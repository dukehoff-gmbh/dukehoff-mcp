/*
 * Copyright 2026 Dukehoff GmbH
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dukehoff.mcp.tools;

import java.io.File;
import java.net.URL;
import java.util.List;
import java.util.Map;
import javax.lang.model.SourceVersion;
import com.dukehoff.mcp.Json;
import com.dukehoff.mcp.Tool;
import com.dukehoff.mcp.Workspace;
import org.netbeans.modules.refactoring.api.MoveRefactoring;
import org.netbeans.modules.refactoring.java.api.JavaRefactoringUtils;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;
import org.openide.util.Utilities;
import org.openide.util.lookup.Lookups;

/**
 * Moves a top-level class to another package with the Java refactoring
 * engine.
 */
public final class MoveTool implements Tool {

    private final Workspace workspace;

    public MoveTool(Workspace workspace) {
        this.workspace = workspace;
    }

    @Override
    public String name() {
        return "move";
    }

    @Override
    public String description() {
        return "Move a top-level Java class, interface, enum or record to another package (created if needed) in "
                + "the same source root, and write the changes to disk. Updates the package declaration, all "
                + "imports and qualified references, and adds imports where classes of the old package are "
                + "used; moving the file by hand does none of this. The whole file moves, including other "
                + "top-level types declared in it. Visibility problems (package-private members used across the "
                + "new package boundary) are reported, and fatal ones stop the move before anything changes. "
                + "Output: warnings, then a unified diff (use git to review or revert)." + RefactoringRunner.COMPILE_CHECK;
    }

    @Override
    public List<String> searchTerms() {
        return List.of("move class", "change package", "refactor move");
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Json.obj(
                "type", "object",
                "properties", Json.obj(
                        "symbol", Json.obj("type", "string",
                                "description", "Top-level type, e.g. com.acme.shop.Invoice; the package may be left out when the type name is unique"),
                        "target_package", Json.obj("type", "string",
                                "description", "Package to move to, e.g. com.acme.billing"),
                        "max_diff_lines", Json.obj("type", "integer",
                                "description", "Maximum diff lines returned, default "
                                + RefactoringRunner.DEFAULT_MAX_DIFF_LINES + "; the move itself is always complete")),
                "required", List.of("symbol", "target_package"));
    }

    @Override
    public Result call(Map<?, ?> arguments) throws Exception {
        String targetPackage = Json.string(arguments, "target_package");
        if (targetPackage == null || !SourceVersion.isName(targetPackage)) {
            return Result.error("'target_package' must be a package name like com.acme.billing");
        }
        int maxDiffLines = Json.integer(arguments, "max_diff_lines", RefactoringRunner.DEFAULT_MAX_DIFF_LINES);
        Resolution r = Resolution.of(workspace, arguments);
        if (r.error() != null) {
            return r.error();
        }
        SymbolResolver.Resolved symbol = r.symbol();
        if (!symbol.topLevelType()) {
            return Result.error("move works on top-level types; " + symbol.signature()
                    + " is a " + (symbol.kind().isClass() || symbol.kind().isInterface() ? "nested type" : "member") + ".");
        }
        FileObject file = symbol.file();
        FileObject root = r.roots().stream().filter(sr -> FileUtil.isParentOf(sr, file)).findFirst().orElse(null);
        if (root == null) {
            return Result.error("Cannot find the source root of " + workspace.displayPath(file));
        }
        String targetPath = targetPackage.replace('.', '/');
        if (targetPath.equals(FileUtil.getRelativePath(root, file.getParent()))) {
            return Result.error(symbol.signature() + " is already in package " + targetPackage + ".");
        }
        // the target may not exist yet; the refactoring creates it
        FileObject existing = root.getFileObject(targetPath);
        URL target = existing != null
                ? existing.toURL()
                : Utilities.toURI(new File(FileUtil.toFile(root), targetPath)).toURL();

        MoveRefactoring refactoring = new MoveRefactoring(Lookups.fixed(file));
        refactoring.getContext().add(JavaRefactoringUtils.getClasspathInfoFor(file));
        refactoring.setTarget(Lookups.singleton(target));
        String nameExt = file.getNameExt();
        return new RefactoringRunner(workspace, r.roots(), refactoring,
                "move " + symbol.signature() + " to package " + targetPackage)
                .involving(file)
                .relocatingWith(fo -> {
                    FileObject folder = root.getFileObject(targetPath);
                    return folder != null ? folder.getFileObject(nameExt) : null;
                })
                .run(maxDiffLines);
    }
}
