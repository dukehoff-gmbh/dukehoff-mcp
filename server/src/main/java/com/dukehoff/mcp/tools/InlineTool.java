/*
 * Copyright 2026 Dukehoff GmbH
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dukehoff.mcp.tools;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import com.dukehoff.mcp.Json;
import com.dukehoff.mcp.Tool;
import com.dukehoff.mcp.Workspace;
import org.netbeans.modules.refactoring.java.api.InlineRefactoring;
import org.netbeans.modules.refactoring.java.api.JavaRefactoringUtils;

/**
 * Inlines a method or a constant into all its usages and removes it.
 */
public final class InlineTool implements Tool {

    private final Workspace workspace;

    public InlineTool(Workspace workspace) {
        this.workspace = workspace;
    }

    @Override
    public String name() {
        return "inline";
    }

    @Override
    public String description() {
        return "Inline a Java method (replace every call with the method body, adapting parameters and returns) or "
                + "a constant (static final field: replace every usage with its value) and remove the declaration; "
                + "write the changes to disk. Refuses, and changes nothing, when inlining is not possible (e.g. "
                + "recursive or overridden methods, bodies referencing members that are not accessible at a call "
                + "site). An argument is substituted for each use of its parameter, so an argument expression can "
                + "end up evaluated more than once: check the diff when arguments have side effects or are "
                + "expensive. Symbol syntax: com.acme.Foo#helper(String), com.acme.Foo#MAX_SIZE. Output: warnings, "
                + "then a unified diff." + RefactoringRunner.COMPILE_CHECK;
    }

    @Override
    public List<String> searchTerms() {
        return List.of("inline method", "inline constant", "refactor inline");
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Json.obj(
                "type", "object",
                "properties", Json.obj(
                        "symbol", Json.obj("type", "string",
                                "description", "Method or constant, e.g. com.acme.Foo#helper(String); the package may be left out when the type name is unique"),
                        RefactoringRunner.ALLOW_OUTSIDE, RefactoringRunner.ALLOW_OUTSIDE_SCHEMA,
                        "max_diff_lines", Json.obj("type", "integer",
                                "description", "Maximum diff lines returned, default " + RefactoringRunner.DEFAULT_MAX_DIFF_LINES)),
                "required", List.of("symbol"));
    }

    @Override
    public Result call(Map<?, ?> arguments) throws Exception {
        int maxDiffLines = Json.integer(arguments, "max_diff_lines", RefactoringRunner.DEFAULT_MAX_DIFF_LINES);
        Resolution r = Resolution.of(workspace, arguments);
        if (r.error() != null) {
            return r.error();
        }
        SymbolResolver.Resolved symbol = r.symbol();
        InlineRefactoring.Type type = switch (symbol.kind()) {
            case METHOD -> InlineRefactoring.Type.METHOD;
            case FIELD -> InlineRefactoring.Type.CONSTANT;
            default -> null;
        };
        if (type == null) {
            return Result.error("inline works on methods and constants; " + symbol.signature() + " is a "
                    + symbol.kind().name().toLowerCase(Locale.ROOT) + ".");
        }
        InlineRefactoring refactoring = new InlineRefactoring(symbol.handle(), type);
        refactoring.getContext().add(JavaRefactoringUtils.getClasspathInfoFor(symbol.file()));
        return new RefactoringRunner(workspace, r.roots(), refactoring, "inline " + symbol.signature())
                .involving(symbol.file())
                .allowingOutside(arguments)
                .run(maxDiffLines);
    }
}
