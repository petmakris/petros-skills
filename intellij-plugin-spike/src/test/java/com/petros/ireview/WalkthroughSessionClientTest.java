package com.petros.ireview;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class WalkthroughSessionClientTest {

    private static final String STEPS = """
        {"question":"q","kind":"explain","generated_ts":7,"steps":[
          {"id":1,"title":"one","file":"a.java","line":1,"snippet":"x","role":"context","markdown":"m"},
          {"id":2,"title":"two","file":"b.java","line":9,"snippet":"y","role":"seam","markdown":"m2"}]}
        """;

    private static void await(BooleanSupplier cond) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) return;
            Thread.sleep(25);
        }
        fail("condition not met within 5s");
    }

    private static String sessionsRow(String sid) {
        return "[{\"sid\":\"" + sid + "\",\"title\":\"how sharing is gated\","
             + "\"state_dir\":\"/tmp/state\"}]";
    }

    @Test void attachesAndLoadsSteps() throws Exception {
        try (FakeReviewServer server = new FakeReviewServer()) {
            server.sessionsJson = sessionsRow("wt1");
            server.stepsJson = STEPS;
            server.watcherSeenAt = System.currentTimeMillis() / 1000;
            AtomicReference<WalkthroughDoc> seen = new AtomicReference<>();
            WalkthroughSessionClient client = new WalkthroughSessionClient(
                server.baseUrl(), "/proj", Duration.ofMillis(100));
            client.addListener(new WalkthroughSessionClient.Listener() {
                @Override public void onStepsChanged(WalkthroughDoc doc) { seen.set(doc); }
            });
            client.start();
            try {
                await(() -> seen.get() != null && seen.get().steps().size() == 2);
                assertEquals("how sharing is gated", client.currentSession().orElseThrow().title());
                assertEquals(2, client.doc().steps().size());
                assertEquals("step:2", client.doc().steps().get(1).anchor());
            } finally {
                client.stop();
            }
        }
    }

    @Test void threadChangedEventUpdatesCacheAndClearsPending() throws Exception {
        try (FakeReviewServer server = new FakeReviewServer()) {
            server.sessionsJson = sessionsRow("wt2");
            server.stepsJson = STEPS;
            server.watcherSeenAt = System.currentTimeMillis() / 1000;
            List<String> pendingEvents = new CopyOnWriteArrayList<>();
            WalkthroughSessionClient client = new WalkthroughSessionClient(
                server.baseUrl(), "/proj", Duration.ofMillis(100));
            client.addListener(new WalkthroughSessionClient.Listener() {
                @Override public void onPendingChanged(String anchor, boolean pending) {
                    pendingEvents.add(anchor + "=" + pending);
                }
            });
            client.start();
            try {
                await(() -> client.currentSession().isPresent());
                client.postAsk(2, "is ordering guaranteed?").join();
                await(() -> client.isPending("step:2"));
                assertTrue(server.lastSubmitBody.contains("\"anchor\":\"step:2\""));
                assertTrue(server.lastSubmitBody.contains("is ordering guaranteed?"));

                server.pushSseEvent("thread-changed",
                    "{\"anchor\":\"step:2\",\"latest_synthesis\":\"no, bean order\","
                    + "\"version\":1,\"title\":\"Ordering\",\"question\":\"is ordering guaranteed?\"}");
                await(() -> client.threadFor("step:2").isPresent() && !client.isPending("step:2"));
                var t = client.threadFor("step:2").orElseThrow();
                assertEquals("no, bean order", t.synthesis());
                assertEquals(1, t.version());
                assertEquals("Ordering", t.title());
                assertTrue(pendingEvents.contains("step:2=true"));
                assertTrue(pendingEvents.contains("step:2=false"));
            } finally {
                client.stop();
            }
        }
    }

    @Test void stepsChangedEventReloadsDoc() throws Exception {
        try (FakeReviewServer server = new FakeReviewServer()) {
            server.sessionsJson = sessionsRow("wt3");
            server.stepsJson = "{\"question\":\"q\",\"kind\":\"explain\",\"generated_ts\":0,\"steps\":[]}";
            server.watcherSeenAt = System.currentTimeMillis() / 1000;
            List<Integer> sizes = new CopyOnWriteArrayList<>();
            WalkthroughSessionClient client = new WalkthroughSessionClient(
                server.baseUrl(), "/proj", Duration.ofMillis(100));
            client.addListener(new WalkthroughSessionClient.Listener() {
                @Override public void onStepsChanged(WalkthroughDoc doc) { sizes.add(doc.steps().size()); }
            });
            client.start();
            try {
                await(() -> client.currentSession().isPresent());
                server.stepsJson = STEPS;
                server.pushSseEvent("steps-changed", "{\"generated_ts\":7,\"count\":2}");
                await(() -> client.doc().steps().size() == 2);
                assertTrue(sizes.contains(2));
            } finally {
                client.stop();
            }
        }
    }

    @Test void endedSessionFreezesAndRefusesAsks() throws Exception {
        try (FakeReviewServer server = new FakeReviewServer()) {
            server.sessionsJson = sessionsRow("wt4");
            server.stepsJson = STEPS;
            server.watcherSeenAt = System.currentTimeMillis() / 1000;
            WalkthroughSessionClient client = new WalkthroughSessionClient(
                server.baseUrl(), "/proj", Duration.ofMillis(100));
            client.start();
            try {
                await(() -> client.state() == WalkthroughSessionClient.State.ACTIVE);
                server.ended = true;
                server.endedReason = "finished";
                await(() -> client.state() == WalkthroughSessionClient.State.ENDED);
                assertThrows(Exception.class, () -> client.postAsk(1, "too late").join());
            } finally {
                client.stop();
            }
        }
    }

    @Test void detachesWhenNoSession() throws Exception {
        try (FakeReviewServer server = new FakeReviewServer()) {
            server.sessionsJson = "[]";
            List<String> events = new ArrayList<>();
            WalkthroughSessionClient client = new WalkthroughSessionClient(
                server.baseUrl(), "/proj", Duration.ofMillis(100));
            client.addListener(new WalkthroughSessionClient.Listener() {
                @Override public void onDetached() { events.add("detached"); }
            });
            client.start();
            try {
                await(() -> client.state() == WalkthroughSessionClient.State.DORMANT);
                assertTrue(client.currentSession().isEmpty());
                assertTrue(client.doc().isEmpty());
                // No session was ever attached, so onDetached (which only fires
                // out of handleNoSession's `current != null` branch) never fires.
                assertTrue(events.isEmpty());
            } finally {
                client.stop();
            }
        }
    }

    @Test void detachesWhenSessionDisappearsFromDiscovery() throws Exception {
        try (FakeReviewServer server = new FakeReviewServer()) {
            server.sessionsJson = sessionsRow("wt5");
            server.stepsJson = STEPS;
            server.watcherSeenAt = System.currentTimeMillis() / 1000;
            List<String> events = new CopyOnWriteArrayList<>();
            WalkthroughSessionClient client = new WalkthroughSessionClient(
                server.baseUrl(), "/proj", Duration.ofMillis(100));
            client.addListener(new WalkthroughSessionClient.Listener() {
                @Override public void onDetached() { events.add("detached"); }
            });
            client.start();
            try {
                await(() -> client.currentSession().isPresent() && client.doc().steps().size() == 2);

                server.pushSseEvent("thread-changed",
                    "{\"anchor\":\"step:1\",\"latest_synthesis\":\"yes\","
                    + "\"version\":1,\"title\":\"T\",\"question\":\"q?\"}");
                await(() -> client.threadFor("step:1").isPresent());

                // The server never reports the session as ended (server.ended
                // stays false throughout), so pollDiscover's found==null branch
                // falls through pollLiveness without latching ENDED and calls
                // handleNoSession() — this drives the plain detach path, not
                // the ENDED latch in pollLiveness.
                server.sessionsJson = "[]";

                await(() -> events.contains("detached"));
                assertTrue(client.currentSession().isEmpty());
                assertTrue(client.doc().isEmpty());
                assertTrue(client.threadFor("step:1").isEmpty());
                assertNotEquals(WalkthroughSessionClient.State.ENDED, client.state());
            } finally {
                client.stop();
            }
        }
    }
}
