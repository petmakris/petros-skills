package com.petros.ireview;

import com.intellij.openapi.diagnostic.Logger;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Loads and parses the bundled {@code /shortcuts/shortcuts.yml} via SnakeYAML. */
public final class ShortcutSheetLoader {

    private static final Logger LOG = Logger.getInstance(ShortcutSheetLoader.class);
    private static final String RESOURCE = "/shortcuts/shortcuts.yml";

    private ShortcutSheetLoader() {}

    public static ShortcutSheet load() {
        try (InputStream in = ShortcutSheetLoader.class.getResourceAsStream(RESOURCE)) {
            if (in == null) return ShortcutSheet.error("shortcuts.yml not found on classpath");
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return parse(content);
        } catch (Exception e) {
            LOG.warn("Failed to load " + RESOURCE, e);
            return ShortcutSheet.error("couldn't load shortcuts.yml: " + e.getMessage());
        }
    }

    public static ShortcutSheet parse(String yaml) {
        try {
            Object loaded = new Yaml().load(yaml.strip());
            return parse(loaded);
        } catch (Exception e) {
            return ShortcutSheet.error("couldn't parse shortcuts.yml: " + e.getMessage());
        }
    }

    private static ShortcutSheet parse(Object root) {
        if (!(root instanceof Map<?, ?> map)) return ShortcutSheet.error("shortcuts.yml: expected a mapping at top level");
        List<ShortcutSheet.Category> categories = new ArrayList<>();
        if (map.get("categories") instanceof List<?> catList) {
            for (Object c : catList) {
                if (!(c instanceof Map<?, ?> cm)) continue;
                String name = asString(cm.get("name"));
                List<ShortcutSheet.Entry> entries = new ArrayList<>();
                if (cm.get("entries") instanceof List<?> entryList) {
                    for (Object e : entryList) {
                        if (!(e instanceof Map<?, ?> em)) continue;
                        String action = asString(em.get("action"));
                        if (action == null || action.isBlank()) continue;
                        String label = asString(em.get("label"));
                        boolean enabled = Boolean.TRUE.equals(em.get("enabled"));
                        entries.add(new ShortcutSheet.Entry(action, label, enabled));
                    }
                }
                categories.add(new ShortcutSheet.Category(name, entries));
            }
        }
        return new ShortcutSheet(categories, null);
    }

    private static String asString(Object o) {
        if (o == null) return null;
        if (o instanceof String s) return s;
        return o.toString();
    }
}
