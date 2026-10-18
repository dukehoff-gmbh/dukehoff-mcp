/*
 * Copyright 2026 Dukehoff GmbH
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dukehoff.mcp.tools;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import org.netbeans.api.actions.Savable;
import org.netbeans.api.java.source.ModificationResult;
import org.netbeans.api.java.source.WorkingCopy;
import org.netbeans.api.java.source.matching.Occurrence;
import org.netbeans.api.queries.FileEncodingQuery;
import com.dukehoff.mcp.ChangeLog;
import com.dukehoff.mcp.Json;
import com.dukehoff.mcp.Tool;
import com.dukehoff.mcp.Workspace;
import org.netbeans.spi.java.hints.support.TransformationSupport;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;

/**
 * Structural search and replace with NetBeans declarative hint rules
 * (Jackpot), through the public {@link TransformationSupport}.
 */
public final class ApplyRuleTool implements Tool {

    private static final int DEFAULT_LIMIT = 200;
    private static final Pattern STATEMENT_VARIABLE = Pattern.compile("(\\$[A-Za-z_][\\w$]*)\\s*;");

    private final Workspace workspace;

    public ApplyRuleTool(Workspace workspace) {
        this.workspace = workspace;
    }

    @Override
    public String name() {
        return "apply_rule";
    }

    @Override
    public String description() {
        return "Structural search and replace over all Java code of the workspace, matching syntax trees and "
                + "types instead of text. With a rewrite ('=>') every match is transformed and the changes are "
                + "written to disk (dry_run: only show the diff); without one, the matches are listed. Use it "
                + "for API migrations and mechanical changes across many files instead of sed or editing file "
                + "by file.\n"
                + "Syntax: <pattern> [:: <conditions>] [=> <replacement>] ;;\n"
                + "- $name matches one expression/statement, $name$ any number of them (e.g. arguments), "
                + "$_ is an unnamed wildcard.\n"
                + "- Conditions constrain types: $x instanceof java.util.Collection (combine with &&). For calls of "
                + "overloaded methods give every argument variable a type (java.lang.Object for any reference, long "
                + "etc. for primitives): untyped ones can miss calls, e.g. statically imported ones.\n"
                + "- Patterns may span statements ($T $r = $init; try { $body$; } finally { $r.close(); }); a "
                + "variable used twice must match the same expression twice; !referencedIn($v, $body$) excludes "
                + "places where $v is used in $body$.\n"
                + "- Write types fully qualified; imports are added and simplified.\n"
                + "- Several rules in one call, each ending with ;;\n"
                + "Examples:\n"
                + "  $s.length() == 0 :: $s instanceof java.lang.String => $s.isEmpty() ;;\n"
                + "  new java.lang.Integer($v) => java.lang.Integer.valueOf($v) ;;\n"
                + "  $c.size() == 0 :: $c instanceof java.util.Collection => $c.isEmpty() ;;\n"
                + "  java.util.Objects.equals($a, $b) ;;   (search only)\n"
                + "Packs, tested rule sets, instead of a rule: " + String.join("; ", PACKS.entrySet().stream()
                        .map(e -> e.getKey() + " (" + e.getValue() + ")").toList())
                + ". Try a pack with dry_run first; the result lists what is left for hand edits.\n"
                + "Search mode checks the pattern and type conditions only. Output: matches as 'line: source "
                + "line' grouped by file, or a unified diff of the changes.";
    }

    @Override
    public List<String> searchTerms() {
        return List.of("structural search", "search and replace", "rewrite pattern", "API migration");
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Json.obj(
                "type", "object",
                "properties", Json.obj(
                        "rule", Json.obj("type", "string", "description", "One or more rules, each ending with ;;"),
                        "pack", Json.obj("type", "string", "enum", List.copyOf(PACKS.keySet()),
                                "description", "A tested rule set to apply instead of 'rule'"),
                        "paths", Json.obj("type", "array", "items", Json.obj("type", "string"),
                                "description", "Limit to these folders or files (relative to the workspace); default: everything"),
                        "dry_run", Json.obj("type", "boolean",
                                "description", "Rewrite rules: only return the diff, write nothing. Default false."),
                        "limit", Json.obj("type", "integer",
                                "description", "Search: maximum matches listed, default " + DEFAULT_LIMIT),
                        RefactoringRunner.ALLOW_OUTSIDE, RefactoringRunner.ALLOW_OUTSIDE_SCHEMA,
                        "max_diff_lines", Json.obj("type", "integer",
                                "description", "Rewrite: maximum diff lines returned, default "
                                + RefactoringRunner.DEFAULT_MAX_DIFF_LINES)));
    }

