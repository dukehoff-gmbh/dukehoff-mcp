/*
 * Copyright 2026 Dukehoff GmbH
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dukehoff.mcp.launcher;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Starts the headless NetBeans of the dukehoff-mcp runtime as an MCP server on stdio: the same as
 * {@code bin/dukehoff-mcp}, but in Java, so it runs on Windows too (NetBeans' Windows launchers are GUI
 * programs without stdin and stdout). Builds the command line NetBeans' {@code nbexec} would build,
 * starts the NetBeans JVM with this process's stdin, stdout and stderr, and exits with its status.
 * <p>
 * Usage: {@code java -jar <runtime>/bin/dukehoff-mcp.jar [options] [workspace] [-- extra NetBeans arguments]},
 * options as in {@code bin/dukehoff-mcp}.
 */
public final class Launcher {

    static final String USAGE = String.join("\n",
            "Starts a headless NetBeans as MCP server (stdio) for Java code intelligence. By default the",
            "server keeps running between sessions (one per workspace, stopped after 30 minutes without a",
            "client); this launcher connects to it, starting it when needed.",
            "",
            "Usage: java -jar dukehoff-mcp.jar [options] [workspace] [-- extra NetBeans launcher arguments]",
            "  workspace          project or folder of projects; default: current directory",
            "  --jdk <path>       JDK 17+ home; default: $DUKEHOFF_MCP_JDK, $JAVA_HOME, the JDK running this",
            "  --data <dir>       where per-workspace userdirs and index caches are kept; default:",
            "                     $DUKEHOFF_MCP_DATA, %LOCALAPPDATA%\\dukehoff-mcp (Windows), ${XDG_CACHE_HOME:-~/.cache}/dukehoff-mcp",
            "  --locale <lang>    language of compiler messages; default: en",
            "  --check            only print what would be started and whether a server runs, then exit",
            "  --stop             stop the workspace's running server, then exit",
            "  --no-daemon        run a server only for this session, ending with it",
            "  --tools <list>     tools to offer: comma separated tool names, groups (core, refactor,",
            "                     migrate, debug, all) and presets (copilot, claude-code); default: all.",
            "                     With --config, the printed registration carries the selection",
            "  --config <client>  print the configuration that registers this server with a client",
            "                     (claude, copilot, vscode, cursor, codex, omp), then exit",
            "  --version          print the version, then exit",
            "",
            "When it starts the server, stdout carries the MCP protocol, so this launcher then only writes to stderr.");

    static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    private static final String EXE = WINDOWS ? ".exe" : "";

    private Launcher() {
    }

    public static void main(String[] args) {
        int status;
        try {
            status = run(args, Paths.get(Launcher.class.getProtectionDomain().getCodeSource().getLocation().toURI()));
        } catch (UsageException ex) {
            System.err.println("dukehoff-mcp: " + ex.getMessage());
            status = 2;
        } catch (IOException | URISyntaxException ex) {
            System.err.println("dukehoff-mcp: " + ex);
            status = 2;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            status = 2;
        }
        System.exit(status);
    }

    /** A problem the user can fix; the message says how. */
    static final class UsageException extends Exception {

        private static final long serialVersionUID = 1L;

        UsageException(String message) {
            super(message);
        }
    }

    /**
     * Parsed command line.
     */
    static final class Options {
        Path jdk;
        Path data;
        String language = "en";
        boolean check;
        boolean stop;
        boolean noDaemon;
        String tools;
        String config;
        boolean version;
        Path workspace;
        final List<String> extra = new ArrayList<>();
    }

