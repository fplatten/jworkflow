package org.jworkflow.workbench;

import java.util.ArrayList;
import java.util.List;

/** Bounded line-based unified diff for whole-change-set review; it never omits changed lines. */
public final class TextDiff {
    private static final int MAX_LCS_CELLS = 4_000_000;
    private static final int CONTEXT = 3;

    private TextDiff() {}

    /** Unified diff of {@code before} to {@code after}; null means the file is absent on that side. */
    public static String unified(String path, String before, String after) {
        List<String> a = lines(before), b = lines(after);
        List<Edit> edits = edits(a, b);
        StringBuilder out = new StringBuilder();
        out.append("--- ").append(before == null ? "/dev/null" : "a/" + path).append('\n');
        out.append("+++ ").append(after == null ? "/dev/null" : "b/" + path).append('\n');
        int index = 0;
        while (index < edits.size()) {
            while (index < edits.size() && edits.get(index).kind == ' ') index++;
            if (index == edits.size()) break;
            int start = Math.max(0, index - CONTEXT), end = index;
            int quiet = 0;
            while (end < edits.size()) {
                if (edits.get(end).kind == ' ') { if (++quiet > CONTEXT * 2) break; } else quiet = 0;
                end++;
            }
            end = Math.min(edits.size(), end - Math.max(0, quiet - CONTEXT));
            int oldStart = 1, newStart = 1;
            for (int i = 0; i < start; i++) { if (edits.get(i).kind != '+') oldStart++; if (edits.get(i).kind != '-') newStart++; }
            int oldCount = 0, newCount = 0;
            for (int i = start; i < end; i++) { if (edits.get(i).kind != '+') oldCount++; if (edits.get(i).kind != '-') newCount++; }
            out.append("@@ -").append(oldCount == 0 ? oldStart - 1 : oldStart).append(',').append(oldCount)
                    .append(" +").append(newCount == 0 ? newStart - 1 : newStart).append(',').append(newCount).append(" @@\n");
            for (int i = start; i < end; i++) out.append(edits.get(i).kind).append(edits.get(i).line).append('\n');
            index = end;
        }
        return out.toString();
    }

    private static List<String> lines(String text) {
        if (text == null || text.isEmpty()) return List.of();
        String normalized = text.replace("\r\n", "\n");
        List<String> parts = new ArrayList<>(List.of(normalized.split("\n", -1)));
        if (normalized.endsWith("\n")) parts.remove(parts.size() - 1);
        return parts;
    }

    private static List<Edit> edits(List<String> a, List<String> b) {
        int prefix = 0;
        while (prefix < a.size() && prefix < b.size() && a.get(prefix).equals(b.get(prefix))) prefix++;
        int suffix = 0;
        while (suffix < a.size() - prefix && suffix < b.size() - prefix && a.get(a.size() - 1 - suffix).equals(b.get(b.size() - 1 - suffix))) suffix++;
        List<String> x = a.subList(prefix, a.size() - suffix), y = b.subList(prefix, b.size() - suffix);
        List<Edit> result = new ArrayList<>();
        for (int i = 0; i < prefix; i++) result.add(new Edit(' ', a.get(i)));
        if ((long) x.size() * y.size() > MAX_LCS_CELLS) {
            // Too large for an exact LCS: show a complete replacement rather than a partial diff.
            x.forEach(line -> result.add(new Edit('-', line)));
            y.forEach(line -> result.add(new Edit('+', line)));
        } else {
            int[][] lcs = new int[x.size() + 1][y.size() + 1];
            for (int i = x.size() - 1; i >= 0; i--)
                for (int j = y.size() - 1; j >= 0; j--)
                    lcs[i][j] = x.get(i).equals(y.get(j)) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            int i = 0, j = 0;
            while (i < x.size() && j < y.size()) {
                if (x.get(i).equals(y.get(j))) { result.add(new Edit(' ', x.get(i))); i++; j++; }
                else if (lcs[i + 1][j] >= lcs[i][j + 1]) result.add(new Edit('-', x.get(i++)));
                else result.add(new Edit('+', y.get(j++)));
            }
            while (i < x.size()) result.add(new Edit('-', x.get(i++)));
            while (j < y.size()) result.add(new Edit('+', y.get(j++)));
        }
        for (int i = a.size() - suffix; i < a.size(); i++) result.add(new Edit(' ', a.get(i)));
        return result;
    }

    private record Edit(char kind, String line) {}
}
