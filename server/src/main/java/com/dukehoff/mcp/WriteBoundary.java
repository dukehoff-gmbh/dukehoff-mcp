/*
 * Copyright 2026 Dukehoff GmbH
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dukehoff.mcp;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Where the tools may write. Refactorings write files themselves, apart from the agent's own edits, so
 * the harness's edit permissions never see them. Like a harness, which treats everything outside the
 * session's folder as external, they may change only files in the workspace (inside an IDE: the open
 * projects), plus folders the registration allows ({@code --allow-writes}) and paths a call allows
 * ({@code allow_outside}, which the agent passes after asking the user). Anything else refuses the
 * whole refactoring before a file is written.
 * <p>
 * The registration's folders are per connection (several registrations share one server), so like
 * {@link Offered} they are set per call.
 */
public final class WriteBoundary {

    private static final ThreadLocal<List<String>> REGISTERED = new ThreadLocal<>();

    private WriteBoundary() {
    }

    /** The folders the current client's registration allows besides the workspace, as given. */
    public static List<String> registered() {
        List<String> r = REGISTERED.get();
        return r == null ? List.of() : r;
    }

    static void set(List<String> paths) {
        REGISTERED.set(List.copyOf(paths));
    }

    static void clear() {
        REGISTERED.remove();
    }

    /** {@code --allow-writes} as a list: comma separated paths. */
    public static List<String> parse(String spec) {
        List<String> paths = new ArrayList<>();
        if (spec != null) {
            for (String p : spec.split(",")) {
                if (!p.isBlank()) {
                    paths.add(p.trim());
                }
            }
        }
        return paths;
    }

    /** The files that lie in none of the roots (a root is a folder or a single file). */
    public static List<File> outside(Collection<File> files, Collection<File> roots) {
        List<File> outside = new ArrayList<>();
        for (File f : files) {
            if (roots.stream().noneMatch(r -> f.toPath().normalize().startsWith(r.toPath().normalize()))) {
                outside.add(f);
            }
        }
        return outside;
    }
}
