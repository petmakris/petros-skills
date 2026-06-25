package com.petros.ireview;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
