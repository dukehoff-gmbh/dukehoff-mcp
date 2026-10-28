/*
 * Copyright 2026 Dukehoff GmbH
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dukehoff.mcp.tools;

import java.io.File;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.netbeans.api.java.classpath.ClassPath;
import org.netbeans.api.java.classpath.JavaClassPathConstants;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;
import org.openide.modules.ModuleInfo;
import org.openide.modules.Modules;

/**
 * A Lombok too old for nb-javac on a processor path, while the module that swaps it (Lombok for nb-javac,
 * {@code com.dukehoff.netbeans.lombok}) is not installed: only possible in a NetBeans IDE, since the runtime and
 * the VS Code extension include that module. Lombok then generates nothing, and errors about its members
 * are false.
 */
final class OldLombok {

    /** The first Lombok release that works with the nb-javac of NetBeans 31 (that plugin bundles it). */
    static final int[] WORKING = {1, 18, 48};
    private static final String SWAPPING_MODULE = "com.dukehoff.netbeans.lombok";
    private static final Pattern LOMBOK_JAR = Pattern.compile("lombok-(\\d+)\\.(\\d+)\\.(\\d+)\\.jar");

    private OldLombok() {
    }

    /** A note for results with compile errors in these files, or null. */
    static String note(Collection<FileObject> files) {
        ModuleInfo swapping = Modules.getDefault().findCodeNameBase(SWAPPING_MODULE);
        if (swapping != null && swapping.isEnabled()) {
            return null;
        }
        Set<ClassPath> seen = new HashSet<>();
        for (FileObject fo : files) {
            ClassPath path = ClassPath.getClassPath(fo, JavaClassPathConstants.PROCESSOR_PATH);
            if (path == null || !seen.add(path)) {
                continue;
            }
            for (ClassPath.Entry e : path.entries()) {
                File archive = FileUtil.archiveOrDirForURL(e.getURL());
                String old = archive == null ? null : tooOld(archive.getName());
                if (old != null) {
                    return "Note: " + old + " on the annotation processor path generates nothing in NetBeans' "
                            + "javac (Lombok 1.18.48 or newer works), so errors about Lombok-generated members "
                            + "(getters, builders, ...) are false. The build is not affected. Fix: install the "
                            + "NetBeans plugin \"Lombok for nb-javac\" (github.com/dukehoff-gmbh/lombok-nb-javac) and "
                            + "restart NetBeans, or update Lombok in the project.";
                }
            }
        }
        return null;
    }

    /** The jar's name if it is a Lombok older than {@link #WORKING}, else null. */
    static String tooOld(String jarName) {
        Matcher m = LOMBOK_JAR.matcher(jarName);
        if (!m.matches()) {
            return null;
        }
        for (int i = 0; i < 3; i++) {
            int v = Integer.parseInt(m.group(i + 1));
            if (v != WORKING[i]) {
                return v < WORKING[i] ? jarName : null;
            }
        }
        return null;
    }
}
