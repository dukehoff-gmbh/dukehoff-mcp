/*
 * Copyright 2026 Dukehoff GmbH
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dukehoff.mcp;

import java.awt.GraphicsEnvironment;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
 * NetBeans of the VS Code extension) on an owner-only Unix domain socket, and announced in
 * {@code ~/.dukehoff-mcp/ide/<pid>.json} ({@code DUKEHOFF_MCP_IDE_DIR} elsewhere), where the launcher's
 * {@code --ide} finds it. Each connection names the agent's folder: the tools see the projects open in
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
            Path dir;
            try {
                // a short path: Unix domain sockets allow ~100 bytes
                dir = Files.createTempDirectory("dukehoff-mcp-ide-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            } catch (UnsupportedOperationException ex) {
                dir = Files.createTempDirectory("dukehoff-mcp-ide-"); // Windows: the user's temp directory is private
            }
            Path socket = dir.resolve("ide.sock");
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
            Path socketDir = dir;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    Files.deleteIfExists(entry);
                    Files.deleteIfExists(socket);
                    Files.deleteIfExists(socketDir);
                } catch (IOException ex) {
                    // the launcher skips entries of ended processes
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

    private static Path announce(Path socket) throws IOException {
        Path dir = registry();
        Files.createDirectories(dir);
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
