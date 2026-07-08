package com.petros.ireview;

import java.util.List;

/**
 * Renders a {@link ResolvedSheet} to a self-contained HTML document styled like
 * the cheat-sheet mockup: category headers, keycap chips, and CSS auto-flowing
 * columns so groups pack into as many columns as the width allows.
 */
public final class ShortcutsHtmlRenderer {

    private ShortcutsHtmlRenderer() {}

    public static String toDocument(ResolvedSheet sheet, boolean dark) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("<!doctype html><html><head><meta charset=\"utf-8\"><style>");
        sb.append(css(dark));
        sb.append("</style></head><body>");
        sb.append("<div class=\"bar\"><h2>Keyboard Shortcuts</h2></div>");

        if (sheet.isError()) {
            sb.append("<p class=\"err\">").append(esc(sheet.error())).append("</p>");
        } else if (sheet.categories().isEmpty()) {
            sb.append("<p class=\"err\">No shortcuts enabled. Edit shortcuts.yml.</p>");
        } else {
            sb.append("<div class=\"board\">");
            for (ResolvedSheet.ResolvedCategory cat : sheet.categories()) {
                sb.append("<div class=\"group\"><div class=\"cat\">").append(esc(cat.name())).append("</div>");
                for (ResolvedSheet.ResolvedEntry e : cat.entries()) {
                    sb.append("<div class=\"row\"><span class=\"name\">").append(esc(e.label())).append("</span>");
                    sb.append("<span class=\"keys\">");
                    if (e.unassigned()) {
                        sb.append("<span class=\"tag\">unassigned</span>");
                    } else {
                        for (List<String> group : e.groups()) {
                            for (String token : group) {
                                sb.append("<span class=\"cap\">").append(esc(token)).append("</span>");
                            }
                        }
                    }
                    sb.append("</span></div>");
                }
                sb.append("</div>");
            }
            sb.append("</div>");
        }

        sb.append("<div class=\"foot\">Press <span class=\"cap\">Esc</span> to close</div>");
        sb.append("</body></html>");
        return sb.toString();
    }

    private static String css(boolean dark) {
        String bg      = dark ? "#2b2d30" : "#ffffff";
        String ink     = dark ? "#dfe1e5" : "#1f2328";
        String muted   = dark ? "#8b9096" : "#8a9099";
        String line    = dark ? "#3c3f43" : "#e6e8eb";
        String capBg   = dark ? "#3a3d41" : "#f6f7f9";
        String capLine = dark ? "#54585d" : "#d6dade";
        String capInk  = dark ? "#d0d3d8" : "#3a4048";
        return ""
            + "*{box-sizing:border-box}"
            + "body{margin:0;background:" + bg + ";color:" + ink + ";"
            + "font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;}"
            + ".bar{padding:16px 22px;border-bottom:1px solid " + line + ";}"
            + ".bar h2{margin:0;font-size:15.5px;font-weight:650;}"
            + ".board{padding:18px 24px;column-width:230px;column-gap:38px;}"
            + ".group{break-inside:avoid;margin-bottom:20px;}"
            + ".cat{font-size:10.5px;font-weight:700;letter-spacing:.09em;color:" + muted + ";"
            + "text-transform:uppercase;margin:0 0 9px;}"
            + ".row{display:flex;align-items:center;gap:12px;padding:5px 0;}"
            + ".name{font-size:13px;}"
            + ".keys{margin-left:auto;display:flex;gap:5px;flex:0 0 auto;}"
            + ".cap{min-width:22px;height:22px;padding:0 6px;border-radius:6px;background:" + capBg + ";"
            + "border:1px solid " + capLine + ";border-bottom-width:2px;color:" + capInk + ";"
            + "font-size:11.5px;font-weight:600;display:inline-flex;align-items:center;justify-content:center;"
            + "font-family:ui-monospace,SFMono-Regular,Menlo,monospace;}"
            + ".tag{font-size:10.5px;color:" + muted + ";font-style:italic;}"
            + ".err{padding:22px;color:" + muted + ";}"
            + ".foot{text-align:center;color:" + muted + ";font-size:11.5px;padding:11px;border-top:1px solid " + line + ";}"
            + ".foot .cap{display:inline-flex;vertical-align:middle;margin:0 2px;}";
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
