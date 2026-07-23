package com.petros.ireview;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

class ReviewSessionClientTest {

    @Test
    void emitsAttachedWhenDiscoverFindsSession() throws Exception {
        try (FakeReviewServer server = new FakeReviewServer()) {
            server.sessionsJson =
                "[{\"sid\":\"abc\",\"pr_ref\":\"PMP-171\",\"title\":\"Proposal dashboard\","
              + "\"state_dir\":\"/tmp/x\"}]";
            CountDownLatch attached = new CountDownLatch(1);
            ReviewSessionClient client = new ReviewSessionClient(
                server.baseUrl(),
                "/proj/montblanc",
                Duration.ofMillis(100));
            client.addListener(new ReviewSessionClient.Listener() {
                @Override public void onAttached(ReviewSessionClient.SessionInfo info) {
                    assertEquals("abc", info.sid());
                    assertEquals("PMP-171", info.prRef());
                    attached.countDown();
                }
            });
            client.start();
            assertTrue(attached.await(2, TimeUnit.SECONDS), "should attach within 2s");
            client.stop();
        }
    }

    @Test
    void pausedWatcherHeartbeatBlocksSubmission() throws Exception {
        try (FakeReviewServer server = new FakeReviewServer()) {
            server.sessionsJson =
                "[{\"sid\":\"abc\",\"pr_ref\":\"PR1\",\"title\":\"t\","
              + "\"state_dir\":\"/tmp/x\"}]";
            // Watcher last beat 100s ago, but server does not (yet) report
            // ended → recoverable PAUSED tier.
            server.watcherSeenAt = System.currentTimeMillis() / 1000 - 100;
            CountDownLatch paused = new CountDownLatch(1);
            ReviewSessionClient client = new ReviewSessionClient(
                server.baseUrl(),
                "/proj/montblanc",
                Duration.ofMillis(100));
            client.addListener(new ReviewSessionClient.Listener() {
                @Override public void onStateChanged(ReviewSessionClient.State state) {
                    if (state == ReviewSessionClient.State.PAUSED) paused.countDown();
                }
            });
            client.start();
            assertTrue(paused.await(2, TimeUnit.SECONDS), "should detect paused watcher");

            // A submission while paused must fail fast and never reach the server.
            var f = client.postComment("foo:R:1", "hi", "");
            assertThrows(Exception.class, () -> f.get(1, TimeUnit.SECONDS));
            assertEquals(0, server.submitCount.get(),
                "paused session must not POST submits");
            assertFalse(client.isPending("foo:R:1"), "must not leave a pending spinner");
            client.stop();
        }
    }

    @Test
    void serverEndedLatchesIntoFrozenReadOnly() throws Exception {
        try (FakeReviewServer server = new FakeReviewServer()) {
            server.sessionsJson =
                "[{\"sid\":\"abc\",\"pr_ref\":\"PR1\",\"title\":\"t\",\"state_dir\":\"/tmp/x\"}]";
            server.watcherSeenAt = System.currentTimeMillis() / 1000; // fresh → ACTIVE
            CountDownLatch attached = new CountDownLatch(1);
            CountDownLatch ended = new CountDownLatch(1);
            ReviewSessionClient client = new ReviewSessionClient(
                server.baseUrl(), "/proj/montblanc", Duration.ofMillis(80));
            client.addListener(new ReviewSessionClient.Listener() {
                @Override public void onAttached(ReviewSessionClient.SessionInfo info) { attached.countDown(); }
                @Override public void onStateChanged(ReviewSessionClient.State s) {
                    if (s == ReviewSessionClient.State.ENDED) ended.countDown();
                }
            });
            client.start();
            assertTrue(attached.await(2, TimeUnit.SECONDS));

            // Server now reports the session ended (watcher-dead past reap).
            server.endedReason = "dead";
            server.ended = true;
            assertTrue(ended.await(2, TimeUnit.SECONDS), "should latch ENDED from /poll");

            // Frozen: still attached (findings preserved), but submits are blocked.
            assertTrue(client.currentSession().isPresent(), "ENDED freezes, does not detach");
            var f = client.postComment("foo:R:1", "hi", "");
            assertThrows(Exception.class, () -> f.get(1, TimeUnit.SECONDS));
            assertEquals(0, server.submitCount.get(), "ended session must not POST");

            // Latch: a returning heartbeat / ended=false must NOT un-freeze.
            server.ended = false;
            server.endedReason = null;
            server.watcherSeenAt = System.currentTimeMillis() / 1000;
            Thread.sleep(400); // several poll cycles
            assertEquals(ReviewSessionClient.State.ENDED, client.state(),
                "ENDED is a one-way latch");
            client.stop();
        }
    }

