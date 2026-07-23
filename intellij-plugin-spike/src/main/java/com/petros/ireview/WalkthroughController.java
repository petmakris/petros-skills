package com.petros.ireview;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The single source of truth for a walk: which step is active. The rail panel
 * is always live; the inline card is a complementary layer over the editor
 * whose visibility is toggled here — hiding it never disturbs the current
 * index.
 */
public final class WalkthroughController {

    public interface Listener {
        default void onStepActivated(WalkthroughStep step, int index, int total) {}
        default void onInlineVisibleChanged(boolean visible) {}
        default void onDocChanged(WalkthroughDoc doc) {}
    }

    private final WalkthroughNavigator navigator;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    private volatile WalkthroughDoc doc = WalkthroughDoc.EMPTY;
    private volatile int index = 0;
    private volatile boolean inlineVisible = true;

    public WalkthroughController(WalkthroughNavigator navigator) {
        this.navigator = navigator;
    }

    public WalkthroughDoc doc() { return doc; }

    public int index() { return index; }

    public int size() { return doc.steps().size(); }

    public Optional<WalkthroughStep> current() {
        List<WalkthroughStep> steps = doc.steps();
        return (index >= 0 && index < steps.size()) ? Optional.of(steps.get(index)) : Optional.empty();
    }

    public boolean inlineVisible() { return inlineVisible; }

    public void addListener(Listener l) { listeners.add(l); }

    public void removeListener(Listener l) { listeners.remove(l); }

    /** Install a new step list. Resets to step 1 and activates it. */
    public void setDoc(WalkthroughDoc next) {
        doc = next == null ? WalkthroughDoc.EMPTY : next;
        index = 0;
        for (Listener l : listeners) l.onDocChanged(doc);
        if (!doc.isEmpty()) activate();
    }

    public boolean next() { return jumpTo(index + 1); }

    public boolean prev() { return jumpTo(index - 1); }

    /** @return false when the target is out of range — no navigation happens. */
    public boolean jumpTo(int target) {
        if (doc.isEmpty() || target < 0 || target >= doc.steps().size()) return false;
        index = target;
        activate();
        return true;
    }

    public boolean jumpToId(int stepId) {
        int i = doc.indexOfId(stepId);
        return i >= 0 && jumpTo(i);
    }

    /** Showing/hiding the inline card keeps the current step; no re-navigation. */
    public void setInlineVisible(boolean visible) {
        if (visible == inlineVisible) return;
        inlineVisible = visible;
        for (Listener l : listeners) l.onInlineVisibleChanged(inlineVisible);
    }

    private void activate() {
        WalkthroughStep step = doc.steps().get(index);
        navigator.navigate(step);
        for (Listener l : listeners) l.onStepActivated(step, index, doc.steps().size());
    }
}
