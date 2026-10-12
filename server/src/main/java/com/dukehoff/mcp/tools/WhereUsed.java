/*
 * Copyright 2026 Dukehoff GmbH
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dukehoff.mcp.tools;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import org.netbeans.api.queries.FileEncodingQuery;
import com.dukehoff.mcp.Tool.Result;
import com.dukehoff.mcp.Workspace;
import org.netbeans.modules.refactoring.api.Problem;
import org.netbeans.modules.refactoring.api.RefactoringElement;
import org.netbeans.modules.refactoring.api.RefactoringSession;
import org.netbeans.modules.refactoring.api.WhereUsedQuery;
import org.openide.filesystems.FileObject;
import org.openide.util.lookup.Lookups;

/**
 * Runs a {@link WhereUsedQuery} over the workspace sources and lists the
 * found locations grouped by file as {@code line: source line}.
 */
final class WhereUsed {

    private WhereUsed() {
    }

    /**
     * Creates a query for the resolved symbol, scoped to the given source roots.
     */
    static WhereUsedQuery query(SymbolResolver.Resolved symbol, List<FileObject> roots) {
        WhereUsedQuery query = new WhereUsedQuery(Lookups.singleton(symbol.handle()));
        // No custom scope: NetBeans' default searches every project that depends on the symbol's
        // project. A scope of all source roots looked broader but found only modules seeing the
        // declaring module as sources; on GlassFish (modules depend on siblings' jars) 3 of 73 files.
        // The roots parameter stays for callers that compute more than the query.
        return query;
    }

    /**
     * Runs the query and formats its results.
     *
     * @param noun what one result is, e.g. "usage"; pluralized by appending "s"
     */
    static Result run(Workspace workspace, WhereUsedQuery query, String signature, String noun, int limit) throws IOException {
        Found found = find(workspace, query, signature, noun, limit, re -> true);
        return found.error() ? Result.error(found.text()) : Result.ok(found.text());
    }

    /**
     * Result of a search.
     *
     * @param total number of found locations
     * @param text the listing, or the error message if {@code error}
     */
    record Found(int total, String text, boolean error) {
    }

    /**
     * Runs the query and formats the results that pass {@code filter}.
     */
    static Found find(Workspace workspace, WhereUsedQuery query, String signature, String noun, int limit,
            Predicate<RefactoringElement> filter) throws IOException {
        RefactoringSession session = RefactoringSession.create("MCP " + noun + "s");
        try {
            Problem p = firstFatal(query.preCheck());
            if (p == null) {
                p = firstFatal(query.checkParameters());
            }
            if (p == null) {
                p = firstFatal(query.prepare(session));
            }
            if (p != null) {
                return new Found(0, "Search for " + noun + "s of " + signature + " failed: " + p.getMessage(), true);
            }
            return format(workspace, session, signature, noun, limit, filter);
        } finally {
            session.finished();
        }
    }

    private static Problem firstFatal(Problem p) {
        for (; p != null; p = p.getNext()) {
            if (p.isFatal()) {
                return p;
            }
        }
        return null;
    }

    /** A result position: file, 0-based line, offset. */
    record Hit(FileObject file, int line, int offset) {
    }

    private static Found format(Workspace workspace, RefactoringSession session, String signature, String noun, int limit,
            Predicate<RefactoringElement> filter) throws IOException {
        List<Hit> hits = new ArrayList<>();
        for (RefactoringElement re : session.getRefactoringElements()) {
            FileObject file = re.getParentFile();
            if (file == null || !file.isData() || re.getPosition() == null || !filter.test(re)) {
                continue;
            }
            hits.add(new Hit(file, re.getPosition().getBegin().getLine(), re.getPosition().getBegin().getOffset()));
        }
        return render(workspace, hits, signature, noun, limit);
    }

    /**
     * Lists the hits grouped by file as {@code line: source line}, counting each position once.
     */
    static Found render(Workspace workspace, List<Hit> hits, String signature, String noun, int limit) {
        // file -> line -> source line and the offsets found on it; TreeMaps keep output stable and sorted
        Map<String, TreeMap<Integer, Line>> byFile = new TreeMap<>();
        Map<FileObject, List<String>> lineCache = new HashMap<>();
        int total = 0;
        int lineCount = 0;
        for (Hit hit : hits) {
            int line = hit.line();
            List<String> lines = lineCache.computeIfAbsent(hit.file(), WhereUsed::readLines);
            TreeMap<Integer, Line> fileLines = byFile.computeIfAbsent(workspace.displayPath(hit.file()), k -> new TreeMap<>());
            Line l = fileLines.get(line + 1);
            if (l == null) {
                l = new Line(line >= 0 && line < lines.size() ? lines.get(line).strip() : "", new TreeSet<>());
                fileLines.put(line + 1, l);
                lineCount++;
            }
            if (l.offsets().add(hit.offset())) {
                total++;
            }
        }
        if (total == 0) {
            return new Found(0, "No " + noun + "s of " + signature + " in the workspace.", false);
        }
        StringBuilder sb = new StringBuilder();
        sb.append(total).append(' ').append(noun).append(total == 1 ? "" : "s").append(" of ").append(signature)
                .append(" in ").append(byFile.size()).append(byFile.size() == 1 ? " file" : " files");
        if (lineCount != total) {
            // agents compare the count with the listed lines; say why they differ
            sb.append(", on ").append(lineCount).append(" lines");
        }
        sb.append('\n');
        int shown = 0;
        outer:
        for (Map.Entry<String, TreeMap<Integer, Line>> f : byFile.entrySet()) {
            sb.append('\n').append(f.getKey()).append('\n');
            for (Map.Entry<Integer, Line> l : f.getValue().entrySet()) {
                if (shown++ == limit) {
                    sb.append("\n... ").append(lineCount - limit).append(" more lines not shown, raise 'limit' to see them\n");
                    break outer;
                }
                int n = l.getValue().offsets().size();
                sb.append("  ").append(l.getKey()).append(n > 1 ? " (" + n + " " + noun + "s)" : "").append(": ")
                        .append(l.getValue().text()).append('\n');
            }
        }
        return new Found(total, sb.toString(), false);
    }

    /** A listed source line and the offsets of the results on it. */
    private record Line(String text, Set<Integer> offsets) {
    }

    static List<String> readLines(FileObject fo) {
        try {
            Charset cs = FileEncodingQuery.getEncoding(fo);
            return fo.asLines(cs.name());
        } catch (IOException ex) {
            return new ArrayList<>();
        }
    }
}