    @Test
    void frozenSessionStaysWhenDiscoveryEmpties() throws Exception {
        // The reported bug: cancelling/ending must not blank the panel nor fall
        // back to another session. A frozen session keeps showing its own
        // findings when discovery goes empty (the dead session is reaped).
        try (FakeReviewServer server = new FakeReviewServer()) {
            server.sessionsJson =
                "[{\"sid\":\"abc\",\"pr_ref\":\"PR1\",\"title\":\"t\",\"state_dir\":\"/tmp/x\"}]";
            server.watcherSeenAt = System.currentTimeMillis() / 1000;
            CountDownLatch ended = new CountDownLatch(1);
            ReviewSessionClient client = new ReviewSessionClient(
                server.baseUrl(), "/proj/montblanc", Duration.ofMillis(80));
            client.addListener(new ReviewSessionClient.Listener() {
                @Override public void onStateChanged(ReviewSessionClient.State s) {
                    if (s == ReviewSessionClient.State.ENDED) ended.countDown();
                }
            });
            client.start();
            server.endedReason = "cancelled";
            server.ended = true;
            assertTrue(ended.await(2, TimeUnit.SECONDS));

            // Discovery now empties (real server reaps terminal/dead sessions).
            server.sessionsJson = "[]";
            Thread.sleep(400); // several poll cycles
            assertEquals(ReviewSessionClient.State.ENDED, client.state(),
                "frozen panel must not blank when discovery empties");
            assertTrue(client.currentSession().isPresent(), "must keep its own session");
            assertEquals("abc", client.currentSession().get().sid());
            client.stop();
        }
    }

    @Test
    void newLiveSessionSupersedesFrozenPanel() throws Exception {
        try (FakeReviewServer server = new FakeReviewServer()) {
            server.sessionsJson =
                "[{\"sid\":\"abc\",\"pr_ref\":\"PR1\",\"title\":\"t\",\"state_dir\":\"/tmp/a\"}]";
            server.watcherSeenAt = System.currentTimeMillis() / 1000;
            CountDownLatch ended = new CountDownLatch(1);
            CountDownLatch attachedDef = new CountDownLatch(1);
            ReviewSessionClient client = new ReviewSessionClient(
                server.baseUrl(), "/proj/montblanc", Duration.ofMillis(80));
            client.addListener(new ReviewSessionClient.Listener() {
                @Override public void onAttached(ReviewSessionClient.SessionInfo info) {
                    if ("def".equals(info.sid())) attachedDef.countDown();
                }
                @Override public void onStateChanged(ReviewSessionClient.State s) {
                    if (s == ReviewSessionClient.State.ENDED) ended.countDown();
                }
            });
            client.start();
            server.endedReason = "dead";
            server.ended = true;
            assertTrue(ended.await(2, TimeUnit.SECONDS), "freeze abc first");

            // A brand-new live review appears (different sid) → it supersedes.
            server.ended = false;
            server.endedReason = null;
            server.watcherSeenAt = System.currentTimeMillis() / 1000;
            server.sessionsJson =
                "[{\"sid\":\"def\",\"pr_ref\":\"PR2\",\"title\":\"t2\",\"state_dir\":\"/tmp/b\"}]";
            assertTrue(attachedDef.await(2, TimeUnit.SECONDS),
                "a different LIVE session should supersede the frozen one");
            assertEquals("def", client.currentSession().orElseThrow().sid());
            client.stop();
        }
    }