    static Options parse(String[] args, Map<String, String> env) throws UsageException {
        Options o = new Options();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--jdk" -> o.jdk = Paths.get(value(args, ++i, a));
                case "--data" -> o.data = Paths.get(value(args, ++i, a));
                case "--locale" -> o.language = value(args, ++i, a);
                case "--check" -> o.check = true;
                case "--stop" -> o.stop = true;
                case "--no-daemon" -> o.noDaemon = true;
                case "--tools" -> o.tools = value(args, ++i, a);
                case "--config" -> o.config = value(args, ++i, "--config needs a client: claude, copilot, vscode, cursor, codex or omp;");
                case "--version" -> o.version = true;
                case "-h", "--help" -> throw new UsageException("\n" + USAGE);
                case "--" -> {
                    o.extra.addAll(List.of(args).subList(i + 1, args.length));
                    i = args.length;
                }
                default -> {
                    if (a.startsWith("-")) {
                        throw new UsageException("unknown option " + a + " (use -- to pass options to NetBeans)");
                    }
                    if (o.workspace != null) {
                        throw new UsageException("only one workspace can be given");
                    }
                    o.workspace = Paths.get(a);
                }
            }
        }
        if (o.jdk == null && env.get("DUKEHOFF_MCP_JDK") != null) {
            o.jdk = Paths.get(env.get("DUKEHOFF_MCP_JDK"));
        }
        if (o.data == null) {
            o.data = defaultData(env);
        }
        return o;
    }

    private static String value(String[] args, int i, String option) throws UsageException {
        if (i >= args.length) {
            throw new UsageException(option.endsWith(";") ? option.substring(0, option.length() - 1) : option + " needs a value");
        }
        return args[i];
    }

    static Path defaultData(Map<String, String> env) {
        if (env.get("DUKEHOFF_MCP_DATA") != null) {
            return Paths.get(env.get("DUKEHOFF_MCP_DATA"));
        }
        if (WINDOWS && env.get("LOCALAPPDATA") != null) {
            return Paths.get(env.get("LOCALAPPDATA"), "dukehoff-mcp");
        }
        String cache = env.get("XDG_CACHE_HOME");
        return cache != null ? Paths.get(cache, "dukehoff-mcp") : Paths.get(System.getProperty("user.home"), ".cache", "dukehoff-mcp");
    }

    static int run(String[] args, Path jar) throws UsageException, IOException, InterruptedException {
        Map<String, String> env = System.getenv();
        Options o = parse(args, env);
        Path home = jar.toAbsolutePath().getParent().getParent();
        if (o.version) {
            Path version = home.resolve("VERSION");
            System.out.println(Files.exists(version) ? Files.readString(version).strip() : "dukehoff-mcp (version unknown)");
            return 0;
        }
        Path jdk = findJdk(o.jdk, env);
        if (o.config != null) {
            System.out.print(config(o.config, jdk.resolve("bin").resolve("java" + EXE), jar.toAbsolutePath(), o.tools));
            return 0;
        }
        if (!Files.isDirectory(home.resolve("platform").resolve("lib"))) {
            throw new UsageException("no dukehoff-mcp runtime around " + jar + " (expected <runtime>/bin/dukehoff-mcp.jar)");
        }
        Path workspace = (o.workspace != null ? o.workspace : Paths.get("")).toAbsolutePath().normalize();
        if (!Files.isDirectory(workspace)) {
            throw new UsageException("workspace " + workspace + " is not a directory");
        }
        // one userdir and index cache per workspace, named like bin/dukehoff-mcp names them (POSIX cksum of
        // the path), so both launchers share them
        Path base = o.data.resolve(workspace.getFileName() + "-" + cksum(workspace.toString().getBytes(StandardCharsets.UTF_8)));
        PrintStream err = System.err;
        if (o.check) {
            err.printf("workspace: %s%nruntime:   %s%njdk:       %s%ndata:      %s%n", workspace, home, jdk, slot(base, false));
            for (int n = 1; Files.isDirectory(slotDir(base, n)); n++) {
                Optional<Proxy.Connection> c = Proxy.tryConnect(slotDir(base, n));
                if (c.isPresent()) {
                    err.printf("server:    running in %s (version %s, pid %d)%n", slotDir(base, n),
                            c.get().hello().version(), c.get().hello().pid());
                    c.get().channel().close();
                }
            }
            return 0;
        }
        if (o.stop) {
            int stopped = 0;
            Proxy proxy = new Proxy(err);
            for (int n = 1; Files.isDirectory(slotDir(base, n)); n++) {
                Optional<Proxy.Connection> c = Proxy.tryConnect(slotDir(base, n));
                if (c.isPresent()) {
                    proxy.stop(c.get());
                    err.println("dukehoff-mcp: stopped the server in " + slotDir(base, n));
                    stopped++;
                }
            }
            if (stopped == 0) {
                err.println("dukehoff-mcp: no server running for " + workspace);
            }
            return 0;
        }
        if (!o.noDaemon) {
            err.println("dukehoff-mcp: workspace " + workspace + ", runtime " + home + ", JDK " + jdk + ", data " + base);
            return new Proxy(err).run(base, Proxy.moduleVersion(home), dir -> {
                try {
                    return daemonCommand(command(home, jdk, prepare(dir), dir.resolve("cache"), workspace, o.language, o.extra));
                } catch (IOException ex) {
                    throw new UncheckedIOException(ex);
                }
            }, o.tools);
        }
        Path dir = slot(base, true);
        List<String> command = command(home, jdk, prepare(dir), dir.resolve("cache"), workspace, o.language, o.extra);
        if (o.tools != null) {
            command.add("--mcp-tools");
            command.add(o.tools);
        }
        err.println("dukehoff-mcp: workspace " + workspace + ", runtime " + home + ", JDK " + jdk + ", data " + dir);
        Process netbeans = new ProcessBuilder(command).inheritIO().start();
        Runtime.getRuntime().addShutdownHook(new Thread(netbeans::destroy));
        return netbeans.waitFor();
    }

    /** Creates a slot's userdir and cache; returns the userdir. */
    private static Path prepare(Path dir) throws IOException {
        Path userdir = dir.resolve("userdir");
        Files.createDirectories(userdir.resolve("var").resolve("log"));
        Files.createDirectories(dir.resolve("cache"));
        // otherwise a fresh userdir offers to import the settings of an installed NetBeans;
        // that dialog fails headless and NetBeans exits before the server starts
        Path imported = userdir.resolve("var").resolve("imported");
        if (!Files.exists(imported)) {
            Files.createFile(imported);
        }
        return userdir;
    }

    /**
     * The command line for a daemon, which outlives this launcher and the client's terminal: on Windows
     * {@code javaw} (no console, so closing the terminal does not end it), elsewhere under
     * {@code nohup} (a closed terminal's SIGHUP would end the JVM).
     */
    static List<String> daemonCommand(List<String> command) {
        List<String> cmd = new ArrayList<>(command);
        if (WINDOWS) {
            Path javaw = Paths.get(cmd.get(0)).resolveSibling("javaw.exe");
            if (Files.isRegularFile(javaw)) {
                cmd.set(0, javaw.toString());
            }
        } else if (Files.isExecutable(Paths.get("/usr/bin/nohup"))) {
            cmd.add(0, "/usr/bin/nohup");
        }
        return cmd;
    }

    /** Slot {@code n} (1-based) of a workspace's data directory. */
    static Path slotDir(Path base, int n) {
        return n == 1 ? base : base.resolveSibling(base.getFileName() + "-" + n);
    }

    /** In a data slot: the pid of the launcher using it, alive as long as its NetBeans runs. */
    static final String PID_FILE = "dukehoff-mcp.pid";

    /**
     * The first data slot ({@code base}, then {@code base-2}, {@code base-3}, ...) no running launcher
     * uses. A NetBeans started on a userdir in use hands its command line to the running instance,
     * which then serves a second client over its CLI socket: the answers never arrived (Copilot CLI
     * switching sessions), and the instance exits with its first client. So a second client on the
     * same workspace gets its own instance, at the cost of indexing that slot once.
     *
     * @param claim write this process's pid into the slot; false only to report it
     */
    static Path slot(Path base, boolean claim) throws IOException {
        for (int n = 1; ; n++) {
            Path dir = slotDir(base, n);
            Path pidFile = dir.resolve(PID_FILE);
            if (inUse(pidFile)) {
                continue;
            }
            if (!claim) {
                return dir;
            }
            Files.createDirectories(dir);
            Files.deleteIfExists(pidFile);
            try {
                Files.writeString(pidFile, Long.toString(ProcessHandle.current().pid()), StandardOpenOption.CREATE_NEW);
                return dir;
            } catch (FileAlreadyExistsException ex) {
                // another launcher claimed it in the meantime; try the next slot
            }
        }
    }

    static boolean inUse(Path pidFile) {
        try {
            long pid = Long.parseLong(Files.readString(pidFile).strip());
            return pid != ProcessHandle.current().pid() && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
        } catch (IOException | NumberFormatException ex) {
            return false;
        }
    }

    /**
     * The command line {@code nbexec} builds: JVM options from {@code etc/dukehoffmcp.conf}, the platform's
     * boot classpath, the clusters of {@code etc/dukehoffmcp.clusters}, then {@code org.netbeans.Main}.
     */
    static List<String> command(Path home, Path jdk, Path userdir, Path cachedir, Path workspace, String language,
            List<String> extra) throws IOException {
        List<String> jvm = new ArrayList<>();
        List<String> main = new ArrayList<>();
        // the caller's options after the configuration's, so they win; -J goes to the JVM, as with nbexec
        List<String> options = new ArrayList<>(defaultOptions(home.resolve("etc").resolve("dukehoffmcp.conf")));
        options.addAll(extra);
        for (String option : options) {
            if (option.startsWith("-J")) {
                jvm.add(option.substring(2));
            } else {
                main.add(option);
            }
        }
        Path platform = home.resolve("platform");
        List<String> clusters = new ArrayList<>();
        for (String line : Files.readAllLines(home.resolve("etc").resolve("dukehoffmcp.clusters"))) {
            String c = line.strip();
            if (!c.isEmpty() && !c.startsWith("#") && !c.equals("platform")) {
                clusters.add(home.resolve(c).toString());
            }
        }
        List<String> cmd = new ArrayList<>();
        cmd.add(jdk.resolve("bin").resolve("java" + EXE).toString());
        cmd.add("-Djdk.home=" + jdk);
        cmd.add("-classpath");
        cmd.add(classpath(platform.resolve("lib")));
        cmd.add("-Dnetbeans.dirs=" + String.join(java.io.File.pathSeparator, clusters));
        cmd.add("-Dnetbeans.home=" + platform);
        cmd.addAll(jvm);
        cmd.add("-Djava.awt.headless=true");
        cmd.add("-Duser.language=" + language);
        cmd.add("-Duser.country=");
        cmd.add("-XX:+HeapDumpOnOutOfMemoryError");
        cmd.add("-XX:HeapDumpPath=" + userdir.resolve("var").resolve("log").resolve("heapdump.hprof"));
        cmd.add("org.netbeans.Main");
        cmd.add("--cachedir");
        cmd.add(cachedir.toString());
        cmd.add("--userdir");
        cmd.add(userdir.toString());
        cmd.addAll(main);
        cmd.add("--nogui");
        cmd.add("--nosplash");
        cmd.add("--start-mcp-server");
        cmd.add("--mcp-workspace");
        cmd.add(workspace.toString());
        return cmd;
    }

    /** The words of {@code default_options="..."} in a NetBeans launcher configuration. */
    static List<String> defaultOptions(Path conf) throws IOException {
        Matcher m = Pattern.compile("(?m)^default_options=\"(.*)\"\\s*$").matcher(Files.readString(conf));
        if (!m.find()) {
            throw new IOException("no default_options in " + conf);
        }
        return List.of(m.group(1).trim().split("\\s+"));
    }

    private static String classpath(Path lib) throws IOException {
        List<Path> jars = new ArrayList<>();
        for (Path dir : List.of(lib, lib.resolve("locale"))) {
            if (Files.isDirectory(dir)) {
                try (Stream<Path> s = Files.list(dir)) {
                    s.filter(p -> p.toString().endsWith(".jar")).sorted().forEach(jars::add);
                }
            }
        }
        return jars.stream().map(Path::toString).collect(Collectors.joining(java.io.File.pathSeparator));
    }

    /**
     * A JDK 17+ home: the one given, else {@code $JAVA_HOME}, else the JDK running this launcher. A JDK
     * needs a release file and javac (a JRE or the macOS {@code /usr} stubs do not do).
     */
    static Path findJdk(Path given, Map<String, String> env) throws UsageException {
        if (given != null) {
            if (!jdkOk(given)) {
                throw new UsageException(given + " is not a JDK 17+ home (needs release, bin/java, bin/javac)");
            }
            return given.toAbsolutePath();
        }
        for (String candidate : new String[] {env.get("JAVA_HOME"), System.getProperty("java.home")}) {
            if (candidate != null && jdkOk(Paths.get(candidate))) {
                return Paths.get(candidate).toAbsolutePath();
            }
        }
        throw new UsageException("no JDK 17+ found in JAVA_HOME or running this launcher; use --jdk or DUKEHOFF_MCP_JDK");
    }

    static boolean jdkOk(Path home) {
        Path release = home.resolve("release");
        if (!Files.isRegularFile(release) || !Files.isRegularFile(home.resolve("bin").resolve("java" + EXE))
                || !Files.isRegularFile(home.resolve("bin").resolve("javac" + EXE))) {
            return false;
        }
        try {
            Matcher m = Pattern.compile("(?m)^JAVA_VERSION=\"(\\d+)").matcher(Files.readString(release));
            return m.find() && Integer.parseInt(m.group(1)) >= 17;
        } catch (IOException | NumberFormatException ex) {
            return false;
        }
    }

    /** The client configuration, starting the server with {@code java -jar}: no shell, no cmd /c. */
    static String config(String client, Path java, Path jar) throws UsageException {
        return config(client, java, jar, null);
    }

    /**
     * The configuration that registers this server with a client.
     *
     * @param tools the registration's tool selection ({@code --tools}), or null for all tools
     */
    static String config(String client, Path java, Path jar, String tools) throws UsageException {
        String j = json(java.toString());
        String a = json(jar.toString());
        String t = tools == null ? "" : ", \"--tools\", \"" + json(tools) + "\"";
        String cli = tools == null ? "" : " --tools " + tools;
        return switch (client) {
            case "claude" -> "# Claude Code, for all your projects:\n"
                    + "claude mcp add --scope user java -- \"" + java + "\" -jar \"" + jar + "\"" + cli + "\n"
                    + "# or, shared with the team, .mcp.json in the project:\n"
                    + "{ \"mcpServers\": { \"java\": { \"command\": \"" + j + "\", \"args\": [\"-jar\", \"" + a + "\"" + t + "] } } }\n"
                    + "# the first start indexes the project; allow time for it (\"env\" in ~/.claude/settings.json):\n"
                    + "MCP_TIMEOUT=120000 MCP_TOOL_TIMEOUT=600000\n";
            case "vscode" -> "// VS Code (GitHub Copilot agent mode): .vscode/mcp.json in the project\n"
                    + "{ \"servers\": { \"java\": { \"type\": \"stdio\", \"command\": \"" + j + "\", \"args\": [\"-jar\", \""
                    + a + "\"" + t + ", \"${workspaceFolder}\"] } } }\n";
            case "cursor", "omp" -> "// " + client + ": ." + client + "/mcp.json in the project\n"
                    + "{ \"mcpServers\": { \"java\": { \"command\": \"" + j + "\", \"args\": [\"-jar\", \"" + a + "\"" + t + "] } } }\n";
            case "copilot" -> "// GitHub Copilot CLI: ~/.copilot/mcp-config.json (Windows: %USERPROFILE%\\.copilot\\mcp-config.json);\n"
                    + "// merge into an existing file. Start copilot in the project directory.\n"
                    + "{ \"mcpServers\": { \"java\": { \"type\": \"local\", \"command\": \"" + j + "\", \"args\": [\"-jar\", \""
                    + a + "\"" + t + "], \"tools\": [\"*\"] } } }\n";
            case "codex" -> "# Codex CLI: ~/.codex/config.toml\n[mcp_servers.java]\ncommand = \"" + j
                    + "\"\nargs = [\"-jar\", \"" + a + "\"" + t + "]\nstartup_timeout_sec = 120\n";
            default -> throw new UsageException("unknown client " + client + " (claude, copilot, vscode, cursor, codex, omp)");
        };
    }

    private static String json(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** POSIX {@code cksum}: CRC-32 (polynomial 0x04C11DB7, MSB first) over the bytes, then their length. */
    static long cksum(byte[] data) {
        int crc = 0;
        for (byte b : data) {
            crc = step(crc, b & 0xff);
        }
        for (long n = data.length; n != 0; n >>>= 8) {
            crc = step(crc, (int) (n & 0xff));
        }
        return ~crc & 0xffffffffL;
    }

    private static int step(int crc, int b) {
        crc ^= b << 24;
        for (int k = 0; k < 8; k++) {
            crc = (crc & 0x80000000) != 0 ? (crc << 1) ^ 0x04C11DB7 : crc << 1;
        }
        return crc;
    }
}
