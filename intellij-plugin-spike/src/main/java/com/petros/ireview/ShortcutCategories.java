package com.petros.ireview;

import com.intellij.openapi.actionSystem.ex.QuickList;
import com.intellij.openapi.keymap.Keymap;
import com.intellij.openapi.keymap.KeymapManager;
import com.intellij.openapi.keymap.impl.ui.ActionsTreeUtil;
import com.intellij.openapi.keymap.impl.ui.Group;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * Derives each action's category from IntelliJ's own Keymap group tree — the same
 * grouping shown in Settings → Keymap (built via {@link ActionsTreeUtil#createMainGroup}).
 * Nothing is user-assigned: the category is wherever the action already lives.
 *
 * <p>To get useful granularity we descend one level into container groups (so we get
 * "Edit" / "Navigate" / "Refactor" rather than one giant "Main menu" bucket). Anything
 * we can't place falls back to {@link #OTHER}.
 */
final class ShortcutCategories {

    static final String OTHER = "Other";

    private ShortcutCategories() {}

    /** actionId → category name. Missing/unknown ids should be treated as {@link #OTHER} by callers. */
    static Map<String, String> byAction(@Nullable Project project) {
        Project p = project != null ? project : ProjectManager.getInstance().getDefaultProject();
        Keymap keymap = KeymapManager.getInstance().getActiveKeymap();
        Map<String, String> out = new HashMap<>();
        try {
            Group root = ActionsTreeUtil.createMainGroup(p, keymap, new QuickList[0]);
            for (Object child : root.getChildren()) {
                if (child instanceof Group group) {
                    indexTopLevel(group, out);
                } else if (child instanceof String id) {
                    out.putIfAbsent(id, OTHER);
                }
            }
        } catch (Throwable t) {
            // Internal API — if it changes shape, degrade gracefully to all-Other.
        }
        return out;
    }

    private static void indexTopLevel(Group top, Map<String, String> out) {
        boolean hasSubgroups = false;
        for (Object c : top.getChildren()) {
            if (c instanceof Group) { hasSubgroups = true; break; }
        }
        if (!hasSubgroups) {
            for (String id : top.initIds()) out.putIfAbsent(id, clean(top.getName()));
            return;
        }
        // Container group (e.g. "Main menu"): use each subgroup as the category,
        // and file this group's direct actions under the container's own name.
        for (Object c : top.getChildren()) {
            if (c instanceof Group sub) {
                String name = clean(sub.getName());
                for (String id : sub.initIds()) out.putIfAbsent(id, name);
            } else if (c instanceof String id) {
                out.putIfAbsent(id, clean(top.getName()));
            }
        }
    }

    private static String clean(@Nullable String name) {
        if (name == null || name.isBlank()) return OTHER;
        return name.replace("&", "").trim();
    }
}