    @Test
    void cancelSessionPostsCancelAndDetaches() throws Exception {
        try (FakeReviewServer server = new FakeReviewServer()) {
            server.sessionsJson =
                "[{\"sid\":\"abc\",\"pr_ref\":\"PR1\",\"title\":\"t\","
              + "\"state_dir\":\"/tmp/x\"}]";
            server.watcherSeenAt = System.currentTimeMillis() / 1000; // fresh → active
            CountDownLatch attached = new CountDownLatch(1);
            CountDownLatch detached = new CountDownLatch(1);
            ReviewSessionClient client = new ReviewSessionClient(
                server.baseUrl(),
                "/proj/montblanc",
                Duration.ofMillis(100));
            client.addListener(new ReviewSessionClient.Listener() {
                @Override public void onAttached(ReviewSessionClient.SessionInfo info) {
                    attached.countDown();
                }
                @Override public void onDetached() { detached.countDown(); }
            });
            client.start();
            assertTrue(attached.await(2, TimeUnit.SECONDS), "should attach first");

            client.cancelSession().get(2, TimeUnit.SECONDS);
            assertEquals(1, server.cancelCount.get(), "should POST /api/cancel once");
            assertTrue(detached.await(2, TimeUnit.SECONDS),
                "cancel should detach the session");
            assertTrue(client.currentSession().isEmpty(), "no current session after cancel");
            client.stop();
        }
    }

    @Test
    void receivesThreadChangedEvent() throws Exception {
        try (FakeReviewServer server = new FakeReviewServer()) {
            server.sessionsJson =
                "[{\"sid\":\"abc\",\"pr_ref\":\"PR1\",\"title\":\"t\","
              + "\"state_dir\":\"/tmp/x\"}]";
            CountDownLatch gotEvent = new CountDownLatch(1);
            ReviewSessionClient client = new ReviewSessionClient(
                server.baseUrl(),
                "/proj/montblanc",
                Duration.ofMillis(100));
            client.addListener(new ReviewSessionClient.Listener() {
                @Override public void onThreadChanged(String anchor, String synthesis, int version) {
                    if ("foo:R:1".equals(anchor) && "hello".equals(synthesis)) {
                        gotEvent.countDown();
                    }
                }
            });
            client.start();
            Thread.sleep(300); // let it attach + open SSE
            server.pushSseEvent("thread-changed",
                "{\"anchor\":\"foo:R:1\",\"latest_synthesis\":\"hello\",\"version\":1,\"updated_at\":1}");
            assertTrue(gotEvent.await(3, TimeUnit.SECONDS));
            client.stop();
        }
    }

    @Test
    void exposesAnchorTextAndParsesTrickySynthesis() throws Exception {
        try (FakeReviewServer server = new FakeReviewServer()) {
            server.sessionsJson =
                "[{\"sid\":\"abc\",\"pr_ref\":\"PR1\",\"title\":\"t\",\"state_dir\":\"/tmp/x\"}]";
            // Synthesis text full of JSON-hostile characters; anchor_text present.
            server.threadsJson =
                "{\"foo:R:1\":{\"latest_synthesis\":\"a \\\"quote\\\" and {brace}\\nline2\","
              + "\"version\":3,\"anchor_text\":\"  return foo(bar);\"}}";
            CountDownLatch seeded = new CountDownLatch(1);
            ReviewSessionClient client = new ReviewSessionClient(
                server.baseUrl(), "/proj/montblanc", Duration.ofMillis(100));
            client.addListener(new ReviewSessionClient.Listener() {
                @Override public void onThreadChanged(String anchor, String synthesis, int version) {
                    if ("foo:R:1".equals(anchor)) seeded.countDown();
                }
            });
            client.start();
            assertTrue(seeded.await(2, TimeUnit.SECONDS));
            var ts = client.threadFor("foo:R:1").orElseThrow();
            assertEquals("  return foo(bar);", ts.anchorText());
            assertEquals("a \"quote\" and {brace}\nline2", ts.synthesis());
            assertEquals(3, ts.version());
            client.stop();
        }
    }