    /** The rule packs (resources packs/<name>.rules) and what each covers. */
    static final Map<String, String> PACKS = new LinkedHashMap<>();

    static {
        PACKS.put("junit5", "JUnit 4 to 5: assertions with the message moved last, assertThat to Hamcrest, annotations");
        PACKS.put("modernize", "try-with-resources, for-each, getOrDefault, isEmpty, valueOf instead of new Integer(...) "
                + "(which shares cached instances: not for code relying on object identity)");
        PACKS.put("jakarta", "javax to jakarta for Servlet, JSP, EL, Persistence, Validation, Annotation, Inject, CDI, "
                + "EJB, JAX-RS, Transaction, JAXB, Activation, Mail, JSON-P, JSON-B, JMS, Faces, WebSocket; the JDK's own "
                + "javax packages and JCache (javax.cache) stay");
    }

    /**
     * A pack's rules and its notes for the result: lines starting with "#!" are notes, other lines
     * starting with "#" comments.
     */
    record Pack(String rules, String notes) {

        static Pack load(String name) throws IOException {
            if (!PACKS.containsKey(name)) {
                return null;
            }
            try (java.io.InputStream in = ApplyRuleTool.class.getResourceAsStream("packs/" + name + ".rules")) {
                if (in == null) {
                    return null;
                }
                StringBuilder rules = new StringBuilder();
                StringBuilder notes = new StringBuilder();
                for (String line : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split("\n")) {
                    if (line.startsWith("#!")) {
                        notes.append(line.substring(2).strip()).append('\n');
                    } else if (!line.startsWith("#") && !line.isBlank()) {
                        rules.append(line).append('\n');
                    }
                }
                return new Pack(rules.toString().strip(), notes.toString());
            }
        }
    }

    @Override
    public Result call(Map<?, ?> arguments) throws Exception {
        String packName = Json.string(arguments, "pack");
        if (packName == null) {
            return call(arguments, Json.string(arguments, "rule"));
        }
        if (Json.string(arguments, "rule") != null) {
            return Result.error("Give either 'rule' or 'pack', not both.");
        }
        Pack pack = Pack.load(packName);
        if (pack == null) {
            return Result.error("Unknown pack '" + packName + "'. Packs: " + String.join(", ", PACKS.keySet()));
        }
        Result r = call(arguments, pack.rules());
        return r.error() || pack.notes().isEmpty() ? r : Result.ok(r.text() + "\n" + pack.notes());
    }

    private Result call(Map<?, ?> arguments, String rule) throws Exception {
        if (rule == null) {
            return Result.error("'rule' (or 'pack') is required");
        }
        // without ";;" the text is taken as a bare pattern and conditions after "::" are not understood
        if (!rule.endsWith(";;")) {
            rule = rule + " ;;";
        }
        // Jackpot understands more than Java ($mods$, class members, annotations), so javac's view of the
        // rule only explains a rule that matches nothing or that Jackpot cannot read either
        // javac takes a while per rule (hundreds in a pack): only when the message is needed
        String rules = rule;
        java.util.function.Supplier<String> invalid = () -> checkSyntax(rules);
        List<FileObject> roots;
        try {
            roots = workspace.awaitSourceRoots();
        } catch (Workspace.NotReadyException ex) {
            return Result.error(ex.getMessage());
        }
        List<FileObject> scope = new ArrayList<>();
        if (arguments.get("paths") instanceof List<?> paths) {
            for (Object p : paths) {
                FileObject fo = p instanceof String s ? workspace.findFile(s) : null;
                if (fo == null) {
                    return Result.error("No such file or folder: " + p);
                }
                scope.add(fo);
            }
        }
        try {
            if (!rule.contains("=>")) {
                return search(rule, scope, Json.integer(arguments, "limit", DEFAULT_LIMIT), invalid);
            }
            RefactoringRunner.WRITES.lock();
            try {
                return rewrite(rule, scope, roots, Json.bool(arguments, "dry_run", false),
                        Json.integer(arguments, "max_diff_lines", RefactoringRunner.DEFAULT_MAX_DIFF_LINES),
                        Json.strings(arguments, RefactoringRunner.ALLOW_OUTSIDE), invalid);
            } finally {
                RefactoringRunner.WRITES.unlock();
            }
        } catch (RuntimeException ex) {
            String message = invalid.get();
            if (message != null) {
                return Result.error(message);
            }
            throw ex;
        }
    }

