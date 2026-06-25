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
    void staleWatcherHeartbeatGoesStaleAndBlocksSubmission() throws Exception {
        try (FakeReviewServer server = new FakeReviewServer()) {
            server.sessionsJson =
                "[{\"sid\":\"abc\",\"pr_ref\":\"PR1\",\"title\":\"t\","
              + "\"state_dir\":\"/tmp/x\"}]";
            // Watcher last beat 100s ago → the Claude session is gone.
            server.watcherSeenAt = System.currentTimeMillis() / 1000 - 100;
            CountDownLatch stale = new CountDownLatch(1);
            ReviewSessionClient client = new ReviewSessionClient(
                server.baseUrl(),
                "/proj/montblanc",
                Duration.ofMillis(100));
            client.addListener(new ReviewSessionClient.Listener() {
                @Override public void onStateChanged(ReviewSessionClient.State state) {
                    if (state == ReviewSessionClient.State.STALE) stale.countDown();
                }
            });
            client.start();
            assertTrue(stale.await(2, TimeUnit.SECONDS), "should detect dead watcher");

            // A submission while stale must fail fast and never reach the server.
            var f = client.postComment("foo:R:1", "hi");
            assertThrows(Exception.class, () -> f.get(1, TimeUnit.SECONDS));
            assertEquals(0, server.submitCount.get(),
                "stale session must not POST submits");
            assertFalse(client.isPending("foo:R:1"), "must not leave a pending spinner");
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
}