    @Test
    void exposesTitleAndQuestion() throws Exception {
        try (FakeReviewServer server = new FakeReviewServer()) {
            server.sessionsJson =
                "[{\"sid\":\"abc\",\"pr_ref\":\"PR1\",\"title\":\"t\",\"state_dir\":\"/tmp/x\"}]";
            server.threadsJson =
                "{\"foo:R:1\":{\"latest_synthesis\":\"because **foo** is null\",\"version\":2,"
              + "\"anchor_text\":\"return foo();\",\"title\":\"Null check on foo\","
              + "\"question\":\"why null-checked?\"}}";
            CountDownLatch seeded = new CountDownLatch(1);
            ReviewSessionClient client = new ReviewSessionClient(
                server.baseUrl(), "/proj/montblanc", Duration.ofMillis(100));
            client.addListener(new ReviewSessionClient.Listener() {
                @Override public void onThreadChanged(String anchor, String synthesis, int version) {
                    if ("foo:R:1".equals(anchor)) seeded.countDown();
                }
            });
            client.start();
            assertTrue(seeded.await(2, TimeUnit.SECONDS));
            var ts = client.threadFor("foo:R:1").orElseThrow();
            assertEquals("Null check on foo", ts.title());
            assertEquals("why null-checked?", ts.question());
            client.stop();
        }
    }

    @Test
    void discoveryBlipsBelowThresholdDoNotDetach() throws Exception {
        try (FakeReviewServer server = new FakeReviewServer()) {
            server.sessionsJson =
                "[{\"sid\":\"abc\",\"pr_ref\":\"PR1\",\"title\":\"t\",\"state_dir\":\"/tmp/x\"}]";
            server.watcherSeenAt = System.currentTimeMillis() / 1000;
            CountDownLatch attached = new CountDownLatch(1);
            AtomicInteger detaches = new AtomicInteger();
            ReviewSessionClient client = new ReviewSessionClient(
                server.baseUrl(), "/proj/montblanc", Duration.ofMillis(100));
            client.addListener(new ReviewSessionClient.Listener() {
                @Override public void onAttached(ReviewSessionClient.SessionInfo info) {
                    attached.countDown();
                }
                @Override public void onDetached() { detaches.incrementAndGet(); }
            });
            client.start();
            assertTrue(attached.await(2, TimeUnit.SECONDS));

            // Two consecutive failures — below the 3-strike threshold. The
            // pre-fix behaviour detached (and wiped the cache) on the FIRST one.
            server.sessionsFailuresRemaining.set(2);
            Thread.sleep(700); // several poll cycles: fail, fail, recover
            assertEquals(0, detaches.get(), "blips below the threshold must not detach");
            assertTrue(client.currentSession().isPresent(), "session must survive the blips");
            client.stop();
        }
    }

    @Test
    void consecutiveDiscoveryFailuresDetachAndGoOffline() throws Exception {
        try (FakeReviewServer server = new FakeReviewServer()) {
            server.sessionsJson =
                "[{\"sid\":\"abc\",\"pr_ref\":\"PR1\",\"title\":\"t\",\"state_dir\":\"/tmp/x\"}]";
            server.watcherSeenAt = System.currentTimeMillis() / 1000;
            CountDownLatch attached = new CountDownLatch(1);
            CountDownLatch detached = new CountDownLatch(1);
            CountDownLatch offline = new CountDownLatch(1);
            ReviewSessionClient client = new ReviewSessionClient(
                server.baseUrl(), "/proj/montblanc", Duration.ofMillis(100));
            client.addListener(new ReviewSessionClient.Listener() {
                @Override public void onAttached(ReviewSessionClient.SessionInfo info) {
                    attached.countDown();
                }
                @Override public void onDetached() { detached.countDown(); }
                @Override public void onStateChanged(ReviewSessionClient.State s) {
                    if (s == ReviewSessionClient.State.OFFLINE) offline.countDown();
                }
            });
            client.start();
            assertTrue(attached.await(2, TimeUnit.SECONDS));

            // Discovery now fails on every poll — a real outage.
            server.sessionsFailuresRemaining.set(Integer.MAX_VALUE);
            assertTrue(detached.await(3, TimeUnit.SECONDS),
                "a sustained outage must eventually detach");
            assertTrue(offline.await(2, TimeUnit.SECONDS),
                "an unreachable server must surface as OFFLINE, not idle");
            client.stop();
        }
    }

