/*
 * Copyright 2026 Dukehoff GmbH
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dukehoff.mcp.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.openide.filesystems.FileObject;

/**
 * String literals that contain a name, after a rename. A rename changes references, not text, like the
 * IDE's own Refactor > Rename: a name in a string (reflection such as {@code getMethod("name")} or
 * {@code Class.forName}, names used as data in tests, messages) keeps the old name. Agents get the
 * places instead of having to grep the whole project, and see that the compile check cannot cover them.
 */
final class StringMentions {

    /** At most this many places are listed; more are counted. */
    static final int LIMIT = 20;
    private static final int LINE_CHARS = 160;

    private StringMentions() {
    }

    /** A line with a string literal containing the name: its number (1-based) and the source line, trimmed. */
    record Hit(int line, String source) {
    }

    /**
     * The string literals of a Java source that contain the name as a whole word (not inside a longer
     * identifier: "com.acme.Foo" and "Foo#bar" count for Foo, "FooBar" and "test_Foo" do not), one hit per
     * line. Comments and character literals are skipped; text blocks count.
     */
    static List<Hit> scan(String text, String name) {
        List<Hit> hits = new ArrayList<>();
        if (name.isEmpty() || !text.contains(name)) {
            return hits;
        }
        int n = text.length();
        int i = 0;
        while (i < n) {
            char c = text.charAt(i);
            if (c == '/' && i + 1 < n && text.charAt(i + 1) == '/') {
                i = indexOrEnd(text, "\n", i + 2);
            } else if (c == '/' && i + 1 < n && text.charAt(i + 1) == '*') {
                int end = text.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
            } else if (c == '\'') {
                i = literalEnd(text, i + 1, "'");
            } else if (text.startsWith("\"\"\"", i)) {
                int end = literalEnd(text, i + 3, "\"\"\"");
                check(text, i + 3, Math.max(i + 3, end - 3), name, i, hits);
                i = end;
            } else if (c == '"') {
                int end = literalEnd(text, i + 1, "\"");
                check(text, i + 1, Math.max(i + 1, end - 1), name, i, hits);
                i = end;
            } else {
                i++;
            }
        }
        return hits;
    }

    private static int indexOrEnd(String text, String s, int from) {
        int at = text.indexOf(s, from);
        return at < 0 ? text.length() : at;
    }

    /** The offset after the closing delimiter of a literal starting at from; escapes skipped; a line end ends "x" and 'x'. */
    private static int literalEnd(String text, int from, String close) {
        int n = text.length();
        int i = from;
        while (i < n) {
            char c = text.charAt(i);
            if (c == '\\') {
                i += 2;
            } else if (text.startsWith(close, i)) {
                return i + close.length();
            } else if (c == '\n' && close.length() == 1) {
                return i; // unterminated: do not run into the next line
            } else {
                i++;
            }
        }
        return n;
    }

    private static void check(String text, int from, int to, String name, int literalStart, List<Hit> hits) {
        int at = text.indexOf(name, from);
        while (at >= 0 && at + name.length() <= to) {
            boolean before = at == 0 || !Character.isJavaIdentifierPart(text.charAt(at - 1));
            int after = at + name.length();
            if (before && (after >= text.length() || !Character.isJavaIdentifierPart(text.charAt(after)))) {
                int line = line(text, literalStart);
                // one hit per line, also with several such literals on it
                if (hits.isEmpty() || hits.get(hits.size() - 1).line() != line) {
                    hits.add(new Hit(line, sourceLine(text, literalStart)));
                }
                return;
            }
            at = text.indexOf(name, at + 1);
        }
    }

    private static int line(String text, int offset) {
        int line = 1;
        for (int i = 0; i < offset; i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private static String sourceLine(String text, int offset) {
        int start = text.lastIndexOf('\n', offset - 1) + 1;
        int end = indexOrEnd(text, "\n", offset);
        String s = text.substring(start, end).strip();
        return s.length() > LINE_CHARS ? s.substring(0, LINE_CHARS) + "..." : s;
    }

    /** The hits in the Java files under the source roots, by file (display path), in file order. */
    static Map<String, List<Hit>> find(List<FileObject> roots, String name, Function<FileObject, String> display) {
        Map<String, List<Hit>> found = new LinkedHashMap<>();
        for (FileObject root : roots) {
            Enumeration<? extends FileObject> files = root.getChildren(true);
            while (files.hasMoreElements()) {
                FileObject fo = files.nextElement();
                if (!fo.isData() || !"java".equals(fo.getExt())) {
                    continue;
                }
                List<Hit> hits;
                try {
                    hits = scan(fo.asText(StandardCharsets.UTF_8.name()), name);
                } catch (IOException ex) {
                    continue;
                }
                if (!hits.isEmpty()) {
                    found.computeIfAbsent(display.apply(fo), k -> new ArrayList<>()).addAll(hits);
                }
            }
        }
        return found;
    }

    /** The report appended to a rename's result; empty if no string contains the name. */
    static String report(Map<String, List<Hit>> found, String name) {
        int total = found.values().stream().mapToInt(List::size).sum();
        if (total == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\nString literals that still contain \"").append(name).append("\": ")
                .append(total).append(total == 1 ? " place" : " places").append(" in ").append(found.size())
                .append(found.size() == 1 ? " file" : " files").append(". A rename changes code, not text, like the IDE's "
                + "Refactor > Rename; check these for reflection (getMethod, Class.forName) or names used as data. Some may "
                + "mean another member of that name.\n");
        int listed = 0;
        for (Map.Entry<String, List<Hit>> e : found.entrySet()) {
            if (listed >= LIMIT) {
                break;
            }
            sb.append(e.getKey()).append('\n');
            for (Hit h : e.getValue()) {
                if (listed >= LIMIT) {
                    break;
                }
                sb.append("  ").append(h.line()).append(": ").append(h.source()).append('\n');
                listed++;
            }
        }
        if (listed < total) {
            sb.append("... and ").append(total - listed).append(" more (search the old name to see all)\n");
        }
        return sb.toString();
    }
}
