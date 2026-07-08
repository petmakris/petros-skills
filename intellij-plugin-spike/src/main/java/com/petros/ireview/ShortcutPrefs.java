package com.petros.ireview;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Application-level settings: which keymap actions the user has featured in the
 * cheat-sheet, and the category assigned to each. Persisted by the IDE to
 * {@code ireview-shortcuts.xml}. Disabling never forgets the category.
 */
@State(name = "IReviewShortcutPrefs", storages = @Storage("ireview-shortcuts.xml"))
public final class ShortcutPrefs implements PersistentStateComponent<ShortcutPrefs.State> {

    public static final String DEFAULT_CATEGORY = "General";

    /** One remembered action. Mutable bean with a no-arg ctor for IDE XML serialization. */
    public static final class Assignment {
        public String actionId;
        public String category;   // may be null
        public boolean enabled;
        public Assignment() {}
        public Assignment(String actionId, String category, boolean enabled) {
            this.actionId = actionId; this.category = category; this.enabled = enabled;
        }
    }

    public static final class State {
        public boolean initialized = false;
        public List<Assignment> assignments = new ArrayList<>();
    }

    private State state = new State();

    @Override public State getState() { return state; }
    @Override public void loadState(@NotNull State s) { this.state = s; }

    private Assignment find(String id) {
        for (Assignment a : state.assignments) if (id.equals(a.actionId)) return a;
        return null;
    }
    private Assignment findOrCreate(String id) {
        Assignment a = find(id);
        if (a == null) { a = new Assignment(id, null, false); state.assignments.add(a); }
        return a;
    }

    public boolean isEnabled(String actionId) { Assignment a = find(actionId); return a != null && a.enabled; }
    public String categoryOf(String actionId) { Assignment a = find(actionId); return a == null ? null : a.category; }
    public void setEnabled(String actionId, boolean on) { findOrCreate(actionId).enabled = on; }
    public void setCategory(String actionId, String category) {
        if (category == null || category.isBlank()) return;
        findOrCreate(actionId).category = category.trim();
    }
    public List<String> categories() {
        return state.assignments.stream()
                .map(a -> a.category)
                .filter(c -> c != null && !c.isBlank())
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .collect(Collectors.toList());
    }
    public List<Assignment> assignments() { return state.assignments; }
    public boolean isInitialized() { return state.initialized; }
    public void markInitialized() { state.initialized = true; }
}
