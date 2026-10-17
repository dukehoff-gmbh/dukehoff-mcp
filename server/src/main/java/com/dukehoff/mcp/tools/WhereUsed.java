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
        return find(workspace, query, signature, noun, limit, filter, false);
    }

    /**
     * Runs the query and formats the results that pass {@code filter}, grouped by file or, with
     * {@code byCaller}, by the member that contains them.
     */
    static Found find(Workspace workspace, WhereUsedQuery query, String signature, String noun, int limit,
            Predicate<RefactoringElement> filter, boolean byCaller) throws IOException {
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
            return format(workspace, session, signature, noun, limit, filter, byCaller);
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
            Predicate<RefactoringElement> filter, boolean byCaller) throws IOException {
        List<Hit> hits = new ArrayList<>();
        for (RefactoringElement re : session.getRefactoringElements()) {
            FileObject file = re.getParentFile();
            if (file == null || !file.isData() || re.getPosition() == null || !filter.test(re)) {
                continue;
            }
            hits.add(new Hit(file, re.getPosition().getBegin().getLine(), re.getPosition().getBegin().getOffset()));
        }
        return byCaller ? renderByCaller(workspace, hits, signature, noun, limit) : render(workspace, hits, signature, noun, limit);
    }

    /**
     * Lists the hits grouped by the member that contains them (method, constructor, field, initializer,
     * type declaration), each with its file and then {@code line: source line}: who uses the symbol,
     * not only where. Lambdas and anonymous or local classes count to the member around them.
     */
    static Found renderByCaller(Workspace workspace, List<Hit> hits, String signature, String noun, int limit) {
        // caller -> (file, lines); the file of a caller is the one its first hit is in
        Map<FileObject, List<Hit>> byFileObject = new java.util.LinkedHashMap<>();
        for (Hit hit : hits) {
            byFileObject.computeIfAbsent(hit.file(), k -> new ArrayList<>()).add(hit);
        }
        Map<String, Caller> callers = new TreeMap<>();
        int total = 0;
        for (Map.Entry<FileObject, List<Hit>> e : byFileObject.entrySet()) {
            FileObject file = e.getKey();
            List<String> lines = readLines(file);
            Map<Integer, String> labels = callerLabels(file, e.getValue());
            for (Hit hit : e.getValue()) {
                String label = labels.getOrDefault(hit.offset(), "(not resolved)");
                Caller c = callers.computeIfAbsent(label + "\u0000" + workspace.displayPath(file),
                        k -> new Caller(label, workspace.displayPath(file), new TreeMap<>()));
                Line l = c.lines().computeIfAbsent(hit.line() + 1, k -> new Line(
                        hit.line() >= 0 && hit.line() < lines.size() ? lines.get(hit.line()).strip() : "", new TreeSet<>()));
                if (l.offsets().add(hit.offset())) {
                    total++;
                }
            }
        }
        if (total == 0) {
            return new Found(0, "No " + noun + "s of " + signature + " in the workspace.", false);
        }
        long files = callers.values().stream().map(Caller::file).distinct().count();
        StringBuilder sb = new StringBuilder();
        sb.append(total).append(' ').append(noun).append(total == 1 ? "" : "s").append(" of ").append(signature)
                .append(" in ").append(callers.size()).append(callers.size() == 1 ? " member" : " members").append(" (")
                .append(files).append(files == 1 ? " file" : " files").append(")\n");
        int shown = 0;
        int lineCount = callers.values().stream().mapToInt(c -> c.lines().size()).sum();
        outer:
        for (Caller c : callers.values()) {
            sb.append('\n').append(c.label()).append("   ").append(c.file()).append('\n');
            for (Map.Entry<Integer, Line> l : c.lines().entrySet()) {
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

    /** A member using the symbol, its file and the lines. */
    private record Caller(String label, String file, TreeMap<Integer, Line> lines) {
    }

    /** The label of the member around each hit's offset, from one parse of the file. */
    private static Map<Integer, String> callerLabels(FileObject file, List<Hit> hits) {
        Map<Integer, String> labels = new HashMap<>();
        org.netbeans.api.java.source.JavaSource js = org.netbeans.api.java.source.JavaSource.forFileObject(file);
        if (js == null) {
            return labels;
        }
        try {
            js.runUserActionTask(cc -> {
                cc.toPhase(org.netbeans.api.java.source.JavaSource.Phase.RESOLVED);
                for (Hit hit : hits) {
                    labels.put(hit.offset(), callerLabel(cc, cc.getTreeUtilities().pathFor(hit.offset())));
                }
            }, true);
        } catch (IOException ex) {
            // the hits stay, labelled as not resolved
        }
        return labels;
    }

    /** The innermost named member around a path: method, constructor, field, initializer or type. */
    static String callerLabel(org.netbeans.api.java.source.CompilationInfo info, com.sun.source.util.TreePath path) {
        for (com.sun.source.util.TreePath p = path; p != null; p = p.getParentPath()) {
            com.sun.source.tree.Tree t = p.getLeaf();
            com.sun.source.tree.Tree parent = p.getParentPath() == null ? null : p.getParentPath().getLeaf();
            javax.lang.model.element.Element e = null;
            switch (t.getKind()) {
                case METHOD -> e = info.getTrees().getElement(p);
                case VARIABLE -> {
                    if (parent != null && com.sun.source.tree.Tree.Kind.CLASS.asInterface().isInstance(parent)) {
                        e = info.getTrees().getElement(p);
                    }
                }
                case BLOCK -> {
                    if (parent != null && com.sun.source.tree.Tree.Kind.CLASS.asInterface().isInstance(parent)) {
                        javax.lang.model.element.Element owner = info.getTrees().getElement(p.getParentPath());
                        if (owner instanceof javax.lang.model.element.TypeElement te && named(te)) {
                            boolean isStatic = ((com.sun.source.tree.BlockTree) t).isStatic();
                            return te.getQualifiedName() + (isStatic ? " (static initializer)" : " (initializer)");
                        }
                    }
                }
                case CLASS, INTERFACE, ENUM, RECORD, ANNOTATION_TYPE -> {
                    javax.lang.model.element.Element type = info.getTrees().getElement(p);
                    if (type instanceof javax.lang.model.element.TypeElement te && named(te)) {
                        return te.getQualifiedName() + " (declaration)";
                    }
                }
                default -> {
                }
            }
            // members of anonymous and local classes count to the member around them
            if (e != null && e.getEnclosingElement() instanceof javax.lang.model.element.TypeElement owner && named(owner)) {
                return SymbolResolver.signature(e, info);
            }
        }
        return "(imports)";
    }

    private static boolean named(javax.lang.model.element.TypeElement type) {
        return type.getNestingKind() != javax.lang.model.element.NestingKind.ANONYMOUS
                && type.getNestingKind() != javax.lang.model.element.NestingKind.LOCAL;
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
