/*
 * Copyright 2026 Dukehoff GmbH
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dukehoff.mcp.tools;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.ModuleElement;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import org.netbeans.api.java.classpath.ClassPath;
import org.netbeans.api.java.source.ClassIndex;
import org.netbeans.api.java.source.ClasspathInfo;
import org.netbeans.api.java.source.CompilationInfo;
import org.netbeans.api.java.source.ElementHandle;
import org.netbeans.api.java.source.JavaSource;
import org.netbeans.api.java.source.SourceUtils;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;

/**
 * Types of the workspace's dependencies (the JDK and the jars on the compile class paths of its source
 * roots), which agents otherwise guess: grep cannot look into jars.
 */
final class LibraryTypes {

    /** JDK roots in NetBeans are per module, e.g. nbjrt:file:/.../!/modules/java.base/. */
    private static final Pattern JDK_MODULE = Pattern.compile("/modules/([^/]+)/?$");

    /**
     * A library type.
     *
     * @param fqn qualified name, nested types with '.'
     * @param handle handle resolvable in a {@code JavaSource.create(cpInfo)} task
     * @param cpInfo the class path it was found on
     * @param kind e.g. class, interface
     * @param origin the JDK module or the jar declaring it
     */
    record Found(String fqn, ElementHandle<TypeElement> handle, ClasspathInfo cpInfo, String kind, String origin) {
    }

    private LibraryTypes() {
    }

    /**
     * Library types whose simple name matches, from the class paths of the source roots, by qualified
     * name. Types with source in the workspace are left out (the source search finds them), and each
     * distinct class path is searched once: in large workspaces many roots share the JDK and the jars.
     */
    static Map<String, Found> find(String pattern, ClassIndex.NameKind kind, List<FileObject> roots, Predicate<String> fqnFilter) {
        Map<String, Found> found = new TreeMap<>();
        Set<String> searched = new HashSet<>();
        for (FileObject root : roots) {
            ClasspathInfo cpInfo = ClasspathInfo.create(root);
            if (!searched.add(key(cpInfo))) {
                continue;
            }
            Set<ElementHandle<TypeElement>> types = cpInfo.getClassIndex()
                    .getDeclaredTypes(pattern, kind, EnumSet.of(ClassIndex.SearchScope.DEPENDENCIES));
            if (types == null) {
                continue;
            }
            List<ElementHandle<TypeElement>> candidates = new ArrayList<>();
            for (ElementHandle<TypeElement> h : types) {
                String fqn = h.getQualifiedName().replace('$', '.');
                if (!found.containsKey(fqn) && fqnFilter.test(fqn) && !inWorkspace(h, cpInfo, roots)) {
                    candidates.add(h);
                }
            }
            for (ElementHandle<TypeElement> h : usable(cpInfo, candidates)) {
                String fqn = h.getQualifiedName().replace('$', '.');
                found.putIfAbsent(fqn, new Found(fqn, h, cpInfo, h.getKind().name().toLowerCase(Locale.ROOT).replace('_', ' '),
                        origin(cpInfo, h.getBinaryName())));
            }
        }
        return found;
    }

    /**
     * The types a project can use: public (as are their enclosing types), in a package their module
     * exports to everyone. Leaves out the JDK's internals (com.sun.tools.javac.util.List, the jdk.*
     * internals, Graal) that would otherwise make simple names like List ambiguous.
     */
    private static List<ElementHandle<TypeElement>> usable(ClasspathInfo cpInfo, List<ElementHandle<TypeElement>> handles) {
        if (handles.isEmpty()) {
            return handles;
        }
        List<ElementHandle<TypeElement>> usable = new ArrayList<>();
        try {
            JavaSource.create(cpInfo).runUserActionTask(cc -> {
                for (ElementHandle<TypeElement> h : handles) {
                    TypeElement type = h.resolve(cc);
                    if (type != null && isPublic(type) && exported(cc, type)) {
                        usable.add(h);
                    }
                }
            }, true);
        } catch (IOException ex) {
            return handles; // better unfiltered than nothing
        }
        return usable;
    }

    private static boolean isPublic(TypeElement type) {
        for (Element e = type; e instanceof TypeElement; e = e.getEnclosingElement()) {
            if (!e.getModifiers().contains(Modifier.PUBLIC)) {
                return false;
            }
        }
        return true;
    }

    private static boolean exported(CompilationInfo cc, TypeElement type) {
        ModuleElement module = cc.getElements().getModuleOf(type);
        if (module == null || module.isUnnamed() || cc.getElements().isAutomaticModule(module)) {
            return true; // a jar on the class path, or a modular jar without module-info: no boundary
        }
        PackageElement pkg = cc.getElements().getPackageOf(type);
        for (ModuleElement.Directive d : module.getDirectives()) {
            if (d instanceof ModuleElement.ExportsDirective exports && exports.getPackage().equals(pkg)
                    && exports.getTargetModules() == null) {
                return true;
            }
        }
        return false;
    }

    /**
     * The library type a name stands for: qualified, or simple / partly qualified when that is unique.
     *
     * @throws SymbolResolver.ResolutionException none or several match; the message says which
     */
    static Found resolve(String typeName, List<FileObject> roots) throws SymbolResolver.ResolutionException {
        String simple = typeName.substring(typeName.lastIndexOf('.') + 1);
        Map<String, Found> matches = find(simple, ClassIndex.NameKind.SIMPLE_NAME, roots,
                fqn -> fqn.equals(typeName) || fqn.endsWith("." + typeName));
        Found exact = matches.get(typeName);
        if (exact != null) {
            return exact;
        }
        if (matches.isEmpty()) {
            throw new SymbolResolver.ResolutionException("No type " + typeName
                    + " in the workspace sources or on their class paths (the JDK and the dependencies). Check the name, "
                    + "or that the project's dependencies are resolved (build it once).");
        }
        if (matches.size() > 1) {
            throw new SymbolResolver.ResolutionException("'" + typeName + "' is ambiguous, write the type qualified. Candidates: "
                    + String.join(", ", matches.keySet().stream().limit(10).toList())
                    + (matches.size() > 10 ? ", ... (" + matches.size() + ")" : ""));
        }
        return matches.values().iterator().next();
    }

    /** The JDK module (e.g. "java.base (JDK)") or the jar declaring a type, or "unknown". */
    static String origin(ClasspathInfo cpInfo, String binaryName) {
        String resource = binaryName.replace('.', '/') + ".class";
        ClassPath boot = cpInfo.getClassPath(ClasspathInfo.PathKind.BOOT);
        FileObject inBoot = boot == null ? null : boot.findResource(resource);
        if (inBoot != null) {
            FileObject root = boot.findOwnerRoot(inBoot);
            Matcher m = JDK_MODULE.matcher(root == null ? "" : root.toURL().toString());
            return m.find() ? m.group(1) + " (JDK)" : "JDK";
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

    /** A type whose source is in the workspace (another module of it, on this root's class path). */
    private static boolean inWorkspace(ElementHandle<TypeElement> h, ClasspathInfo cpInfo, List<FileObject> roots) {
        FileObject source = SourceUtils.getFile(h, cpInfo);
        return source != null && roots.stream().anyMatch(r -> FileUtil.isParentOf(r, source));
    }

    private static String key(ClasspathInfo cpInfo) {
        return path(cpInfo.getClassPath(ClasspathInfo.PathKind.BOOT)) + "|" + path(cpInfo.getClassPath(ClasspathInfo.PathKind.COMPILE));
    }

    private static String path(ClassPath cp) {
        return cp == null ? "" : cp.toString(ClassPath.PathConversionMode.PRINT);
    }
}
