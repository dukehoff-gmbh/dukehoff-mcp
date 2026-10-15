/*
 * Copyright 2026 Dukehoff GmbH
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dukehoff.mcp.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class LauncherTest {

    @Test
    public void cksumMatchesPosixCksum() {
        // printf '%s' <string> | cksum, as bin/dukehoff-mcp computes the workspace id
        assertEquals(4294967295L, Launcher.cksum(new byte[0]));
        assertEquals(4006592941L, Launcher.cksum("/home/dev/project".getBytes(StandardCharsets.UTF_8)));
        assertEquals(1075549122L, Launcher.cksum("C:\\work\\my project".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void secondClientOnAWorkspaceGetsItsOwnSlot() throws Exception {
        // Copilot CLI switching sessions: a second NetBeans on the same userdir would join the first
        Path data = Files.createTempDirectory("dukehoffmcp-slots");
        Path base = data.resolve("ws-1");
        String alive = Long.toString(ProcessHandle.current().parent().orElseThrow().pid());
        Process done = new ProcessBuilder(Paths.get(System.getProperty("java.home"), "bin", "java").toString(), "-version")
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        done.waitFor();
        String dead = Long.toString(done.pid());

        assertEquals(base, Launcher.slot(base, true));
        assertEquals(Long.toString(ProcessHandle.current().pid()), Files.readString(base.resolve(Launcher.PID_FILE)));

        Files.writeString(base.resolve(Launcher.PID_FILE), alive);
        assertEquals("--check claims nothing", data.resolve("ws-1-2"), Launcher.slot(base, false));
        assertTrue(Files.notExists(data.resolve("ws-1-2")));
        assertEquals(data.resolve("ws-1-2"), Launcher.slot(base, true));
        Files.writeString(data.resolve("ws-1-2").resolve(Launcher.PID_FILE), alive);
        assertEquals(data.resolve("ws-1-3"), Launcher.slot(base, true));

        // a slot whose launcher is gone (exited or crashed) is reused
        Files.writeString(base.resolve(Launcher.PID_FILE), dead);
        assertEquals(base, Launcher.slot(base, true));
    }

    @Test
    public void extraJvmOptionsReachTheJvmAfterTheConfiguredOnes() throws Exception {
        // -J options are for the JVM (nbexec strips the -J); org.netbeans.Main does not know them
        Path home = Files.createTempDirectory("dukehoffmcp-home");
        Files.createDirectories(home.resolve("etc"));
        Files.createDirectories(home.resolve("platform").resolve("lib"));
        Files.writeString(home.resolve("etc").resolve("dukehoffmcp.conf"), "default_options=\"-J-Xmx2g --laf Metal\"\n");
        Files.writeString(home.resolve("etc").resolve("dukehoffmcp.clusters"), "platform\njava\n");
        List<String> cmd = Launcher.command(home, Paths.get("/jdk"), home.resolve("u"), home.resolve("c"), Paths.get("/ws"),
                "en", List.of("-J-Xmx4g", "-J-Ddukehoffmcp.wait.seconds=60", "--fontsize", "12"));
        int main = cmd.indexOf("org.netbeans.Main");
        assertTrue(cmd.toString(), cmd.indexOf("-Xmx2g") < cmd.indexOf("-Xmx4g") && cmd.indexOf("-Xmx4g") < main);
        assertTrue(cmd.toString(), cmd.indexOf("-Ddukehoffmcp.wait.seconds=60") < main);
        assertTrue(cmd.toString(), cmd.indexOf("--fontsize") > main && cmd.indexOf("--laf") > main);
        assertTrue(cmd.toString(), cmd.stream().noneMatch(a -> a.startsWith("-J")));
        assertEquals(List.of("--start-mcp-server", "--mcp-workspace", Paths.get("/ws").toString()),
                cmd.subList(cmd.size() - 3, cmd.size()));
    }

    @Test
    public void parsesOptionsWorkspaceAndExtraArguments() throws Exception {
        Launcher.Options o = Launcher.parse(new String[] {"--locale", "de", "ws", "--", "-J-Xmx4g", "--x"},
                Map.of("DUKEHOFF_MCP_DATA", "/data"));
        assertEquals("de", o.language);
        assertEquals(Paths.get("ws"), o.workspace);
        assertEquals(List.of("-J-Xmx4g", "--x"), o.extra);
        assertEquals(Paths.get("/data"), o.data);
    }

    @Test
    public void rejectsUnknownOptionsAndSecondWorkspace() {
        assertThrows(Launcher.UsageException.class, () -> Launcher.parse(new String[] {"--nope"}, Map.of()));
        assertThrows(Launcher.UsageException.class, () -> Launcher.parse(new String[] {"a", "b"}, Map.of()));
        assertThrows(Launcher.UsageException.class, () -> Launcher.parse(new String[] {"--jdk"}, Map.of()));
    }

    @Test
    public void copilotCliConfigHasTypeLocalAndAllTools() throws Exception {
        Path java = Paths.get("/jdk/bin/java");
        Path jar = Paths.get("/opt/dukehoffmcp/bin/dukehoff-mcp.jar");
        String copilot = Launcher.config("copilot", java, jar);
        assertTrue(copilot, copilot.contains("{ \"mcpServers\": { \"java\": { \"type\": \"local\", \"command\": \"" + json(java) + "\", "
                + "\"args\": [\"-jar\", \"" + json(jar) + "\"], \"tools\": [\"*\"] } } }"));
    }

    @Test
    public void configCarriesTheToolSelection() throws Exception {
        Path java = Paths.get("/jdk/bin/java");
        Path jar = Paths.get("/opt/dukehoffmcp/bin/dukehoff-mcp.jar");
        assertTrue(Launcher.config("copilot", java, jar, "copilot").contains(
                "\"args\": [\"-jar\", \"" + json(jar) + "\", \"--tools\", \"copilot\"], \"tools\": [\"*\"]"));
        assertTrue(Launcher.config("vscode", java, jar, "core,migrate").contains(
                "\"args\": [\"-jar\", \"" + json(jar) + "\", \"--tools\", \"core,migrate\", \"${workspaceFolder}\"]"));
        assertTrue(Launcher.config("claude", java, jar, "all").contains("-jar \"" + jar + "\" --tools all\n"));
        assertTrue(Launcher.config("codex", java, jar, "core").contains("args = [\"-jar\", \"" + json(jar) + "\", \"--tools\", \"core\"]"));
    }

    @Test
    public void configCarriesTheAllowedFolders() throws Exception {
        Path java = Paths.get("/jdk/bin/java");
        Path jar = Paths.get("/opt/dukehoffmcp/bin/dukehoff-mcp.jar");
        assertTrue(Launcher.config("vscode", java, jar, null, "../shared").contains(
                "\"args\": [\"-jar\", \"" + json(jar) + "\", \"--allow-writes\", \"../shared\", \"${workspaceFolder}\"]"));
        assertTrue(Launcher.config("claude", java, jar, "core", "../shared,/opt/lib").contains(
                "-jar \"" + jar + "\" --tools core --allow-writes \"../shared,/opt/lib\"\n"));
        assertTrue(Launcher.parse(new String[]{"--allow-writes", "../shared", "ws"}, Map.of()).allowWrites.equals("../shared"));
    }

    @Test
    public void configCarriesTheIde() throws Exception {
        Path java = Paths.get("/jdk/bin/java");
        Path jar = Paths.get("/opt/dukehoffmcp/bin/dukehoff-mcp.jar");
        assertTrue(Launcher.config("claude", java, jar, "core", null, true).contains("-jar \"" + jar + "\" --ide --tools core\n"));
        assertTrue(Launcher.config("vscode", java, jar, null, null, true).contains(
                "\"args\": [\"-jar\", \"" + json(jar) + "\", \"--ide\", \"${workspaceFolder}\"]"));
        assertTrue(Launcher.parse(new String[]{"--ide"}, Map.of()).ide);
    }

    /** A path as the configurations write it into JSON and TOML: on Windows its backslashes escaped. */
    private static String json(Path p) {
        return p.toString().replace("\\", "\\\\");
    }

    @Test
    public void configEscapesWindowsPathsForJson() throws Exception {
        String vscode = Launcher.config("vscode", Paths.get("C:\\Program Files\\Java\\jdk-21\\bin\\java.exe"),
                Paths.get("C:\\tools\\dukehoffmcp\\bin\\dukehoff-mcp.jar"));
        assertTrue(vscode, vscode.contains("\"command\": \"C:\\\\Program Files\\\\Java\\\\jdk-21\\\\bin\\\\java.exe\""));
        assertTrue(vscode, vscode.contains("\"args\": [\"-jar\", \"C:\\\\tools\\\\dukehoffmcp\\\\bin\\\\dukehoff-mcp.jar\", \"${workspaceFolder}\"]"));
    }
}
