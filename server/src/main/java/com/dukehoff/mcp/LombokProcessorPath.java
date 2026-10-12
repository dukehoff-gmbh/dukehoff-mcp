/*
 * Copyright 2026 Dukehoff GmbH
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dukehoff.mcp;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.jar.JarFile;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.netbeans.api.java.classpath.ClassPath;
import org.netbeans.api.java.classpath.JavaClassPathConstants;
import org.netbeans.spi.java.classpath.ClassPathFactory;
import org.netbeans.spi.java.classpath.ClassPathImplementation;
import org.netbeans.spi.java.classpath.ClassPathProvider;
import org.netbeans.spi.java.classpath.PathResourceImplementation;
import org.netbeans.spi.java.classpath.support.ClassPathSupport;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;
import org.openide.modules.InstalledFileLocator;
import org.openide.util.Lookup;
import org.openide.util.lookup.ServiceProvider;

/**
 * Replaces an old Lombok on a project's annotation processor path with the one bundled with dukehoff-mcp.
 * <p>
 * NetBeans parses with nb-javac, a newer javac than the JDK the project builds with, and Lombok works
 * only with the javac versions it knows: Lombok 1.18.38 to 1.18.46 generate nothing in NetBeans 31 (no
 * getters, builders, ...: false "cannot find symbol" errors, usages of generated members not found).
 * The project's build is not touched; only NetBeans' processor path is. Registered before the project
 * providers (position 100), it asks the other providers for the path and returns it with the Lombok
 * entry swapped when that Lombok is older than the bundled one.
 */
@ServiceProvider(service = ClassPathProvider.class, position = 50)
public final class LombokProcessorPath implements ClassPathProvider {

    private static final Logger LOG = Logger.getLogger(LombokProcessorPath.class.getName());
    /** Lombok as the build lays it out: lombok-1.18.38.jar (Maven, Gradle caches). */
    private static final Pattern LOMBOK_JAR = Pattern.compile("lombok-(\\d+)\\.(\\d+)\\.(\\d+)\\.jar");

    private final ThreadLocal<Boolean> delegating = new ThreadLocal<>();
    private final Map<ClassPath, ClassPath> wrapped = Collections.synchronizedMap(new WeakHashMap<>());
    private volatile Bundled bundled;

    /** The bundled Lombok: its root URL and version. */
    private record Bundled(URL root, int[] version) {
    }

    @Override
    public ClassPath findClassPath(FileObject file, String type) {
        if (!JavaClassPathConstants.PROCESSOR_PATH.equals(type) || delegating.get() != null) {
            return null;
        }
        ClassPath original;
        delegating.set(Boolean.TRUE);
        try {
            original = ClassPath.getClassPath(file, type);
        } finally {
            delegating.remove();
        }
        if (original == null || bundled() == null) {
            return original;
        }
        // one wrapper per original path: NetBeans keys caches and listeners on the instance
        return wrapped.computeIfAbsent(original, o -> ClassPathFactory.createClassPath(new Swapped(o, bundled())));
    }

    private Bundled bundled() {
        Bundled b = bundled;
        if (b == null) {
            File jar = InstalledFileLocator.getDefault().locate("modules/ext/dukehoff-mcp/lombok.jar", "com.dukehoff.mcp", false);
            if (jar == null) {
                return null;
            }
            try (JarFile jf = new JarFile(jar)) {
                int[] version = parse(jf.getManifest().getMainAttributes().getValue("Lombok-Version"));
                if (version == null) {
                    return null;
                }
                b = bundled = new Bundled(FileUtil.urlForArchiveOrDir(jar), version);
            } catch (IOException ex) {
                LOG.log(Level.WARNING, "Cannot read the bundled Lombok " + jar, ex);
                return null;
            }
        }
        return b;
    }

    private static int[] parse(String version) {
        Matcher m = version == null ? null : Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)").matcher(version);
        return m != null && m.find()
                ? new int[] {Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))}
                : null;
    }

    /** The version of a Lombok jar on a classpath, or null if the root is no Lombok jar. */
    static int[] lombokVersion(URL root) {
        File archive = FileUtil.archiveOrDirForURL(root);
        if (archive == null) {
            return null;
        }
        Matcher m = LOMBOK_JAR.matcher(archive.getName());
        return m.matches() ? parse(m.group(1) + "." + m.group(2) + "." + m.group(3)) : null;
    }

    static boolean older(int[] a, int[] b) {
        for (int i = 0; i < 3; i++) {
            if (a[i] != b[i]) {
                return a[i] < b[i];
            }
        }
        return false;
    }

    /**
     * The original path with an older Lombok replaced by the bundled one, following its changes.
     */
    private static final class Swapped implements ClassPathImplementation, PropertyChangeListener {

        private final ClassPath original;
        private final Bundled bundled;
        private final PropertyChangeSupport support = new PropertyChangeSupport(this);
        private List<PathResourceImplementation> resources;

        Swapped(ClassPath original, Bundled bundled) {
            this.original = original;
            this.bundled = bundled;
            original.addPropertyChangeListener(this);
        }

        @Override
        public synchronized List<? extends PathResourceImplementation> getResources() {
            if (resources == null) {
                List<PathResourceImplementation> r = new ArrayList<>();
                for (ClassPath.Entry e : original.entries()) {
                    URL root = e.getURL();
                    int[] version = lombokVersion(root);
                    if (version != null && older(version, bundled.version())) {
                        LOG.log(Level.INFO, "Lombok {0} on the processor path is too old for NetBeans'' compiler; "
                                + "using the bundled Lombok {1} for analysis",
                                new Object[] {FileUtil.archiveOrDirForURL(root).getName(), String.join(".",
                                        String.valueOf(bundled.version()[0]), String.valueOf(bundled.version()[1]),
                                        String.valueOf(bundled.version()[2]))});
                        root = bundled.root();
                    }
                    r.add(ClassPathSupport.createResource(root));
                }
                resources = r;
            }
            return resources;
        }

        @Override
        public void propertyChange(PropertyChangeEvent evt) {
            if (ClassPath.PROP_ENTRIES.equals(evt.getPropertyName())) {
                synchronized (this) {
                    resources = null;
                }
                support.firePropertyChange(PROP_RESOURCES, null, null);
            }
        }

        @Override
        public void addPropertyChangeListener(PropertyChangeListener l) {
            support.addPropertyChangeListener(l);
        }

        @Override
        public void removePropertyChangeListener(PropertyChangeListener l) {
            support.removePropertyChangeListener(l);
        }
    }
}