    /** No matches: why, if javac cannot read the rule, else what is commonly missed. */
    private static Result noMatches(String what, java.util.function.Supplier<String> invalid) {
        String message = invalid.get();
        if (message != null) {
            return Result.error(what + " " + message);
        }
        return Result.ok(what + " If you expected some, check the pattern: types fully qualified, $variables for "
                + "the parts that vary, each rule ending with ;;. Calls of overloaded methods (also statically "
                + "imported ones) match only when every argument variable has a type: "
                + "$x instanceof java.lang.Object for any reference, a primitive type such as long for primitives.");
    }

    private static boolean inScope(List<FileObject> scope, FileObject fo) {
        if (scope.isEmpty()) {
            return true;
        }
        for (FileObject s : scope) {
            if (s.equals(fo) || FileUtil.isParentOf(s, fo)) {
                return true;
            }
        }
        return false;
    }

    private Result search(String rule, List<FileObject> scope, int limit, java.util.function.Supplier<String> invalid) {
        // file -> line -> source line (a line with several matches is listed once)
        Map<String, TreeMap<Integer, String>> byFile = Collections.synchronizedMap(new TreeMap<>());
        TransformationSupport.create(rule, (WorkingCopy wc, Occurrence occurrence) -> {
            FileObject fo = wc.getFileObject();
            if (!inScope(scope, fo)) {
                return;
            }
            int[] span = SymbolResolver.span(wc, occurrence.getOccurrenceRoot().getLeaf());
            if (span == null) {
                return;
            }
            CompilationUnitTree cu = wc.getCompilationUnit();
            int line = (int) cu.getLineMap().getLineNumber(span[0]);
            String text = wc.getText();
            int start = (int) cu.getLineMap().getStartPosition(line);
            int end = text.indexOf('\n', start);
            String source = text.substring(start, end < 0 ? text.length() : end).strip();
            byFile.computeIfAbsent(workspace.displayPath(fo), k -> new TreeMap<>()).put(line, source);
        }).processAllProjects();

        int total = byFile.values().stream().mapToInt(Map::size).sum();
        if (total == 0) {
            return noMatches("No matches.", invalid);
        }
        StringBuilder sb = new StringBuilder();
        sb.append(total).append(total == 1 ? " matching line" : " matching lines").append(" in ")
                .append(byFile.size()).append(byFile.size() == 1 ? " file" : " files").append('\n');
        int shown = 0;
        outer:
        for (Map.Entry<String, TreeMap<Integer, String>> f : byFile.entrySet()) {
            sb.append('\n').append(f.getKey()).append('\n');
            for (Map.Entry<Integer, String> l : f.getValue().entrySet()) {
                if (shown++ == limit) {
                    sb.append("\n... ").append(total - limit).append(" more not shown, raise 'limit' to see them\n");
                    break outer;
                }
                sb.append("  ").append(l.getKey()).append(": ").append(l.getValue()).append('\n');
            }
        }
        return Result.ok(sb.toString());
    }

