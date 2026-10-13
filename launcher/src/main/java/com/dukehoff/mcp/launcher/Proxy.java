/*
 * Copyright 2026 Dukehoff GmbH
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dukehoff.mcp.launcher;

import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Connects a client's stdio to the workspace's persistent server (daemon), starting it if needed.
 * One daemon per workspace keeps NetBeans and its index running between sessions; this process is
 * what the client starts and stops.
 * <p>
 * Per data slot ({@link Launcher#slotDir}): under the slot's start lock, use a daemon that answers
 * with this runtime's version; stop one of another version; skip a slot whose launcher is alive but
 * has no daemon (started with {@code --no-daemon}); otherwise start a daemon there.
 */
final class Proxy {

    static final String SOCKET = "daemon.sock";
    static final String START_LOCK = "daemon.lock";
    static final String LOG = "daemon.log";
    /** Unix domain socket paths are limited (macOS: 104 bytes); longer ones go to a per-user temp dir. */
    private static final int MAX_SOCKET_PATH = 100;
    private static final long START_TIMEOUT_MILLIS = TimeUnit.MINUTES.toMillis(3);
    private static final long STOP_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(60);
    private static final Pattern HELLO = Pattern.compile("\"(version|pid)\"\\s*:\\s*(\"[^\"]*\"|\\d+)");
    private static final Pattern MESSAGE = Pattern.compile("\"message\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    /** What a daemon says when a client connects. */
    record Hello(String version, long pid) {
    }

    /** An open connection to a daemon, after its hello. */
    record Connection(SocketChannel channel, Hello hello) {
    }

    private final PrintStream err;

    Proxy(PrintStream err) {
        this.err = err;
    }

    /**
     * Connects to (or starts) the daemon for the workspace and relays stdio until the client or the
     * daemon goes away.
     *
     * @param start the daemon's command line for a slot directory, without the socket option
     * @return the exit status
     */
    int run(Path base, String version, Function<Path, List<String>> start, String tools) throws IOException, InterruptedException {
        Connection connection = connect(base, version, start);
        return relay(connection, tools, null, System.in, new FileOutputStream(FileDescriptor.out));
    }

    /**
     * Relays stdio to a server that already listens on {@code socket} (inside the VS Code extension's
     * NetBeans) for a workspace folder; starts and stops nothing.
     */
    int runConnected(Path socket, String tools, String workspace) throws IOException, InterruptedException {
        Optional<Connection> connection = tryConnectSocket(socket);
        if (connection.isEmpty()) {
            err.println("dukehoff-mcp: no server answers on " + socket + " (is the Apache NetBeans extension running?)");
            return 1;
        }
        return relay(connection.get(), tools, workspace, System.in, new FileOutputStream(FileDescriptor.out));
    }

    Connection connect(Path base, String version, Function<Path, List<String>> start) throws IOException, InterruptedException {
        for (int n = 1; ; n++) {
            Path dir = Launcher.slotDir(base, n);
            Files.createDirectories(dir);
            try (FileChannel lockFile = FileChannel.open(dir.resolve(START_LOCK), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    FileLock lock = lockFile.lock()) {
                Optional<Connection> running = tryConnect(dir);
                if (running.isPresent()) {
                    Connection c = running.get();
                    if (version.equals(c.hello().version())) {
                        return c;
                    }
                    err.println("dukehoff-mcp: the server of " + dir + " is version " + c.hello().version() + ", this is "
                            + version + "; restarting it");
                    stop(c);
                } else if (Launcher.inUse(dir.resolve(Launcher.PID_FILE))) {
                    continue; // a server without a daemon (--no-daemon) uses this slot
                }
                List<String> command;
                try {
                    command = start.apply(dir);
                } catch (UncheckedIOException ex) {
                    throw ex.getCause();
                }
                return startDaemon(dir, command);
            }
        }
    }

    /** Asks a daemon to stop and waits until its process is gone. */
    void stop(Connection c) throws IOException, InterruptedException {
        try (SocketChannel channel = c.channel()) {
            write(channel, "{\"dukehoffmcp\":\"stop\"}\n");
        }
        Optional<ProcessHandle> process = ProcessHandle.of(c.hello().pid());
        if (process.isPresent()) {
            try {
                process.get().onExit().get(STOP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            } catch (ExecutionException | TimeoutException ex) {
                err.println("dukehoff-mcp: the old server (pid " + c.hello().pid() + ") did not stop; killing it");
                process.get().destroyForcibly();
                process.get().onExit().join();
            }
        }
    }

    private Connection startDaemon(Path dir, List<String> command) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(command);
        cmd.add("--mcp-socket");
        cmd.add(socket(dir).toString());
        Path log = dir.resolve(LOG);
        ProcessBuilder pb = new ProcessBuilder(cmd)
                .redirectInput(ProcessBuilder.Redirect.from(Launcher.WINDOWS ? Paths.get("NUL").toFile() : Paths.get("/dev/null").toFile()))
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
                .redirectErrorStream(true);
        err.println("dukehoff-mcp: starting the server for " + dir + " (log: " + log + ")");
        Process daemon = pb.start();
        Files.writeString(dir.resolve(Launcher.PID_FILE), Long.toString(daemon.pid()));
        long deadline = System.currentTimeMillis() + START_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            Optional<Connection> c = tryConnect(dir);
            if (c.isPresent()) {
                return c.get();
            }
            if (!daemon.isAlive()) {
                throw new IOException("the server exited with status " + daemon.exitValue() + " before accepting clients; see "
                        + log + " and " + dir.resolve("userdir").resolve("var").resolve("log").resolve("messages.log"));
            }
            Thread.sleep(200);
        }
        daemon.destroy();
        throw new IOException("the server did not accept clients within " + START_TIMEOUT_MILLIS / 1000 + " s; see " + log);
    }

    /** The daemon of a slot, if one answers. */
    static Optional<Connection> tryConnect(Path dir) {
        return tryConnectSocket(socket(dir));
    }

    /** The daemon listening on a socket, if one answers. */
    static Optional<Connection> tryConnectSocket(Path socket) {
        if (!Files.exists(socket)) {
            return Optional.empty();
        }
        SocketChannel channel = null;
        try {
            channel = SocketChannel.open(StandardProtocolFamily.UNIX);
            channel.connect(UnixDomainSocketAddress.of(socket));
            String line = readLine(channel);
            Hello hello = line == null ? null : parseHello(line);
            if (hello == null) {
                channel.close();
                return Optional.empty();
            }
            return Optional.of(new Connection(channel, hello));
        } catch (IOException ex) {
            if (channel != null) {
                try {
                    channel.close();
                } catch (IOException ignored) {
                    // nothing to do
                }
            }
            return Optional.empty();
        }
    }

    static Hello parseHello(String line) {
        if (!line.contains("\"dukehoffmcp\":\"daemon\"")) {
            return null;
        }
        String version = null;
        long pid = -1;
        Matcher m = HELLO.matcher(line);
        while (m.find()) {
            String value = m.group(2);
            if (m.group(1).equals("version")) {
                version = value.substring(1, value.length() - 1);
            } else {
                pid = Long.parseLong(value);
            }
        }
        return version == null ? null : new Hello(version, pid);
    }

    /**
     * Relays stdin to the daemon and the daemon's answers to stdout. Ends when the daemon closes the
     * connection: after the client closed stdin (status 0) or because it stopped (status 1).
     */
    int relay(Connection c, String tools, String workspace, InputStream in, OutputStream out) throws IOException, InterruptedException {
        SocketChannel channel = c.channel();
        // the registration's tool selection and, for a server of several workspaces, the folder; the
        // daemon answers connected, or error for unknown names or an unusable folder
        write(channel, "{\"dukehoffmcp\":\"connect\"" + (tools == null ? "" : ",\"tools\":\"" + json(tools) + "\"")
                + (workspace == null ? "" : ",\"workspace\":\"" + json(workspace) + "\"") + "}\n");
        String answer = readLine(channel);
        if (answer == null || !answer.contains("\"dukehoffmcp\":\"connected\"")) {
            Matcher m = MESSAGE.matcher(answer == null ? "" : answer);
            err.println("dukehoff-mcp: " + (m.find() ? unescape(m.group(1)) : "the server refused the connection: " + answer));
            channel.close();
            return 2;
        }
        AtomicBoolean clientDone = new AtomicBoolean();
        Thread upstream = new Thread(() -> {
            byte[] buffer = new byte[65536];
            try {
                int n;
                while ((n = in.read(buffer)) != -1) {
                    ByteBuffer b = ByteBuffer.wrap(buffer, 0, n);
                    while (b.hasRemaining()) {
                        channel.write(b);
                    }
                }
                clientDone.set(true);
                channel.shutdownOutput();
            } catch (IOException ex) {
                // the daemon went away; the downstream loop reports it
            }
        }, "dukehoff-mcp proxy stdin");
        upstream.setDaemon(true);
        upstream.start();
        ByteBuffer buffer = ByteBuffer.allocate(65536);
        try (channel) {
            while (channel.read(buffer) != -1) {
                buffer.flip();
                out.write(buffer.array(), 0, buffer.limit());
                out.flush();
                buffer.clear();
            }
        } catch (IOException ex) {
            // connection reset: treated like a close below
        }
        if (clientDone.get()) {
            return 0;
        }
        err.println("dukehoff-mcp: the server closed the connection (stopped or crashed); see the daemon log");
        return 1;
    }

    /** The socket of a slot: in the slot, or in a per-user temp directory when that path is too long. */
    static Path socket(Path dir) {
        Path inSlot = dir.resolve(SOCKET);
        if (inSlot.toString().getBytes(StandardCharsets.UTF_8).length <= MAX_SOCKET_PATH) {
            return inSlot;
        }
        Path tmp = Paths.get(System.getProperty("java.io.tmpdir"), "dukehoff-mcp-" + System.getProperty("user.name"));
        try {
            if (!Files.isDirectory(tmp)) {
                Files.createDirectories(tmp);
                try {
                    Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rwx------"));
                } catch (UnsupportedOperationException ex) {
                    // Windows: the user's temp directory is private already
                }
            }
        } catch (IOException ex) {
            // connecting or binding will fail and say so
        }
        return tmp.resolve(Long.toString(Launcher.cksum(dir.toString().getBytes(StandardCharsets.UTF_8))) + ".sock");
    }

    /**
     * Implementation and build version of the dukehoff-mcp module in a runtime, as the daemon reports its own
     * (McpArgsProcessor.version()).
     */
    static String moduleVersion(Path home) throws IOException {
        try (var dirs = Files.list(home)) {
            for (Path cluster : (Iterable<Path>) dirs::iterator) {
                Path jar = cluster.resolve("modules").resolve("com-dukehoff-mcp.jar");
                if (Files.isRegularFile(jar)) {
                    try (JarFile jf = new JarFile(jar.toFile())) {
                        Attributes a = jf.getManifest().getMainAttributes();
                        return a.getValue("OpenIDE-Module-Implementation-Version") + " " + a.getValue("OpenIDE-Module-Build-Version");
                    }
                }
            }
        }
        throw new IOException("no dukehoff-mcp module (modules/com-dukehoff-mcp.jar) in " + home);
    }

    /** Text as the content of a JSON string. */
    private static String json(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** A JSON string's content without its escapes (the daemon's json_simple also escapes '/'). */
    static String unescape(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\\' && i + 1 < s.length()) {
                char next = s.charAt(++i);
                sb.append(switch (next) {
                    case 'n' -> '\n';
                    case 't' -> '\t';
                    default -> next;
                });
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    private static void write(SocketChannel channel, String text) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
        while (b.hasRemaining()) {
            channel.write(b);
        }
    }

    /** One line, read byte by byte so nothing after it is consumed; null at EOF. */
    private static String readLine(SocketChannel channel) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        ByteBuffer one = ByteBuffer.allocate(1);
        while (true) {
            one.clear();
            if (channel.read(one) == -1) {
                return line.size() == 0 ? null : line.toString(StandardCharsets.UTF_8);
            }
            byte b = one.get(0);
            if (b == '\n') {
                return line.toString(StandardCharsets.UTF_8);
            }
            line.write(b);
        }
    }
}
