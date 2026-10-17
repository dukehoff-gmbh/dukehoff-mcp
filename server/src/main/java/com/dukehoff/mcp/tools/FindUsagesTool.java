/*
 * Copyright 2026 Dukehoff GmbH
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dukehoff.mcp.tools;

import java.util.List;
import java.util.Map;
import javax.lang.model.element.ElementKind;
import com.dukehoff.mcp.Json;
import com.dukehoff.mcp.Tool;
import com.dukehoff.mcp.Workspace;
import org.netbeans.modules.refactoring.api.WhereUsedQuery;
import org.netbeans.modules.refactoring.java.api.WhereUsedQueryConstants;
import org.openide.filesystems.FileObject;

/**
 * Finds references to a type, method, constructor or field using the Java
 * refactoring engine ({@link WhereUsedQuery}).
 */
public final class FindUsagesTool implements Tool {

    private static final int DEFAULT_LIMIT = 200;
    /** Agents tend to re-check a usage list with grep; say what it covers so they need not. */
    private static final String COMPLETENESS = "\nComplete for the Java sources of the workspace: includes calls "
            + "without a receiver, through subclasses and via static imports; excludes other overloads, "
            + "same-named members of other types, comments and strings. Not covered: non-Java files and "
            + "reflection. No need to verify with grep.\n";

    private final Workspace workspace;

    public FindUsagesTool(Workspace workspace) {
        this.workspace = workspace;
    }

    @Override
    public String name() {
        return "find_usages";
    }

    @Override
    public String description() {
        return "Find all references to a Java type, method, constructor or field in the workspace, "
                + "resolved semantically: overloads, same-named members of other types, comments and "
                + "strings are not confused with real usages. Use this instead of grep before changing "
                + "or removing an API. Symbol syntax: com.acme.Foo, com.acme.Foo#bar(String,int), "
                + "com.acme.Foo#<init>(), com.acme.Foo#field. Parameter types may be omitted when the "
                + "method name is not overloaded. Output: usages grouped by file as 'line: source line'; with "
                + "group_by: \"caller\" grouped by the method, constructor or field that contains them, named "
                + "in the same symbol syntax (who calls it, not only where).";
    }

    @Override
    public List<String> searchTerms() {
        return List.of("find usages", "find references", "references", "callers", "who calls", "where used");
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Json.obj(
                "type", "object",
                "properties", Json.obj(
                        "symbol", Json.obj("type", "string",
                                "description", "Symbol, e.g. com.acme.OrderService#save(Order); the package may be left out when the type name is unique"),
                        "include_overriding", Json.obj("type", "boolean",
                                "description", "For methods: also report usages of overriding methods. Default false."),
                        "group_by", Json.obj("type", "string", "enum", List.of("file", "caller"),
                                "description", "file (default): usages per file; caller: per method, constructor or "
                                + "field that contains them, with its signature"),
                        "limit", Json.obj("type", "integer",
                                "description", "Maximum usages listed, default " + DEFAULT_LIMIT)),
                "required", List.of("symbol"));
    }

    @Override
    public Result call(Map<?, ?> arguments) throws Exception {
        String symbolText = Json.string(arguments, "symbol");
        if (symbolText == null) {
            return Result.error("'symbol' is required");
        }
        SymbolSpec spec;
        try {
            spec = SymbolSpec.parse(symbolText);
        } catch (IllegalArgumentException ex) {
            return Result.error(ex.getMessage());
        }
        boolean includeOverriding = Json.bool(arguments, "include_overriding", false);
        int limit = Json.integer(arguments, "limit", DEFAULT_LIMIT);
        String groupBy = Json.string(arguments, "group_by");
        if (groupBy != null && !groupBy.equals("file") && !groupBy.equals("caller")) {
            return Result.error("group_by is 'file' or 'caller', not '" + groupBy + "'");
        }
        boolean byCaller = "caller".equals(groupBy);

        List<FileObject> roots;
        SymbolResolver.Resolved symbol;
        try {
            roots = workspace.awaitSourceRoots();
            symbol = SymbolResolver.resolve(spec, roots);
        } catch (Workspace.NotReadyException | SymbolResolver.ResolutionException ex) {
            return Result.error(ex.getMessage());
        }

        if (symbol.generated()) {
            List<WhereUsed.Hit> hits = GeneratedUsages.find(symbol.handle().getElementHandle(), symbol.simpleName(), roots);
            WhereUsed.Found found = byCaller ? WhereUsed.renderByCaller(workspace, hits, symbol.signature(), "usage", limit)
                    : WhereUsed.render(workspace, hits, symbol.signature(), "usage", limit);
            return Result.ok(found.text() + COMPLETENESS);
        }
        WhereUsedQuery query = WhereUsed.query(symbol, roots);
        query.putValue(WhereUsedQuery.FIND_REFERENCES, true);
        if (includeOverriding && symbol.kind() == ElementKind.METHOD) {
            query.putValue(WhereUsedQueryConstants.FIND_OVERRIDING_METHODS, true);
        }
        WhereUsed.Found found = WhereUsed.find(workspace, query, symbol.signature(), "usage", limit, re -> true, byCaller);
        return found.error() ? Result.error(found.text()) : Result.ok(found.text() + coverage(symbol, roots));
    }

    /**
     * The completeness note, or where usages may be missing: in files with compile errors that
     * mention the name (code that does not compile cannot be attributed).
     */
    private String coverage(SymbolResolver.Resolved symbol, List<FileObject> roots) {
        String name = symbol.simpleName();
        if (name.equals("<init>")) {
            String owner = symbol.signature().substring(0, symbol.signature().indexOf('#'));
            name = owner.substring(owner.lastIndexOf('.') + 1);
        }
        List<FileObject> suspects;
        try {
            suspects = ErrorCoverage.suspects(roots, name);
        } catch (java.io.IOException ex) {
            return "\nCompleteness unknown: the list of files with compile errors could not be read ("
                    + ex.getMessage() + ").\n";
        }
        if (suspects.isEmpty()) {
            return COMPLETENESS;
        }
        StringBuilder sb = new StringBuilder("\nPossibly incomplete: ").append(suspects.size())
                .append(suspects.size() == 1 ? " file with compile errors mentions '" : " files with compile errors mention '")
                .append(name).append("'; usages there cannot be resolved and may be missing (often a dependency "
                + "missing from the module's classpath; 'diagnostics' shows the errors). Check them with grep: ");
        int shown = Math.min(suspects.size(), SUSPECTS_SHOWN);
        for (int i = 0; i < shown; i++) {
            sb.append(i == 0 ? "" : ", ").append(workspace.displayPath(suspects.get(i)));
        }
        if (suspects.size() > shown) {
            sb.append(" and ").append(suspects.size() - shown).append(" more");
        }
        return sb.append(".\n").toString();
    }

    private static final int SUSPECTS_SHOWN = 10;
}