    @Test
    void reResolvesServerUrlAfterRestartOnNewPort() throws Exception {
        try (FakeReviewServer server = new FakeReviewServer()) {
            server.sessionsJson =
                "[{\"sid\":\"abc\",\"pr_ref\":\"PR1\",\"title\":\"t\",\"state_dir\":\"/tmp/x\"}]";
            Path cfg = Files.createTempFile("ireview-server", ".json");
            try {
                // server.json initially points at a dead port (the "old" server).
                Files.writeString(cfg, "{\"url\":\"http://127.0.0.1:9\"}");
                CountDownLatch attached = new CountDownLatch(1);
                ReviewSessionClient client = new ReviewSessionClient(
                    () -> readUrl(cfg, "http://127.0.0.1:9"),
                    "/proj/montblanc", Duration.ofMillis(100));
                client.addListener(new ReviewSessionClient.Listener() {
                    @Override public void onAttached(ReviewSessionClient.SessionInfo info) {
                        attached.countDown();
                    }
                });
                client.start();
                Thread.sleep(300); // a few polls against the dead URL
                assertTrue(client.currentSession().isEmpty(), "dead URL can't attach");

                // The server "restarts" on its real port and rewrites server.json.
                Files.writeString(cfg, "{\"url\":\"" + server.baseUrl() + "\"}");
                assertTrue(attached.await(3, TimeUnit.SECONDS),
                    "a failed poll must re-resolve server.json and pick up the new URL");
                client.stop();
            } finally {
                Files.deleteIfExists(cfg);
            }
        }
    }

    /** Same shape as ReviewSessionService's supplier: regex the url field. */
    private static String readUrl(Path cfg, String fallback) {
        try {
            Matcher m = Pattern.compile("\"url\"\\s*:\\s*\"([^\"]+)\"")
                .matcher(Files.readString(cfg));
            if (m.find()) return m.group(1);
        } catch (java.io.IOException ignored) {
        }
        return fallback;
    }

    @Test
    void sessionSwitchDuringSlowSeedDoesNotPolluteNewCache() throws Exception {
        try (FakeReviewServer server = new FakeReviewServer()) {
            server.sessionsJson =
                "[{\"sid\":\"abc\",\"pr_ref\":\"PR1\",\"title\":\"t\",\"state_dir\":\"/tmp/a\"}]";
            server.watcherSeenAt = System.currentTimeMillis() / 1000;
            // The old session's seed answers slowly, with the OLD threads (the
            // fake captures the body before the delay).
            server.threadsJson =
                "{\"old.java:R:1\":{\"latest_synthesis\":\"stale answer\",\"version\":1}}";
            server.threadsDelayMs = 700;
            CountDownLatch attachedAbc = new CountDownLatch(1);
            CountDownLatch attachedDef = new CountDownLatch(1);
            ReviewSessionClient client = new ReviewSessionClient(
                server.baseUrl(), "/proj/montblanc", Duration.ofMillis(100));
            client.addListener(new ReviewSessionClient.Listener() {
                @Override public void onAttached(ReviewSessionClient.SessionInfo info) {
                    if ("abc".equals(info.sid())) attachedAbc.countDown();
                    if ("def".equals(info.sid())) attachedDef.countDown();
                }
            });
            client.start();
            assertTrue(attachedAbc.await(2, TimeUnit.SECONDS));

            // Switch sessions while abc's seed request is still in flight.
            Thread.sleep(150);
            server.threadsDelayMs = 0;
            server.threadsJson = "{}"; // def has no threads
            server.sessionsJson =
                "[{\"sid\":\"def\",\"pr_ref\":\"PR2\",\"title\":\"t2\",\"state_dir\":\"/tmp/b\"}]";
            assertTrue(attachedDef.await(2, TimeUnit.SECONDS));

            // Let abc's delayed seed response land (and be discarded).
            Thread.sleep(900);
            assertFalse(client.threadFor("old.java:R:1").isPresent(),
                "old session's slow seed must not write into the new session's cache");
            client.stop();
        }
    }

