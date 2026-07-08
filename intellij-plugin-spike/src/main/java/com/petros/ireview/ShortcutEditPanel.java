package com.petros.ireview;

import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;

import javax.swing.BoxLayout;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Native-Swing edit mode: a checklist of every keymap-bound shortcut, grouped into
 * collapsible sections by IntelliJ's own category (never user-assigned). Category
 * "jump" chips and a filter make a few-hundred-item list findable. Ticking a row
 * writes straight to {@link ShortcutPrefs} — no browser, no JS bridge.
 */
final class ShortcutEditPanel extends JPanel {

    private final List<Section> sections = new ArrayList<>();
    private final JPanel body = new JPanel();

    ShortcutEditPanel(ShortcutPrefs prefs, List<CatalogEntry> catalog, Function<String, String> categoryOf) {
        super(new BorderLayout());

        // Group catalog (already A→Z by label) by category; categories A→Z.
        Map<String, List<CatalogEntry>> grouped = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (CatalogEntry e : catalog) {
            String cat = categoryOf.apply(e.actionId());
            if (cat == null || cat.isBlank()) cat = ShortcutCategories.OTHER;
            grouped.computeIfAbsent(cat, k -> new ArrayList<>()).add(e);
        }

        // --- header: filter + jump chips ---
        JBTextField filter = new JBTextField();
        filter.getEmptyText().setText("Filter shortcuts…");
        filter.getDocument().addDocumentListener(new DocumentAdapter() {
            @Override protected void textChanged(@NotNull DocumentEvent e) { applyFilter(filter.getText()); }
        });

        JPanel chips = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        JPanel north = new JPanel(new BorderLayout());
        north.setBorder(JBUI.Borders.empty(8, 12, 4, 12));
        north.add(filter, BorderLayout.NORTH);
        north.add(chips, BorderLayout.CENTER);
        add(north, BorderLayout.NORTH);

        // --- body: one Section per category ---
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        for (Map.Entry<String, List<CatalogEntry>> en : grouped.entrySet()) {
            Section section = new Section(prefs, en.getKey(), en.getValue());
            sections.add(section);
            body.add(section);
            chips.add(new Chip(section));
        }
        JPanel topAlign = new JPanel(new BorderLayout());
        topAlign.add(body, BorderLayout.NORTH);
        JBScrollPane scroll = new JBScrollPane(topAlign);
        scroll.setBorder(JBUI.Borders.empty());
        add(scroll, BorderLayout.CENTER);
    }

    private void applyFilter(String query) {
        String q = query.toLowerCase().trim();
        for (Section s : sections) s.applyFilter(q);
        revalidate();
        repaint();
    }

    private void jumpTo(Section section) {
        section.expand();
        SwingUtilities.invokeLater(() -> {
            Rectangle b = SwingUtilities.convertRectangle(section.getParent(), section.getBounds(), body);
            body.scrollRectToVisible(new Rectangle(b.x, b.y, 1, Math.min(b.height, 400)));
        });
    }

    /** Clickable category chip that jumps to (and expands) its section. */
    private final class Chip extends JBLabel {
        Chip(Section section) {
            super("  " + section.category + "  " + section.total + "  ");
            setOpaque(true);
            setBackground(UIUtil.getPanelBackground().brighter());
            setForeground(UIUtil.getContextHelpForeground());
            setBorder(JBUI.Borders.customLine(UIUtil.getBoundsColor(), 1));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) { jumpTo(section); }
            });
        }
    }

    /** A collapsible category section: a header row plus its shortcut rows. */
    private static final class Section extends JPanel {
        private final String category;
        private final int total;
        private final List<Row> rows = new ArrayList<>();
        private final JPanel rowsPanel = new JPanel();
        private final JBLabel chevron = new JBLabel("▾");
        private final JBLabel selectedLabel = new JBLabel();
        private boolean collapsed = false;

        Section(ShortcutPrefs prefs, String category, List<CatalogEntry> entries) {
            super(new BorderLayout());
            this.category = category;
            this.total = entries.size();
            setAlignmentX(LEFT_ALIGNMENT);

            // header
            JPanel header = new JPanel(new BorderLayout(8, 0));
            header.setBorder(JBUI.Borders.empty(7, 12));
            header.setBackground(UIUtil.getPanelBackground().darker());
            header.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            left.setOpaque(false);
            chevron.setForeground(UIUtil.getContextHelpForeground());
            JBLabel title = new JBLabel(category.toUpperCase() + "   " + total);
            title.setForeground(UIUtil.getLabelInfoForeground());
            left.add(chevron);
            left.add(title);
            selectedLabel.setForeground(UIUtil.getContextHelpForeground());
            header.add(left, BorderLayout.WEST);
            header.add(selectedLabel, BorderLayout.EAST);
            header.addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) { toggle(); }
            });
            add(header, BorderLayout.NORTH);

            // rows
            rowsPanel.setLayout(new BoxLayout(rowsPanel, BoxLayout.Y_AXIS));
            for (CatalogEntry e : entries) {
                Row row = new Row(prefs, e, this);
                rows.add(row);
                rowsPanel.add(row);
            }
            add(rowsPanel, BorderLayout.CENTER);
            refreshSelected();
        }

        void toggle() { setCollapsed(!collapsed); }
        void expand() { setCollapsed(false); }

        private void setCollapsed(boolean c) {
            collapsed = c;
            chevron.setText(c ? "▸" : "▾");
            rowsPanel.setVisible(!c);
            revalidate();
            repaint();
        }

        void applyFilter(String q) {
            int visible = 0;
            for (Row r : rows) {
                boolean match = q.isEmpty() || r.label.toLowerCase().contains(q);
                r.setVisible(match);
                if (match) visible++;
            }
            // While filtering, force-expand; hide the whole section if nothing matches.
            if (!q.isEmpty()) { collapsed = false; chevron.setText("▾"); rowsPanel.setVisible(true); }
            setVisible(visible > 0);
        }

        void refreshSelected() {
            int n = 0;
            for (Row r : rows) if (r.isSelected()) n++;
            selectedLabel.setText(n == 0 ? "" : n + " selected");
        }
    }

    /** One shortcut row: checkbox + name + keys. */
    private static final class Row extends JPanel {
        private final String label;
        private final JBCheckBox check = new JBCheckBox();

        Row(ShortcutPrefs prefs, CatalogEntry entry, Section section) {
            super(new BorderLayout(8, 0));
            this.label = entry.label();
            setBorder(JBUI.Borders.empty(3, 26, 3, 14));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, JBUI.scale(28)));

            check.setSelected(prefs.isEnabled(entry.actionId()));
            check.addActionListener(a -> {
                prefs.setEnabled(entry.actionId(), check.isSelected());
                section.refreshSelected();
            });

            JBLabel keys = new JBLabel(keysText(entry.groups()));
            keys.setForeground(UIUtil.getContextHelpForeground());

            JPanel west = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            west.setOpaque(false);
            west.add(check);
            west.add(new JBLabel(entry.label()));

            add(west, BorderLayout.WEST);
            add(keys, BorderLayout.EAST);
        }

        boolean isSelected() { return check.isSelected(); }
    }

    /** Render key glyph groups as text, e.g. groups [[⌘,K],[⌘,C]] -> "⌘K  ⌘C". */
    private static String keysText(List<List<String>> groups) {
        List<String> parts = new ArrayList<>();
        for (List<String> group : groups) parts.add(String.join("", group));
        return StringUtil.join(parts, "  ");
    }
}
