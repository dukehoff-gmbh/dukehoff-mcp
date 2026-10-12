/*
 * Copyright 2026 Dukehoff GmbH
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dukehoff.mcp.tools;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import javax.lang.model.element.Element;
import org.netbeans.api.java.source.ElementHandle;
import org.netbeans.api.java.source.JavaSource;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;

/**
 * Usages of a member an annotation processor generated, such as Lombok's getters and builders.
 * <p>
 * NetBeans' Find Usages compiles the candidate files together, without annotation processors, so such
 * members do not exist there and nothing is found. A single file is compiled with its processors, so
 * this searches the files that contain the member's name one by one and compares what each reference
 * resolves to.
 */
final class GeneratedUsages {

    private GeneratedUsages() {
    }

    static List<WhereUsed.Hit> find(ElementHandle<? extends Element> target, String name, List<FileObject> roots)
            throws IOException {
        List<WhereUsed.Hit> hits = new ArrayList<>();
        for (FileObject file : filesContaining(name, roots)) {
            JavaSource js = JavaSource.forFileObject(file);
            if (js == null) {
                continue;
            }
            js.runUserActionTask(cc -> {
                cc.toPhase(JavaSource.Phase.RESOLVED);
                CompilationUnitTree cu = cc.getCompilationUnit();
                new TreePathScanner<Void, Void>() {
                    @Override
                    public Void visitIdentifier(IdentifierTree node, Void p) {
                        check();
                        return super.visitIdentifier(node, p);
                    }

                    @Override
                    public Void visitMemberSelect(MemberSelectTree node, Void p) {
                        check();
                        return super.visitMemberSelect(node, p);
                    }

                    @Override
                    public Void visitMemberReference(MemberReferenceTree node, Void p) {
                        check();
                        return super.visitMemberReference(node, p);
                    }

                    @Override
                    public Void visitMethod(MethodTree node, Void p) {
                        // the processor's own generated code (Lombok's toString calls the getters)
                        // has no source position: not a usage anyone wrote
                        return SymbolResolver.span(cc, node) == null ? null : super.visitMethod(node, p);
                    }

                    private void check() {
                        TreePath path = getCurrentPath();
                        Element e = cc.getTrees().getElement(path);
                        if (e == null || e.getKind() != target.getKind() || !target.equals(ElementHandle.create(e))) {
                            return;
                        }
                        Tree leaf = path.getLeaf();
                        int[] span = SymbolResolver.span(cc, leaf);
                        if (span != null) {
                            hits.add(new WhereUsed.Hit(file, (int) cu.getLineMap().getLineNumber(span[0]) - 1, span[0]));
                        }
                    }
                }.scan(cu, null);
            }, true);
        }
        return hits;
    }

    /** The Java files under the roots whose text contains the name: a reference has to spell it. */
    private static List<FileObject> filesContaining(String name, List<FileObject> roots) throws IOException {
        List<FileObject> found = new ArrayList<>();
        for (FileObject root : roots) {
            File dir = FileUtil.toFile(root);
            if (dir == null) {
                continue;
            }
            try (Stream<Path> paths = Files.walk(dir.toPath())) {
                paths.filter(p -> p.toString().endsWith(".java") && Files.isRegularFile(p) && contains(p, name))
                        .map(p -> FileUtil.toFileObject(p.toFile()))
                        .filter(fo -> fo != null)
                        .forEach(found::add);
            } catch (UncheckedIOException ex) {
                throw ex.getCause();
            }
        }
        return found;
    }

    private static boolean contains(Path file, String name) {
        try {
            // names are identifiers; ISO-8859-1 finds an ASCII name in any ASCII-compatible encoding
            return new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1).contains(name);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
