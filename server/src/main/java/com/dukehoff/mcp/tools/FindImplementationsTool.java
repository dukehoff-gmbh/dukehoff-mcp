/*
 * Copyright 2026 Dukehoff GmbH
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dukehoff.mcp.tools;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import com.dukehoff.mcp.Json;
import com.dukehoff.mcp.Offered;
import com.dukehoff.mcp.Tool;
import com.dukehoff.mcp.Workspace;
import org.netbeans.modules.refactoring.api.WhereUsedQuery;
import org.netbeans.modules.refactoring.java.api.WhereUsedQueryConstants;
import org.openide.filesystems.FileObject;

/**
 * Finds subtypes of a type or overriding methods of a method using the Java
 * refactoring engine ({@link WhereUsedQuery}).
 */
public final class FindImplementationsTool implements Tool {

    private static final int DEFAULT_LIMIT = 200;

    private final Workspace workspace;

    public FindImplementationsTool(Workspace workspace) {
        this.workspace = workspace;
    }

    @Override
    public String name() {
        return "find_implementations";
    }

    @Override
    public String description() {
        return "Find the implementations of a Java interface or class (all subtypes, also indirect ones) "
                + "or of a method (all overriding methods) in the workspace. For a method it also lists what "
                + "the method overrides or implements upwards (superclasses, interfaces, libraries), so you "
                + "see the contract before changing it. Use this instead of grepping "
                + "for 'implements'/'extends' or method names, e.g. before changing an interface method. "
                + "Symbol syntax: com.acme.Foo, com.acme.Foo#bar(String,int); parameter types may be omitted "
                + "when the method name is not overloaded. Output: declarations grouped by file as "
                + "'line: source line'.";
    }

    @Override
    public List<String> searchTerms() {
        return List.of("implementations", "subclasses", "subtypes", "implementers", "overriding methods", "type hierarchy",
                "overridden method", "super method");
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Json.obj(
                "type", "object",
                "properties", Json.obj(
                        "symbol", Json.obj("type", "string",
                                "description", "Type or method, e.g. com.acme.PaymentService#pay(Order); the package may be left out when the type name is unique"),
                        "direct_only", Json.obj("type", "boolean",
                                "description", "For types: only direct subtypes. Default false."),
                        "limit", Json.obj("type", "integer",
                                "description", "Maximum results listed, default " + DEFAULT_LIMIT)),
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
        boolean directOnly = Json.bool(arguments, "direct_only", false);
        int limit = Json.integer(arguments, "limit", DEFAULT_LIMIT);

        List<FileObject> roots;
        SymbolResolver.Resolved symbol;
        try {
            roots = workspace.awaitSourceRoots();
            symbol = SymbolResolver.resolve(spec, roots);
        } catch (Workspace.NotReadyException | SymbolResolver.ResolutionException ex) {
            return Result.error(ex.getMessage());
        }

        WhereUsedQuery query = WhereUsed.query(symbol, roots);
        query.putValue(WhereUsedQuery.FIND_REFERENCES, false);
        String upwards = "";
        switch (symbol.kind()) {
            case CLASS, INTERFACE, ENUM, RECORD, ANNOTATION_TYPE ->
                query.putValue(directOnly ? WhereUsedQueryConstants.FIND_DIRECT_SUBCLASSES
                        : WhereUsedQueryConstants.FIND_SUBCLASSES, true);
            case METHOD -> {
                query.putValue(WhereUsedQueryConstants.FIND_OVERRIDING_METHODS, true);
                upwards = OverrideChain.describe(workspace, symbol, roots);
            }
            default -> {
                return Result.error(symbol.signature() + " is a " + symbol.kind().name().toLowerCase(Locale.ROOT)
                        + "; implementations exist only for types and methods."
                        + (Offered.has("find_usages") ? " Use find_usages instead." : ""));
            }
        }
        Result downwards = WhereUsed.run(workspace, query, symbol.signature(), "implementation", limit);
        return upwards.isEmpty() ? downwards : new Result(upwards + downwards.text(), downwards.error());
    }
}
