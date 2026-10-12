/*
 * Copyright 2026 Dukehoff GmbH
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dukehoff.mcp.tools;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.ModuleElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import org.netbeans.api.java.classpath.ClassPath;
import org.netbeans.api.java.source.ClasspathInfo;
import org.netbeans.api.java.source.CompilationController;
import org.netbeans.api.java.source.ElementHandle;
import org.netbeans.api.java.source.JavaSource;
import org.netbeans.api.java.source.SourceUtils;
import com.dukehoff.mcp.Workspace;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;

/**
 * Lists the methods a method overrides or implements, upwards through all
 * supertypes, nearest first: workspace declarations as {@code file:line: source line},
 * library ones with the jar or JDK module they come from.
 */
final class OverrideChain {

    private OverrideChain() {
    }

    /** One overridden method; {@code file} is set for workspace sources, {@code library} otherwise. */
    private record Super(String verb, String signature, ElementHandle<ExecutableElement> handle,
            FileObject file, String library) {
    }

    /**
     * The upward part of the answer for a resolved method, ending with a blank line.
     */
    static String describe(Workspace workspace, SymbolResolver.Resolved method, List<FileObject> roots) throws IOException {
        JavaSource js = JavaSource.forFileObject(method.file());
        if (js == null) {
            return "";
        }
        List<Super> supers = new ArrayList<>();
        js.runUserActionTask(cc -> {
            cc.toPhase(JavaSource.Phase.RESOLVED);
            if (method.handle().resolveElement(cc) instanceof ExecutableElement ee
                    && ee.getEnclosingElement() instanceof TypeElement owner) {
                collect(cc, ee, owner, roots, supers);
            }
        }, true);
        if (supers.isEmpty()) {
            return method.signature() + " overrides or implements nothing.\n\n";
        }
        Map<FileObject, List<String>> lines = new LinkedHashMap<>();
        StringBuilder sb = new StringBuilder(method.signature()).append(" overrides or implements, nearest first:\n");
        for (Super s : supers) {
            sb.append("  ").append(s.verb()).append(' ').append(s.signature());
            if (s.file() == null) {
                sb.append("  [library: ").append(s.library()).append("]\n");
                continue;
            }
            int line = line(s.file(), s.handle());
            List<String> text = lines.computeIfAbsent(s.file(), WhereUsed::readLines);
            sb.append("  ").append(workspace.displayPath(s.file()));
            if (line > 0) {
                sb.append(':').append(line);
                if (line <= text.size()) {
                    sb.append(": ").append(text.get(line - 1).strip());
                }
            }
            sb.append('\n');
        }
        return sb.append('\n').toString();
    }

    private static void collect(CompilationController cc, ExecutableElement method, TypeElement owner,
            List<FileObject> roots, List<Super> into) {
        if (method.getKind() != ElementKind.METHOD || method.getModifiers().contains(Modifier.STATIC)
                || method.getModifiers().contains(Modifier.PRIVATE)) {
            return;
        }
        // breadth first over all supertypes, so the nearest declarations come first
        Queue<TypeElement> queue = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        enqueueSupertypes(cc, owner.asType(), queue, seen);
        while (!queue.isEmpty()) {
            TypeElement type = queue.remove();
            for (Element e : type.getEnclosedElements()) {
                if (e instanceof ExecutableElement candidate && e.getKind() == ElementKind.METHOD
                        && candidate.getSimpleName().contentEquals(method.getSimpleName())
                        && cc.getElements().overrides(method, candidate, owner)) {
                    into.add(describe(cc, candidate, type, roots));
                }
            }
            enqueueSupertypes(cc, type.asType(), queue, seen);
        }
    }

    private static void enqueueSupertypes(CompilationController cc, TypeMirror type, Queue<TypeElement> queue, Set<String> seen) {
        for (TypeMirror st : cc.getTypes().directSupertypes(type)) {
            if (st instanceof DeclaredType dt && dt.asElement() instanceof TypeElement te
                    && seen.add(te.getQualifiedName().toString())) {
                queue.add(te);
            }
        }
    }

    private static Super describe(CompilationController cc, ExecutableElement candidate, TypeElement type, List<FileObject> roots) {
        boolean implementsIt = candidate.getModifiers().contains(Modifier.ABSTRACT);
        String verb = implementsIt ? "implements" : "overrides";
        String signature = SymbolResolver.signature(candidate, cc);
        ElementHandle<ExecutableElement> handle = ElementHandle.create(candidate);
        FileObject source = SourceUtils.getFile(ElementHandle.create(type), cc.getClasspathInfo());
        if (source != null && roots.stream().anyMatch(r -> r.equals(source) || FileUtil.isParentOf(r, source))) {
            return new Super(verb, signature, handle, source, null);
        }
        return new Super(verb, signature, handle, null, library(cc, type));
    }

    /** The JDK module or the jar declaring a type, or "unknown". */
    private static String library(CompilationController cc, TypeElement type) {
        ClasspathInfo cpInfo = cc.getClasspathInfo();
        String resource = ElementHandle.create(type).getBinaryName().replace('.', '/') + ".class";
        ClassPath boot = cpInfo.getClassPath(ClasspathInfo.PathKind.BOOT);
        if (boot != null && boot.findResource(resource) != null) {
            // the JDK's roots are named after NetBeans' internal layout (e.g. "current"), so name the module
            ModuleElement module = cc.getElements().getModuleOf(type);
            return module == null || module.isUnnamed() ? "JDK" : module.getQualifiedName() + " (JDK)";
        }
        ClassPath compile = cpInfo.getClassPath(ClasspathInfo.PathKind.COMPILE);
        FileObject fo = compile == null ? null : compile.findResource(resource);
        FileObject root = fo == null ? null : compile.findOwnerRoot(fo);
        if (root == null) {
            return "unknown";
        }
        FileObject archive = FileUtil.getArchiveFile(root);
        return archive != null ? archive.getNameExt() : root.getPath();
    }

    /** Line (1-based) of a method declared in a workspace file, or 0. */
    private static int line(FileObject file, ElementHandle<ExecutableElement> handle) throws IOException {
        JavaSource js = JavaSource.forFileObject(file);
        if (js == null) {
            return 0;
        }
        int[] line = {0};
        js.runUserActionTask(cc -> {
            cc.toPhase(JavaSource.Phase.ELEMENTS_RESOLVED);
            ExecutableElement e = handle.resolve(cc);
            if (e != null) {
                line[0] = SymbolResolver.line(cc, e);
            }
        }, true);
        return line[0];
    }
}
