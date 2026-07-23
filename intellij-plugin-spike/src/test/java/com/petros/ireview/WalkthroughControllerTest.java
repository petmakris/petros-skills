package com.petros.ireview;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WalkthroughControllerTest {

    private static WalkthroughDoc doc(int n) {
        StringBuilder sb = new StringBuilder(
            "{\"question\":\"q\",\"kind\":\"explain\",\"generated_ts\":1,\"steps\":[");
        for (int i = 1; i <= n; i++) {
            if (i > 1) sb.append(',');
            sb.append("{\"id\":").append(i)
              .append(",\"title\":\"step ").append(i)
              .append("\",\"file\":\"F").append(i)
              .append(".java\",\"line\":").append(i * 10)
              .append(",\"snippet\":\"line ").append(i)
              .append("\",\"role\":\"context\",\"markdown\":\"m\"}");
        }
        return WalkthroughDoc.parse(sb.append("]}").toString());
    }

    private static final class RecordingNavigator implements WalkthroughNavigator {
        final List<String> visited = new ArrayList<>();
        @Override public void navigate(WalkthroughStep step) { visited.add(step.file() + ":" + step.line()); }
    }

    @Test void setDocActivatesFirstStep() {
        RecordingNavigator nav = new RecordingNavigator();
        WalkthroughController c = new WalkthroughController(nav);
        c.setDoc(doc(3));
        assertEquals(0, c.index());
        assertEquals(1, c.current().orElseThrow().id());
        assertEquals(List.of("F1.java:10"), nav.visited);
    }

    @Test void nextAndPrevWalkTheList() {
        RecordingNavigator nav = new RecordingNavigator();
        WalkthroughController c = new WalkthroughController(nav);
        c.setDoc(doc(3));
        assertTrue(c.next());
        assertEquals(1, c.index());
        assertTrue(c.next());
        assertEquals(2, c.index());
        assertTrue(c.prev());
        assertEquals(1, c.index());
        assertEquals(List.of("F1.java:10", "F2.java:20", "F3.java:30", "F2.java:20"), nav.visited);
    }

    @Test void nextAtLastStepIsRefusedAndDoesNotNavigate() {
        RecordingNavigator nav = new RecordingNavigator();
        WalkthroughController c = new WalkthroughController(nav);
        c.setDoc(doc(2));
        assertTrue(c.next());
        assertFalse(c.next());
        assertEquals(1, c.index());
        assertEquals(2, nav.visited.size());
    }

    @Test void prevAtFirstStepIsRefused() {
        RecordingNavigator nav = new RecordingNavigator();
        WalkthroughController c = new WalkthroughController(nav);
        c.setDoc(doc(2));
        assertFalse(c.prev());
        assertEquals(0, c.index());
        assertEquals(1, nav.visited.size());
    }

    @Test void jumpToIndexAndId() {
        RecordingNavigator nav = new RecordingNavigator();
        WalkthroughController c = new WalkthroughController(nav);
        c.setDoc(doc(4));
        assertTrue(c.jumpTo(3));
        assertEquals(4, c.current().orElseThrow().id());
        assertFalse(c.jumpTo(9));
        assertFalse(c.jumpTo(-1));
        assertTrue(c.jumpToId(2));
        assertEquals(1, c.index());
        assertFalse(c.jumpToId(99));
    }

    @Test void emptyDocHasNoCurrentStepAndRefusesMoves() {
        RecordingNavigator nav = new RecordingNavigator();
        WalkthroughController c = new WalkthroughController(nav);
        c.setDoc(WalkthroughDoc.EMPTY);
        assertTrue(c.current().isEmpty());
        assertEquals(0, c.size());
        assertFalse(c.next());
        assertFalse(c.prev());
        assertTrue(nav.visited.isEmpty());
    }

    @Test void hidingInlineCardPreservesIndexAndDoesNotRenavigate() {
        RecordingNavigator nav = new RecordingNavigator();
        WalkthroughController c = new WalkthroughController(nav);
        c.setDoc(doc(3));
        c.next();
        int before = nav.visited.size();
        c.setInlineVisible(false);
        assertFalse(c.inlineVisible());
        assertEquals(1, c.index());
        assertEquals(before, nav.visited.size());
    }

    @Test void listenersSeeActivationsAndInlineVisibilityChanges() {
        WalkthroughController c = new WalkthroughController(step -> {});
        List<String> events = new ArrayList<>();
        c.addListener(new WalkthroughController.Listener() {
            @Override public void onStepActivated(WalkthroughStep step, int index, int total) {
                events.add("activate:" + step.id() + ":" + index + "/" + total);
            }
            @Override public void onInlineVisibleChanged(boolean visible) {
                events.add("inline:" + visible);
            }
            @Override public void onDocChanged(WalkthroughDoc doc) {
                events.add("doc:" + doc.steps().size());
            }
        });
        c.setDoc(doc(2));
        c.next();
        c.setInlineVisible(false);
        assertEquals(List.of("doc:2", "activate:1:0/2", "activate:2:1/2", "inline:false"), events);
    }

    @Test void inlineCardIsVisibleByDefault() {
        WalkthroughController c = new WalkthroughController(step -> {});
        assertTrue(c.inlineVisible());
    }

    @Test void settingSameInlineVisibilityIsANoOp() {
        WalkthroughController c = new WalkthroughController(step -> {});
        List<String> events = new ArrayList<>();
        c.addListener(new WalkthroughController.Listener() {
            @Override public void onInlineVisibleChanged(boolean visible) { events.add("inline:" + visible); }
        });
        c.setInlineVisible(true);
        assertTrue(events.isEmpty());
    }
}
