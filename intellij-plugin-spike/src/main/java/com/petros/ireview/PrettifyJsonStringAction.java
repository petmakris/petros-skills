package com.petros.ireview;

import com.intellij.codeInsight.hint.HintManager;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * Reformats the JSON inside the Java text block under the caret, preserving
 * {@code {{placeholder}}} tokens. Enabled only when the caret sits in a text block
 * ({@code """ ... """}); single-line string literals can't hold multi-line JSON.
 */
public class PrettifyJsonStringAction extends AnAction {

    private static final Logger LOG = Logger.getInstance(PrettifyJsonStringAction.class);

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        boolean enabled;
        try {
            PsiFile file = e.getData(CommonDataKeys.PSI_FILE);
            Editor editor = e.getData(CommonDataKeys.EDITOR);
            enabled = file != null && editor != null && findTextBlockAtCaret(file, editor) != null;
        } catch (Throwable t) {
            LOG.warn("[PrettifyJSON] update() threw", t);
            enabled = false;
        }
        e.getPresentation().setEnabledAndVisible(enabled);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        PsiFile file = e.getData(CommonDataKeys.PSI_FILE);
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        if (project == null || file == null || editor == null) {
            return;
        }
        PsiLiteralExpression literal = findTextBlockAtCaret(file, editor);
        if (literal == null) {
            HintManager.getInstance().showInformationHint(editor, "Put the caret inside a \"\"\" text block");
            return;
        }
        if (!(literal.getValue() instanceof String content)) {
            HintManager.getInstance().showInformationHint(editor, "Could not read text block content");
            return;
        }
        Optional<String> formatted = JsonStringPrettifier.prettify(content);
        if (formatted.isEmpty()) {
            HintManager.getInstance().showInformationHint(editor, "Not valid JSON — nothing to format");
            return;
        }

        String baseIndent = closingDelimiterIndent(literal.getText());
        String replacement = buildTextBlock(formatted.get(), baseIndent);
        TextRange range = literal.getTextRange();
        Document document = editor.getDocument();
        WriteCommandAction.runWriteCommandAction(project, "Prettify JSON String", null,
            () -> document.replaceString(range.getStartOffset(), range.getEndOffset(), replacement),
            file);
    }

    /** The text block literal at the caret, or null if the caret isn't inside one. */
    private static PsiLiteralExpression findTextBlockAtCaret(PsiFile file, Editor editor) {
        int offset = editor.getCaretModel().getOffset();
        PsiElement element = file.findElementAt(offset);
        if (element == null && offset > 0) {
            element = file.findElementAt(offset - 1);
        }
        PsiLiteralExpression literal = PsiTreeUtil.getParentOfType(element, PsiLiteralExpression.class, false);
        if (literal == null) {
            return null;
        }
        return literal.getText().startsWith("\"\"\"") ? literal : null;
    }

    /** Leading whitespace of the text block's closing-delimiter line — the indent to re-apply. */
    private static String closingDelimiterIndent(String rawLiteralText) {
        int lastNewline = rawLiteralText.lastIndexOf('\n');
        if (lastNewline < 0) {
            return "";
        }
        String lastLine = rawLiteralText.substring(lastNewline + 1);
        int i = 0;
        while (i < lastLine.length() && (lastLine.charAt(i) == ' ' || lastLine.charAt(i) == '\t')) {
            i++;
        }
        return lastLine.substring(0, i);
    }

    private static String buildTextBlock(String json, String baseIndent) {
        StringBuilder sb = new StringBuilder("\"\"\"\n");
        for (String line : json.split("\n", -1)) {
            sb.append(baseIndent).append(line).append('\n');
        }
        sb.append(baseIndent).append("\"\"\"");
        return sb.toString();
    }
}
