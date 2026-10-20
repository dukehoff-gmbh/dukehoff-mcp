/*
 * Copyright 2026 Dukehoff GmbH
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dukehoff.mcp.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.lang.model.SourceVersion;
import com.dukehoff.mcp.Json;
import com.dukehoff.mcp.Tool;
import com.dukehoff.mcp.Workspace;
import org.netbeans.modules.refactoring.api.RenameRefactoring;
import org.netbeans.modules.refactoring.java.api.JavaRefactoringUtils;
import org.openide.filesystems.FileObject;
import org.openide.util.lookup.Lookups;

/**
 * Renames a type, method or field with the Java refactoring engine and
 * reports the changes as a unified diff. The files are changed on disk.
 */
public final class RenameTool implements Tool {

    private final Workspace workspace;

    public RenameTool(Workspace workspace) {
        this.workspace = workspace;
    }

    @Override
    public String name() {
        return "rename";
    }

    @Override
    public String description() {
        return "Rename a Java type, method or field everywhere in the workspace with the IDE's refactoring "
                + "engine, and write the changes to disk. Handles what text replacement gets wrong: overloads "
                + "and same-named members of other types stay untouched, overriding methods are renamed with "
                + "the method, a renamed top-level class also renames its file, imports and qualified "
                + "references are updated. Symbol syntax: com.acme.Foo, com.acme.Foo#bar(String,int), "
                + "com.acme.Foo#field. If the rename would break the code (name clash, ...) nothing is "
                + "changed and the problem is reported. Output: warnings, then a unified diff of all changes "
                + "(use git to review or revert)." + RefactoringRunner.COMPILE_CHECK + " Like the IDE's rename, it "
                + "does not change strings; the result lists the string literals that still contain the old name "
                + "(reflection, names used as data), so no grep for them is needed.";
    }

    @Override
    public List<String> searchTerms() {
        return List.of("rename symbol", "rename method", "rename class", "rename field", "refactor rename");
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Json.obj(
                "type", "object",
                "properties", Json.obj(
                        "symbol", Json.obj("type", "string",
                                "description", "Symbol, e.g. com.acme.OrderService#save(Order); the package may be left out when the type name is unique"),
                        "new_name", Json.obj("type", "string",
                                "description", "New simple name, e.g. store (not qualified)"),
                        RefactoringRunner.ALLOW_OUTSIDE, RefactoringRunner.ALLOW_OUTSIDE_SCHEMA,
                        "max_diff_lines", Json.obj("type", "integer",
                                "description", "Maximum diff lines returned, default " + RefactoringRunner.DEFAULT_MAX_DIFF_LINES
                                + "; the rename itself is always complete")),
                "required", List.of("symbol", "new_name"));
    }

    @Override
    public Result call(Map<?, ?> arguments) throws Exception {
        String symbolText = Json.string(arguments, "symbol");
        String newName = Json.string(arguments, "new_name");
        if (symbolText == null || newName == null) {
            return Result.error("'symbol' and 'new_name' are required");
        }
        if (!SourceVersion.isIdentifier(newName) || SourceVersion.isKeyword(newName)) {
            return Result.error("'" + newName + "' is not a valid Java identifier. Give the new simple name only.");
        }
        int maxDiffLines = Json.integer(arguments, "max_diff_lines", RefactoringRunner.DEFAULT_MAX_DIFF_LINES);
        SymbolSpec spec;
        try {
            spec = SymbolSpec.parse(symbolText);
        } catch (IllegalArgumentException ex) {
            return Result.error(ex.getMessage());
        }
        List<FileObject> roots;
        SymbolResolver.Resolved symbol;
        try {
            roots = workspace.awaitSourceRoots();
            symbol = SymbolResolver.resolve(spec, roots);
        } catch (Workspace.NotReadyException | SymbolResolver.ResolutionException ex) {
            return Result.error(ex.getMessage());
        }
        if (symbol.generated()) {
            return Result.error(symbol.generatedError());
        }
        switch (symbol.kind()) {
            case CLASS, INTERFACE, ENUM, RECORD, ANNOTATION_TYPE, METHOD, FIELD, ENUM_CONSTANT -> {
            }
            case CONSTRUCTOR -> {
                return Result.error("Constructors are named after their class; rename the class instead.");
            }
            default -> {
                return Result.error("Renaming a " + symbol.kind() + " is not supported.");
            }
        }
        if (newName.equals(symbol.simpleName())) {
            return Result.error(symbol.signature() + " is already called " + newName + ".");
        }

        List<Object> lookup = new ArrayList<>();
        lookup.add(symbol.handle());
        if (symbol.topLevelType() && symbol.simpleName().equals(symbol.file().getName())) {
            // like the IDE and the LSP server: rename the file together with its public class
            lookup.add(symbol.file());
        }
        RenameRefactoring refactoring = new RenameRefactoring(Lookups.fixed(lookup.toArray()));
        refactoring.getContext().add(JavaRefactoringUtils.getClasspathInfoFor(symbol.file()));
        refactoring.setNewName(newName);

        String oldName = symbol.simpleName();
        Result result = new RefactoringRunner(workspace, roots, refactoring,
                "rename " + symbol.signature() + " to " + newName)
                .involving(symbol.file())
                // a renamed public class renames its file
                .relocatingWith(fo -> fo.getParent() != null ? fo.getParent().getFileObject(newName, fo.getExt()) : null)
                .allowingOutside(arguments)
                .run(maxDiffLines);
        if (result.error()) {
            return result;
        }
        // what neither the rename nor the compile check covers: the old name in strings
        return Result.ok(result.text() + StringMentions.report(StringMentions.find(roots, oldName, workspace::displayPath), oldName));
    }
}
