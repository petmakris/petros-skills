package com.petros.ireview;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ShortcutSheetLoaderTest {

    @Test
    void parsesCategoriesEntriesAndFlags() {
        String yaml = """
            categories:
              - name: Navigation
                entries:
                  - action: GotoClass
                    enabled: true
                  - action: FindUsages
                    label: Find Usages
                    enabled: false
            """;
        ShortcutSheet sheet = ShortcutSheetLoader.parse(yaml);

        assertFalse(sheet.isError(), sheet.error());
        assertEquals(1, sheet.categories().size());
        ShortcutSheet.Category nav = sheet.categories().get(0);
        assertEquals("Navigation", nav.name());
        assertEquals(2, nav.entries().size());

        ShortcutSheet.Entry first = nav.entries().get(0);
        assertEquals("GotoClass", first.action());
        assertNull(first.label());
        assertTrue(first.enabled());

        ShortcutSheet.Entry second = nav.entries().get(1);
        assertEquals("Find Usages", second.label());
        assertFalse(second.enabled());
    }

    @Test
    void entryWithoutActionIsSkipped() {
        String yaml = """
            categories:
              - name: X
                entries:
                  - label: no action here
                    enabled: true
                  - action: GotoClass
                    enabled: true
            """;
        ShortcutSheet sheet = ShortcutSheetLoader.parse(yaml);
        assertEquals(1, sheet.categories().get(0).entries().size());
        assertEquals("GotoClass", sheet.categories().get(0).entries().get(0).action());
    }

    @Test
    void malformedYamlReturnsErrorNotThrow() {
        ShortcutSheet sheet = ShortcutSheetLoader.parse("categories: [ this is : broken");
        assertTrue(sheet.isError());
        assertNotNull(sheet.error());
    }

    @Test
    void bundledResourceLoads() {
        ShortcutSheet sheet = ShortcutSheetLoader.load();
        assertFalse(sheet.isError(), sheet.error());
        assertFalse(sheet.categories().isEmpty());
    }
}
