package com.petros.ireview;

import java.util.List;

/**
 * Renders the read-only cheat-sheet (view mode) as a self-contained HTML document,
 * grouped into categories. Edit mode is a native-Swing component ({@link ShortcutEditPanel}),
 * not HTML, because the JCEF JS→Java bridge is unreliable under out-of-process JCEF.
 */
public final class ShortcutsHtmlRenderer {

    private ShortcutsHtmlRenderer() {}

    /** Backward-compatible view render without an Edit button (fallback + legacy tests). */
    public static String toDocument(ResolvedSheet sheet, boolean dark) {
        return renderView(sheet, dark, false, "");
    }

    public static String renderView(ResolvedSheet sheet, boolean dark, boolean editButton, String bridgeScript) {
        StringBuilder sb = new StringBuilder(4096);
        head(sb, dark);
        sb.append("<div class=\"bar\"><h2>Keyboard Shortcuts</h2>");
        if (editButton) {
            sb.append("<button class=\"btn\" onclick=\"ireviewSend('{&quot;type&quot;:&quot;enterEdit&quot;}')\">&#9998; Edit</button>");
        }
        sb.append("</div>");

        if (sheet.isError()) {
            sb.append("<p class=\"err\">").append(esc(sheet.error())).append("</p>");
        } else if (sheet.categories().isEmpty()) {
            sb.append("<p class=\"err\">No shortcuts featured yet. Click Edit to choose some.</p>");
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
                            sb.append("<span class=\"grp\">");
                            for (String token : group) sb.append("<span class=\"cap\">").append(esc(token)).append("</span>");
                            sb.append("</span>");
                        }
                    }
                    sb.append("</span></div>");
                }
                sb.append("</div>");
            }
            sb.append("</div>");
        }
        sb.append("<div class=\"foot\">Press <span class=\"cap\">Esc</span> to close</div>");
        script(sb, bridgeScript, "");
        return sb.append("</body></html>").toString();
    }

    private static void head(StringBuilder sb, boolean dark) {
        sb.append("<!doctype html><html><head><meta charset=\"utf-8\"><style>").append(css(dark)).append("</style></head><body>");
    }

    private static void script(StringBuilder sb, String bridgeScript, String extra) {
        sb.append("<script>").append(bridgeScript == null ? "" : bridgeScript).append(extra).append("</script>");
    }

    private static String css(boolean dark) {
        String bg = dark ? "#2b2d30" : "#ffffff";
        String ink = dark ? "#dfe1e5" : "#1f2328";
        String muted = dark ? "#8b9096" : "#8a9099";
        String line = dark ? "#3c3f43" : "#e6e8eb";
        String capBg = dark ? "#3a3d41" : "#f6f7f9";
        String capLine = dark ? "#54585d" : "#d6dade";
        String capInk = dark ? "#d0d3d8" : "#3a4048";
        String rowHover = dark ? "#34373b" : "#f7f8fa";
        String accSoft = dark ? "#20364f" : "#eaf1fe";
        String accInk = dark ? "#8ab4f8" : "#1e5fd0";
        return ""
            + "*{box-sizing:border-box}"
            + "body{margin:0;background:" + bg + ";color:" + ink + ";font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;}"
            + ".bar{display:flex;align-items:center;justify-content:space-between;padding:14px 20px;border-bottom:1px solid " + line + ";}"
            + ".bar h2{margin:0;font-size:15px;font-weight:650;} .muted{color:" + muted + ";font-weight:500;} .acc{color:" + accInk + ";}"
            + ".btn{font-size:12.5px;font-weight:600;padding:6px 13px;border-radius:8px;border:1px solid " + capLine + ";background:transparent;color:" + ink + ";cursor:pointer;}"
            + ".btn.primary{background:#3b82f6;border-color:#3b82f6;color:#fff;}"
            + ".board{padding:16px 22px;column-width:250px;column-gap:36px;} .group{break-inside:avoid;margin-bottom:18px;}"
            + ".cat{font-size:10.5px;font-weight:700;letter-spacing:.09em;color:" + muted + ";text-transform:uppercase;margin:0 0 8px;}"
            + ".row{display:flex;align-items:center;gap:12px;padding:5px 0;} .name{font-size:13px;}"
            + ".keys,.ekeys{margin-left:auto;display:flex;gap:5px;flex:0 0 auto;} .grp{display:inline-flex;gap:5px;}"
            + ".cap{min-width:22px;height:22px;padding:0 6px;border-radius:6px;background:" + capBg + ";border:1px solid " + capLine + ";border-bottom-width:2px;color:" + capInk + ";font-size:11.5px;font-weight:600;display:inline-flex;align-items:center;justify-content:center;font-family:ui-monospace,SFMono-Regular,Menlo,monospace;}"
            + ".tag{font-size:10.5px;color:" + muted + ";font-style:italic;}"
            + ".err{padding:22px;color:" + muted + ";}"
            + ".foot{text-align:center;color:" + muted + ";font-size:11.5px;padding:10px;border-top:1px solid " + line + ";} .foot .cap{display:inline-flex;vertical-align:middle;margin:0 2px;}"
            + ".search{padding:12px 20px 4px;} .search input{width:100%;border:1px solid " + capLine + ";border-radius:8px;padding:8px 11px;font-size:12.5px;background:" + bg + ";color:" + ink + ";}"
            + ".count{font-size:11.5px;color:" + muted + ";padding:4px 22px 6px;}"
            + ".list{max-height:360px;overflow-y:auto;padding:2px 10px 10px;}"
            + ".erow{display:flex;align-items:center;gap:12px;padding:8px 12px;border-radius:8px;cursor:pointer;} .erow:hover{background:" + rowHover + ";}"
            + ".erow .ename{font-size:13px;flex:1 1 auto;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;} .erow.off .ename{color:" + muted + ";}"
            + ".catsel{flex:0 0 auto;font-size:11.5px;font-weight:600;color:" + accInk + ";background:" + accSoft + ";border:1px solid " + capLine + ";border-radius:999px;padding:3px 9px;}"
            ;
    }

    /** HTML text escape. */
    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

}