    private Result rewrite(String rule, List<FileObject> scope, List<FileObject> roots, boolean dryRun, int maxDiffLines,
            List<String> allowOutside, java.util.function.Supplier<String> invalid)
            throws IOException {
        Collection<? extends ModificationResult> results = TransformationSupport.create(rule).processAllProjects();
        Map<FileObject, String> before = new LinkedHashMap<>();
        Map<FileObject, String> planned = new LinkedHashMap<>();
        for (ModificationResult mr : results) {
            for (FileObject fo : mr.getModifiedFileObjects()) {
                if (!inScope(scope, fo)) {
                    for (ModificationResult.Difference d : mr.getDifferences(fo)) {
                        d.exclude(true);
                    }
                    continue;
                }
                String old = read(fo);
                String changed = mr.getResultingSource(fo);
                if (!old.equals(changed)) {
                    before.put(fo, old);
                    planned.put(fo, changed);
                }
            }
        }
        if (planned.isEmpty()) {
            return noMatches("No matches, nothing to change.", invalid);
        }
        Map<FileObject, String> after = planned;
        if (!dryRun) {
            List<File> targets = before.keySet().stream().map(FileUtil::toFile).filter(f -> f != null).toList();
            String refusal = RefactoringRunner.outsideRefusal(workspace, targets, allowOutside);
            if (refusal != null) {
                return Result.error("Cannot apply the rule: " + refusal);
            }
            for (ModificationResult mr : results) {
                mr.commit();
            }
            // commit() changes open documents instead of files; save them
            for (Savable savable : Savable.REGISTRY.lookupAll(Savable.class)) {
                savable.save();
            }
            after = new LinkedHashMap<>();
            Set<URL> urls = new LinkedHashSet<>();
            for (FileObject fo : before.keySet()) {
                after.put(fo, read(fo));
                urls.add(fo.toURL());
            }
            RefactoringRunner.reindex(roots, urls);
            List<ChangeLog.FileChange> files = new ArrayList<>();
            for (Map.Entry<FileObject, String> e : before.entrySet()) {
                File file = FileUtil.toFile(e.getKey());
                files.add(new ChangeLog.FileChange(file, file, e.getValue(), after.get(e.getKey())));
            }
            ChangeLog.record("apply_rule: " + files.size() + (files.size() == 1 ? " file" : " files"), files);
        }

        StringBuilder sb = new StringBuilder();
        sb.append(dryRun ? "Dry run, nothing written: " : "Applied: ").append(before.size())
                .append(before.size() == 1 ? " file" : " files").append(dryRun ? " would change." : " changed and saved.")
                .append('\n').append('\n');
        int lines = 0;
        List<String> omitted = new ArrayList<>();
        for (Map.Entry<FileObject, String> e : before.entrySet()) {
            String path = workspace.displayPath(e.getKey());
            String diff = UnifiedDiff.diff(path, path, e.getValue(), after.get(e.getKey()));
            int n = diff.split("\n", -1).length - 1;
            if (lines + n > maxDiffLines && lines > 0) {
                omitted.add(path);
                continue;
            }
            sb.append(diff);
            lines += n;
        }
        if (!omitted.isEmpty()) {
            sb.append("\n... diff omitted for ").append(omitted.size()).append(omitted.size() == 1 ? " file" : " files")
                    .append(" (raise 'max_diff_lines' or use git diff): ").append(String.join(", ", omitted)).append('\n');
        }
        return Result.ok(sb.toString());
    }

    /**
     * Checks that the pattern and replacement of each rule parse as Java
     * (an expression or statements; $variables are valid identifiers). A rule
     * that does not parse would otherwise just find nothing.
     *
     * @return a message for the agent, or {@code null} if the rule looks fine
     */
    static String checkSyntax(String rules) {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        if (javac == null) {
            return null;
        }
        for (String rule : rules.split(";;")) {
            rule = rule.strip();
            if (rule.isEmpty()) {
                continue;
            }
            int arrow = rule.indexOf("=>");
            String left = arrow < 0 ? rule : rule.substring(0, arrow);
            int conditions = left.indexOf("::");
            List<String> parts = new ArrayList<>();
            parts.add((conditions < 0 ? left : left.substring(0, conditions)).strip());
            if (arrow >= 0) {
                String replacement = rule.substring(arrow + 2);
                int fixConditions = replacement.indexOf("::");
                parts.add((fixConditions < 0 ? replacement : replacement.substring(0, fixConditions)).strip());
            }
            for (String part : parts) {
                String error = parseError(javac, "class R { Object o = (" + part + "\n); }");
                // "$body;" stands for any statement, but a bare name is no Java statement
                String statements = STATEMENT_VARIABLE.matcher(part).replaceAll("$1();");
                if (error != null && parseError(javac, "class R { void m() {\n" + statements + "\n} }") != null) {
                    return "The rule does not parse as Java: '" + part + "': " + error
                            + "\nPatterns and replacements are Java expressions or statements with $variables.";
                }
            }
        }
        return null;
    }

    private static String parseError(JavaCompiler javac, String code) {
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        JavaFileObject source = new SimpleJavaFileObject(URI.create("string:///R.java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return code;
            }
        };
        try {
            ((JavacTask) javac.getTask(null, null, diagnostics, List.of("-proc:none"), null, List.of(source))).parse();
        } catch (IOException | RuntimeException ex) {
            return null;
        }
        for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
            if (d.getKind() == Diagnostic.Kind.ERROR) {
                // ROOT is javac's English bundle; ENGLISH would fall back to the default locale first
                return d.getMessage(Locale.ROOT);
            }
        }
        return null;
    }

    private static String read(FileObject fo) throws IOException {
        return fo.asText(FileEncodingQuery.getEncoding(fo).name());
    }
}
