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
