/*
 * Copyright 2026 Dukehoff GmbH
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dukehoff.mcp.tools;

import com.sun.source.tree.Tree;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import javax.lang.model.element.Element;
import org.netbeans.api.java.source.JavaSource;
import com.dukehoff.mcp.Json;
import com.dukehoff.mcp.Tool;
import com.dukehoff.mcp.Workspace;
import org.netbeans.modules.refactoring.api.RefactoringElement;
import org.netbeans.modules.refactoring.api.SafeDeleteRefactoring;
import org.netbeans.modules.refactoring.api.WhereUsedQuery;
import org.netbeans.modules.refactoring.java.api.JavaRefactoringUtils;
import org.openide.util.lookup.Lookups;

/**
 * Deletes a type or member only if nothing outside of it uses it; otherwise
 * lists the usages that block the deletion.
 */
public final class SafeDeleteTool implements Tool {

    private static final int BLOCKING_USAGES_LIMIT = 50;

    private final Workspace workspace;

    public SafeDeleteTool(Workspace workspace) {
        this.workspace = workspace;
    }

    @Override
    public String name() {
        return "safe_delete";
    }

    @Override
    public String description() {
        return "Delete a Java type, method, constructor or field, but only if nothing uses it anymore; write the "
                + "change to disk. If usages remain, nothing is deleted and the blocking usages are listed "
                + "('line: source line' by file) so you can remove or migrate them first. Usages inside the "
                + "deleted element itself (recursion, a class using itself) do not block. A top-level class "
                + "deletes its file. Symbol syntax: com.acme.Foo, com.acme.Foo#bar(String,int), com.acme.Foo#field. "
                + "Output: a unified diff of the deletion." + RefactoringRunner.COMPILE_CHECK;
    }

    @Override
    public List<String> searchTerms() {
        return List.of("safe delete", "delete method", "delete class", "remove unused code");
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Json.obj(
                "type", "object",
                "properties", Json.obj(
                        "symbol", Json.obj("type", "string",
                                "description", "Symbol, e.g. com.acme.LegacyService#oldMethod(); the package may be left out when the type name is unique"),
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
        boolean wholeFile = symbol.topLevelType() && symbol.simpleName().equals(symbol.file().getName());

        // the Java safe-delete plugin reports remaining references only as a warning (the IDE's
        // dialog stops there); check ourselves so nothing used gets deleted
        int[] own = wholeFile ? null : declarationSpan(symbol);
        Predicate<RefactoringElement> outside = re -> {
            if (re.getParentFile().equals(symbol.file())) {
                if (wholeFile) {
                    return false;
                }
                int offset = re.getPosition().getBegin().getOffset();
                return own == null || offset < own[0] || offset >= own[1];
            }
            return true;
        };
        WhereUsedQuery usages = WhereUsed.query(symbol, r.roots());
        usages.putValue(WhereUsedQuery.FIND_REFERENCES, true);
        WhereUsed.Found found = WhereUsed.find(workspace, usages, symbol.signature(), "usage",
                BLOCKING_USAGES_LIMIT, outside);
        if (found.error()) {
            return Result.error(found.text());
        }
        if (found.total() > 0) {
            return Result.error("Cannot safely delete " + symbol.signature() + ": it is still used. "
                    + "Remove or replace these usages first. Nothing was changed.\n\n" + found.text());
        }

        SafeDeleteRefactoring refactoring = new SafeDeleteRefactoring(
                wholeFile ? Lookups.fixed(symbol.file()) : Lookups.fixed(symbol.handle()));
        refactoring.getContext().add(JavaRefactoringUtils.getClasspathInfoFor(symbol.file()));
        return new RefactoringRunner(workspace, r.roots(), refactoring, "safely delete " + symbol.signature())
                .involving(symbol.file())
                .allowingOutside(arguments)
                .run(maxDiffLines);
    }

    /** Start and end offset of the element's declaration in its file. */
    private static int[] declarationSpan(SymbolResolver.Resolved symbol) throws Exception {
        JavaSource js = JavaSource.forFileObject(symbol.file());
        if (js == null) {
            return null;
        }
        AtomicReference<int[]> result = new AtomicReference<>();
        js.runUserActionTask(cc -> {
            cc.toPhase(JavaSource.Phase.RESOLVED);
            Element e = symbol.handle().resolveElement(cc);
            Tree tree = e != null ? cc.getTrees().getTree(e) : null;
            if (tree != null) {
                result.set(SymbolResolver.span(cc, tree));
            }
        }, true);
        return result.get();
    }
}
