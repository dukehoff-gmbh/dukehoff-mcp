/*
 * Copyright 2026 Dukehoff GmbH
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dukehoff.mcp;

import java.awt.GraphicsEnvironment;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.json.simple.JSONValue;
import org.openide.filesystems.FileUtil;
import org.openide.modules.ModuleInfo;
import org.openide.modules.Modules;
import org.openide.modules.OnStart;
import org.openide.modules.Places;
import org.openide.util.Lookup;
import org.openide.util.RequestProcessor;

/**
 * The MCP server inside a running NetBeans IDE, for agents in a terminal (Claude Code, Codex, Copilot
 * CLI): they share the IDE's projects and index instead of starting a NetBeans of their own, and their
 * refactorings show in the IDE at once. Started with the IDE (not in our headless runtime, nor in the
 * NetBeans of the VS Code extension) on an owner-only Unix domain socket {@code <pid>.sock} next to its
 * announcement {@code ~/.dukehoff-mcp/ide/<pid>.json} ({@code DUKEHOFF_MCP_IDE_DIR} elsewhere), where the
 * launcher's {@code --ide} finds it; an agent in a sandbox needs only that folder. Each connection names the agent's folder: the tools see the projects open in
 * the IDE there, name files relative to it and write only inside it ({@link Workspace#ofOpenProjects(File)}).
 * {@code -J-Ddukehoffmcp.ide.server=false} turns it off, {@code true} starts it also headless (tests).
 */
@OnStart
public final class IdeServer implements Runnable {

    static final String SWITCH = "dukehoffmcp.ide.server";
    private static final Logger LOG = Logger.getLogger(IdeServer.class.getName());
    private static final Map<String, List<Tool>> WORKSPACES = new ConcurrentHashMap<>();

    @Override
    public void run() {
        String setting = System.getProperty(SWITCH);
        if ("false".equals(setting)) {
            return;
        }
        if (!"true".equals(setting) && (GraphicsEnvironment.isHeadless() || insideVsCode())) {
            return;
        }
        // the IDE should not wait for us
        RequestProcessor.getDefault().post(IdeServer::start);
    }

    /** The NetBeans of the Apache NetBeans extension for VS Code, which has its own way in ({@link EmbeddedServer}). */
    private static boolean insideVsCode() {
        ModuleInfo nbcode = Modules.getDefault().findCodeNameBase("org.netbeans.modules.nbcode.integration");
        return nbcode != null && nbcode.isEnabled();
    }

    private static void start() {
        try {
            Path registry = registry();
            Files.createDirectories(registry);
            ownerOnly(registry);
            removeEnded(registry);
            // next to the entry, so a sandboxed agent needs only the registry folder (not /tmp); in a
            // temp folder if that path is too long for a Unix domain socket (~100 bytes)
            Path socket = registry.resolve(ProcessHandle.current().pid() + ".sock");
            Path tempDir = null;
            if (socket.toString().getBytes(StandardCharsets.UTF_8).length > SOCKET_PATH_MAX) {
                tempDir = Files.createTempDirectory("dukehoff-mcp-ide-");
                ownerOnly(tempDir);
                socket = tempDir.resolve("ide.sock");
            }
            Daemon daemon = new Daemon(socket, null, IdeServer::toolsFor, McpArgsProcessor.version(), true);
            daemon.setContext(Lookup.getDefault());
            Thread t = new Thread(() -> {
                try {
                    daemon.run();
                } catch (IOException ex) {
                    LOG.log(Level.WARNING, "dukehoff-mcp: the IDE server stopped", ex);
                }
            }, "dukehoff-mcp IDE server");
            t.setDaemon(true);
            t.start();
            if (!daemon.awaitListening(30)) {
                LOG.log(Level.WARNING, "dukehoff-mcp: the IDE server did not listen on {0} within 30 s", socket);
                return;
            }
            Path entry = announce(socket);
            Path socketFile = socket;
            Path socketDir = tempDir;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    Files.deleteIfExists(entry);
                    Files.deleteIfExists(socketFile);
                    if (socketDir != null) {
                        Files.deleteIfExists(socketDir);
                    }
                } catch (IOException ex) {
                    // the next IDE to start removes it; the launcher skips entries nobody answers on
                }
            }));
            LOG.log(Level.INFO, "dukehoff-mcp: IDE server on {0}, announced in {1}", new Object[]{socket, entry});
        } catch (IOException | InterruptedException ex) {
            LOG.log(Level.WARNING, "dukehoff-mcp: the IDE server did not start", ex);
        }
    }

    /** Where running IDEs announce their servers. */
    static Path registry() {
        String dir = System.getenv("DUKEHOFF_MCP_IDE_DIR");
        return dir != null && !dir.isBlank() ? Paths.get(dir) : Paths.get(System.getProperty("user.home"), ".dukehoff-mcp", "ide");
    }

    /** Unix domain socket paths: 104 bytes on macOS, 108 on Linux, with the terminating NUL. */
    private static final int SOCKET_PATH_MAX = 100;

    /** Read and written by the user only (the socket is reachable through it); not on Windows. */
    private static void ownerOnly(Path dir) throws IOException {
        try {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException ex) {
            // Windows: the user's profile and temp directory are private
        }
    }

    /**
     * Removes the entries (and sockets) of IDEs that have ended without removing them. Done here, by the
     * IDE, not by the launcher: an agent in a sandbox with its own process namespace cannot tell
     * whether an IDE runs, and may not be allowed to write here.
     */
    static void removeEnded(Path registry) {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(registry, "*.json")) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                long pid;
                try {
                    pid = Long.parseLong(name.substring(0, name.length() - ".json".length()));
                } catch (NumberFormatException ex) {
                    continue;
                }
                if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
                    continue;
                }
                Files.deleteIfExists(entry);
                Files.deleteIfExists(registry.resolve(pid + ".sock"));
            }
        } catch (IOException ex) {
            LOG.log(Level.INFO, "dukehoff-mcp: could not clean up " + registry, ex);
        }
    }

    private static Path announce(Path socket) throws IOException {
        Path dir = registry();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("socket", socket.toString());
        m.put("pid", ProcessHandle.current().pid());
        File userdir = Places.getUserDirectory();
        m.put("userdir", userdir == null ? null : userdir.getPath());
        m.put("version", McpArgsProcessor.version());
        Path entry = dir.resolve(ProcessHandle.current().pid() + ".json");
        Files.writeString(entry, JSONValue.toJSONString(m) + "\n", StandardCharsets.UTF_8);
        return entry;
    }

    /** The tools for an agent's folder; all open projects without one. */
    static List<Tool> toolsFor(String folder) {
        File dir = folder == null ? null : FileUtil.normalizeFile(new File(folder));
        if (dir != null && !dir.isDirectory()) {
            throw new IllegalArgumentException("not a folder: " + folder);
        }
        return WORKSPACES.computeIfAbsent(dir == null ? "" : dir.getPath(),
                k -> McpArgsProcessor.tools(Workspace.ofOpenProjects(dir)));
    }
}