    @Test
    void metadataOnlyVersionBumpClearsPendingAndNotifies() throws Exception {
        try (FakeReviewServer server = new FakeReviewServer()) {
            server.sessionsJson =
                "[{\"sid\":\"abc\",\"pr_ref\":\"PR1\",\"title\":\"t\",\"state_dir\":\"/tmp/x\"}]";
            server.watcherSeenAt = System.currentTimeMillis() / 1000;
            CountDownLatch gotV1 = new CountDownLatch(1);
            CountDownLatch gotV2 = new CountDownLatch(1);
            ReviewSessionClient client = new ReviewSessionClient(
                server.baseUrl(), "/proj/montblanc", Duration.ofMillis(100));
            client.addListener(new ReviewSessionClient.Listener() {
                @Override public void onThreadChanged(String anchor, String synthesis, int version) {
                    if (!"foo:R:1".equals(anchor)) return;
                    if (version == 1) gotV1.countDown();
                    if (version == 2) gotV2.countDown();
                }
            });
            client.start();
            Thread.sleep(300); // let it attach + open SSE
            server.pushSseEvent("thread-changed",
                "{\"anchor\":\"foo:R:1\",\"latest_synthesis\":\"hello\",\"version\":1}");
            assertTrue(gotV1.await(3, TimeUnit.SECONDS));

            // Ask a question, then the server dedups the reply: same synthesis
            // text, only the version bumps. Pending must clear and listeners
            // must still be notified — otherwise the spinner spins forever.
            client.postComment("foo:R:1", "again?", "").get(2, TimeUnit.SECONDS);
            assertTrue(client.isPending("foo:R:1"));
            server.pushSseEvent("thread-changed",
                "{\"anchor\":\"foo:R:1\",\"latest_synthesis\":\"hello\",\"version\":2}");
            assertTrue(gotV2.await(3, TimeUnit.SECONDS),
                "a version-only bump must notify listeners");
            assertFalse(client.isPending("foo:R:1"),
                "a version-only bump must clear pending");
            assertEquals(2, client.threadFor("foo:R:1").orElseThrow().version());
            client.stop();
        }
    }

    @Test
    void postCommentSendsAnchorText() throws Exception {
        try (FakeReviewServer server = new FakeReviewServer()) {
            server.sessionsJson =
                "[{\"sid\":\"abc\",\"pr_ref\":\"PR1\",\"title\":\"t\",\"state_dir\":\"/tmp/x\"}]";
            server.watcherSeenAt = System.currentTimeMillis() / 1000;
            CountDownLatch attached = new CountDownLatch(1);
            ReviewSessionClient client = new ReviewSessionClient(
                server.baseUrl(), "/proj/montblanc", Duration.ofMillis(100));
            client.addListener(new ReviewSessionClient.Listener() {
                @Override public void onAttached(ReviewSessionClient.SessionInfo info) {
                    attached.countDown();
                }
            });
            client.start();
            assertTrue(attached.await(2, TimeUnit.SECONDS));
            client.postComment("foo:R:1", "why?", "  return foo(bar);").get(2, TimeUnit.SECONDS);
            assertNotNull(server.lastSubmitBody);
            assertTrue(server.lastSubmitBody.contains("\"anchor_text\""),
                "submit body must carry anchor_text");
            assertTrue(server.lastSubmitBody.contains("return foo(bar);"));
            client.stop();
        }
    }
}
