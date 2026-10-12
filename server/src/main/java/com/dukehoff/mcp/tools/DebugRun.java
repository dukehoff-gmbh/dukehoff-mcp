/*
 * Copyright 2026 Dukehoff GmbH
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dukehoff.mcp.tools;

import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.ArrayReference;
import com.sun.jdi.Bootstrap;
import com.sun.jdi.ClassType;
import com.sun.jdi.Field;
import com.sun.jdi.IncompatibleThreadStateException;
import com.sun.jdi.LocalVariable;
import com.sun.jdi.Location;
import com.sun.jdi.Method;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.PrimitiveValue;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
import com.sun.jdi.StringReference;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Value;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.connect.IllegalConnectorArgumentsException;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.ClassPrepareEvent;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.ExceptionEvent;
import com.sun.jdi.event.LocatableEvent;
import com.sun.jdi.event.VMDeathEvent;
import com.sun.jdi.event.VMDisconnectEvent;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.EventRequestManager;
import com.sun.jdi.request.ExceptionRequest;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One debug run, start to finish in one call: starts a test with the JVM waiting for a debugger,
 * attaches (JDI), stops where asked (method entry, line, thrown exception), takes a snapshot (where,
 * the stack, locals of the top frames, expression values) and ends the test JVM and the build. No
 * state survives the call: agents handle one request better than a stepping session.
 */
final class DebugRun {

    /**
     * Where to stop: a method's first line, a source line, or where an exception is thrown; at the
     * {@code hit}-th time {@code when} holds there (null: always).
     */
    record StopAt(String className, String method, List<String> parameterTypes, int line, String exception, int hit, String when) {

        static StopAt method(String className, String method, List<String> parameterTypes, int hit, String when) {
            return new StopAt(className, method, parameterTypes, 0, null, hit, when);
        }

        static StopAt line(String className, int line, int hit, String when) {
            return new StopAt(className, null, null, line, null, hit, when);
        }

        static StopAt exception(String exception, int hit, String when) {
            return new StopAt(null, null, null, 0, exception, hit, when);
        }
    }

    /** {@code path op literal-or-path}, e.g. {@code srcLen == length}, {@code order.total() > 1000}, {@code s != null}. */
    private static final Pattern CONDITION = Pattern.compile("^\\s*(.+?)\\s*(==|!=|<=|>=|<|>)\\s*(.+?)\\s*$");
    private static final Pattern EQUALS = Pattern.compile("^\\s*(!?)\\s*(.+?)\\.equals\\((.+)\\)\\s*$");

    /** One frame: where, and its locals ({@code this} first) already formatted. */
    record Frame(String method, String file, int line, boolean project, Map<String, String> locals) {
    }

    /** What the run found; {@code frames} is empty if it never stopped. */
    record Snapshot(String reason, String thread, List<Frame> frames, Map<String, String> expressions, String output) {
    }

    private static final int MAX_VALUE = 300;
    private static final int LOCAL_FRAMES = 3;
    private static final long INVOKE_MILLIS = 3000;

    private final Process process;
    private final StringBuilder output = new StringBuilder();
    private final Predicate<String> projectClass;
    /** Why the condition could not be evaluated at the last hit, for the result when it never stopped. */
    private String conditionError;

    private DebugRun(Process process, Predicate<String> projectClass) {
        this.process = process;
        this.projectClass = projectClass;
    }

    /**
     * Runs the command, stops at {@code stop}, and returns the snapshot.
     *
     * @param projectClass whether a class (binary name) belongs to the project: only its frames are listed
     */
    static Snapshot run(TestCommand.DebugCommand command, StopAt stop, List<String> expressions, long timeoutMillis,
            Predicate<String> projectClass) throws IOException, InterruptedException {
        TestCommand c = command.command();
        ProcessBuilder pb = new ProcessBuilder(c.command()).directory(c.workDir()).redirectErrorStream(true)
                .redirectInput(ProcessBuilder.Redirect.from(new File(TestCommand.WINDOWS ? "NUL" : "/dev/null")));
        if ("maven".equals(c.kind()) || "ant".equals(c.kind())) {
            String var = "maven".equals(c.kind()) ? "MAVEN_OPTS" : "ANT_OPTS";
            String opts = pb.environment().getOrDefault(var, "");
            pb.environment().put(var, (opts + " -Duser.language=" + System.getProperty("user.language", "en")
                    + " -Duser.country=").strip());
        }
        DebugRun run = new DebugRun(pb.start(), projectClass);
        Thread reader = new Thread(() -> run.drain(run.process.getInputStream()), "dukehoff-mcp debug output");
        reader.setDaemon(true);
        reader.start();
        long deadline = System.currentTimeMillis() + timeoutMillis;
        try {
            VirtualMachine vm = run.attach(command.port(), deadline);
            if (vm == null) {
                return new Snapshot(run.process.isAlive() ? "timeout before the test started" : "the build ended before the test started",
                        null, List.of(), Map.of(), run.output());
            }
            try {
                return run.debug(vm, stop, expressions, deadline);
            } finally {
                try {
                    vm.exit(1);
                } catch (RuntimeException ex) {
                    // already gone
                }
            }
        } finally {
            run.process.descendants().forEach(ProcessHandle::destroyForcibly);
            run.process.destroyForcibly();
        }
    }

    private void drain(InputStream in) {
        byte[] buffer = new byte[8192];
        try (in) {
            int n;
            while ((n = in.read(buffer)) != -1) {
                synchronized (output) {
                    output.append(new String(buffer, 0, n, StandardCharsets.UTF_8));
                    if (output.length() > 200_000) {
                        output.delete(0, output.length() - 200_000);
                    }
                }
            }
        } catch (IOException ex) {
            // the process ended
        }
    }

    private String output() {
        synchronized (output) {
            return output.toString();
        }
    }

    /** Attaches once the test JVM listens; null if the build ended or the time ran out first. */
    private VirtualMachine attach(int port, long deadline) throws IOException, InterruptedException {
        AttachingConnector connector = Bootstrap.virtualMachineManager().attachingConnectors().stream()
                .filter(a -> a.name().equals("com.sun.jdi.SocketAttach")).findFirst()
                .orElseThrow(() -> new IOException("no JDI socket connector in this JDK"));
        Map<String, Connector.Argument> args = connector.defaultArguments();
        args.get("hostname").setValue("127.0.0.1");
        args.get("port").setValue(Integer.toString(port));
        // surefire does not pass the fork's "Listening for transport" line through: just try; the port is ours
        while (System.currentTimeMillis() < deadline && process.isAlive()) {
            try {
                return connector.attach(args);
            } catch (IOException ex) {
                // not yet accepting
            } catch (IllegalConnectorArgumentsException ex) {
                throw new IOException(ex);
            }
            Thread.sleep(200);
        }
        return null;
    }

    private Snapshot debug(VirtualMachine vm, StopAt stop, List<String> expressions, long deadline) throws InterruptedException {
        EventRequestManager erm = vm.eventRequestManager();
        if (stop.exception() != null) {
            // all exceptions: the named type may be loaded late, and subclasses count; filtered below
            ExceptionRequest er = erm.createExceptionRequest(null, true, true);
            er.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
            er.enable();
        } else {
            for (ReferenceType t : vm.classesByName(stop.className())) {
                breakpoints(erm, t, stop);
            }
            ClassPrepareRequest cpr = erm.createClassPrepareRequest();
            cpr.addClassFilter(stop.className());
            cpr.enable();
            ClassPrepareRequest nested = erm.createClassPrepareRequest();
            nested.addClassFilter(stop.className() + "$*");
            nested.enable();
        }
        vm.resume();
        int hits = 0;
        while (System.currentTimeMillis() < deadline) {
            EventSet events = vm.eventQueue().remove(Math.max(1, deadline - System.currentTimeMillis()));
            if (events == null) {
                break;
            }
            for (Event e : events) {
                if (e instanceof ClassPrepareEvent cpe) {
                    breakpoints(erm, cpe.referenceType(), stop);
                } else if (e instanceof BreakpointEvent be) {
                    if (holds(be.thread(), stop.when()) && ++hits >= Math.max(1, stop.hit())) {
                        return snapshot(be.thread(), "breakpoint at " + where(be.location()) + condition(stop), expressions);
                    }
                } else if (e instanceof ExceptionEvent ee) {
                    if (matches(ee.exception().referenceType(), stop.exception()) && projectFrame(ee.thread())
                            && holds(ee.thread(), stop.when()) && ++hits >= Math.max(1, stop.hit())) {
                        String message = message(ee.exception());
                        Location caught = ee.catchLocation();
                        return snapshot(ee.thread(), ee.exception().referenceType().name() + " thrown at " + where(ee.location())
                                + (caught == null ? " (uncaught)"
                                        : projectClass.test(caught.declaringType().name()) ? " (caught at " + where(caught) + ")"
                                        : " (not caught in the project's code)")
                                + condition(stop) + (message == null ? "" : ": " + message), expressions);
                    }
                } else if (e instanceof VMDeathEvent || e instanceof VMDisconnectEvent) {
                    return new Snapshot(notStopped("the test ended without stopping", hits, stop), null, List.of(), Map.of(), output());
                }
            }
            events.resume();
        }
        return new Snapshot(notStopped("timeout: did not stop in time", hits, stop), null, List.of(), Map.of(), output());
    }

    private static String condition(StopAt stop) {
        return stop.when() == null ? "" : ", where " + stop.when().strip();
    }

    private String notStopped(String why, int hits, StopAt stop) {
        StringBuilder sb = new StringBuilder(why);
        if (stop.when() != null) {
            sb.append("; '").append(stop.when().strip()).append("' held ").append(hits).append(hits == 1 ? " time" : " times");
            if (conditionError != null) {
                sb.append("; it could not be evaluated: ").append(conditionError);
            }
        } else if (stop.hit() > 1) {
            sb.append("; reached ").append(hits).append(hits == 1 ? " time" : " times").append(" of ").append(stop.hit());
        }
        return sb.toString();
    }

    /** Whether the condition holds in the innermost project frame of a stopped thread (no condition: true). */
    private boolean holds(ThreadReference thread, String when) {
        if (when == null || when.isBlank()) {
            return true;
        }
        String lhs;
        String op;
        String rhs;
        Matcher eq = EQUALS.matcher(when);
        Matcher m = CONDITION.matcher(when);
        if (eq.matches()) {
            // a.equals(b) as agents write it for strings: the same as a == b here
            lhs = eq.group(2);
            op = eq.group(1).isEmpty() ? "==" : "!=";
            rhs = eq.group(3);
        } else if (m.matches()) {
            lhs = m.group(1);
            op = m.group(2);
            rhs = m.group(3);
        } else {
            conditionError = "not of the form 'path op value' or 'path.equals(value)', e.g. srcLen == length";
            return false;
        }
        Map<String, Value> scope = topScope(thread);
        try (Invoker invoker = new Invoker(thread)) {
            Object left = literal(lhs);
            if (left == null) {
                left = resolve(lhs, scope, invoker);
            }
            Object right = literal(rhs);
            if (right == null) {
                right = resolve(rhs, scope, invoker);
            }
            if (left instanceof Problem p) {
                conditionError = p.message();
                return false;
            }
            if (right instanceof Problem p) {
                conditionError = p.message();
                return false;
            }
            return compare(plain(left), op, plain(right == NULL ? null : right));
        }
    }

    /** The locals (and {@code this}) of the innermost project frame. */
    private Map<String, Value> topScope(ThreadReference thread) {
        Map<String, Value> scope = new LinkedHashMap<>();
        try {
            for (StackFrame f : thread.frames()) {
                if (projectClass.test(f.location().declaringType().name())) {
                    if (f.thisObject() != null) {
                        scope.put("this", f.thisObject());
                    }
                    try {
                        for (LocalVariable v : f.visibleVariables()) {
                            scope.put(v.name(), f.getValue(v));
                        }
                    } catch (AbsentInformationException ex) {
                        // no locals: only this and its fields
                    }
                    break;
                }
            }
        } catch (IncompatibleThreadStateException ex) {
            // not suspended: an empty scope fails the condition
        }
        return scope;
    }

    /** A literal of the condition (number, "text", 'c', true, false, null), or null if it is a path. */
    private static Object literal(String text) {
        String t = text.strip();
        if (t.equals("null")) {
            return NULL;
        }
        if (t.equals("true") || t.equals("false")) {
            return Boolean.valueOf(t);
        }
        if (t.length() >= 2 && (t.startsWith("\"") && t.endsWith("\"") || t.startsWith("'") && t.endsWith("'"))) {
            return t.substring(1, t.length() - 1);
        }
        try {
            return Double.valueOf(t.replace("L", "").replace("f", "").replace("d", ""));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** A JDI value as a plain Java object for comparing: numbers as Double, chars and strings as String. */
    private static Object plain(Object v) {
        if (v instanceof com.sun.jdi.BooleanValue b) {
            return b.value();
        }
        if (v instanceof com.sun.jdi.CharValue c) {
            return String.valueOf(c.value());
        }
        if (v instanceof PrimitiveValue p) {
            return p.doubleValue();
        }
        if (v instanceof StringReference s) {
            return s.value();
        }
        return v; // an object reference (compared by identity), or null
    }

    private static boolean compare(Object left, String op, Object right) {
        if (op.equals("==") || op.equals("!=")) {
            boolean equal = left == null ? right == null : left.equals(right);
            return op.equals("==") == equal;
        }
        if (left instanceof Double a && right instanceof Double b) {
            return switch (op) {
                case "<" -> a < b;
                case "<=" -> a <= b;
                case ">" -> a > b;
                default -> a >= b;
            };
        }
        if (left instanceof String a && right instanceof String b) {
            int c = a.compareTo(b);
            return switch (op) {
                case "<" -> c < 0;
                case "<=" -> c <= 0;
                case ">" -> c > 0;
                default -> c >= 0;
            };
        }
        return false;
    }

    private static void breakpoints(EventRequestManager erm, ReferenceType type, StopAt stop) {
        List<Location> locations = new ArrayList<>();
        try {
            if (stop.method() != null) {
                for (Method m : type.methodsByName(stop.method())) {
                    if (stop.parameterTypes() == null || parametersMatch(m.argumentTypeNames(), stop.parameterTypes())) {
                        if (m.location() != null && m.location().lineNumber() > 0) {
                            locations.add(m.location());
                        }
                    }
                }
            } else {
                locations.addAll(type.locationsOfLine(stop.line()));
                if (locations.isEmpty()) {
                    locations.addAll(nextLineWithCode(type, stop.line()));
                }
            }
        } catch (AbsentInformationException ex) {
            return; // compiled without line numbers
        }
        for (Location l : locations) {
            BreakpointRequest br = erm.createBreakpointRequest(l);
            br.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
            br.enable();
        }
    }

    /**
     * A line without code (blank, comment, a lone brace) stops at the next line with code of the method
     * around it, like the IDE; the stop names the line it used.
     */
    private static List<Location> nextLineWithCode(ReferenceType type, int line) throws AbsentInformationException {
        // the innermost method around the line: a static initializer spans from the first to the
        // last static field and so seems to contain lines of nested classes' methods
        Method around = null;
        int aroundSpan = Integer.MAX_VALUE;
        for (Method m : type.methods()) {
            List<Location> all = m.allLineLocations();
            if (all.isEmpty()) {
                continue;
            }
            int first = all.stream().mapToInt(Location::lineNumber).min().getAsInt();
            int last = all.stream().mapToInt(Location::lineNumber).max().getAsInt();
            if (line >= first && line <= last && last - first < aroundSpan) {
                around = m;
                aroundSpan = last - first;
            }
        }
        if (around == null) {
            return List.of();
        }
        List<Location> all = around.allLineLocations();
        int next = all.stream().mapToInt(Location::lineNumber).filter(n -> n > line).min().orElse(-1);
        // a blank or comment line is followed by code soon; far away is another part of the class
        if (next < 0 || next - line > MAX_LINES_WITHOUT_CODE) {
            return List.of();
        }
        return all.stream().filter(l -> l.lineNumber() == next).toList();
    }

    /** How far a line without code may be from the next line with code it stops at. */
    private static final int MAX_LINES_WITHOUT_CODE = 10;

    private static boolean parametersMatch(List<String> actual, List<String> wanted) {
        if (actual.size() != wanted.size()) {
            return false;
        }
        for (int i = 0; i < actual.size(); i++) {
            // JDI names nested types by binary name (Flow$ConstructorData), signatures canonically
            if (!SymbolSpec.parameterMatches(wanted.get(i), actual.get(i).replace('$', '.'))) {
                return false;
            }
        }
        return true;
    }

    /** Whether an exception type is the wanted one or a subclass of it (simple or qualified name). */
    private static boolean matches(ReferenceType type, String wanted) {
        for (ReferenceType t = type; t != null; t = t instanceof ClassType ct ? ct.superclass() : null) {
            if (t.name().equals(wanted) || t.name().endsWith("." + wanted) || t.name().endsWith("$" + wanted)) {
                return true;
            }
        }
        return false;
    }

    /** Thrown within reach of the project's code (not deep inside the build or test framework only). */
    private boolean projectFrame(ThreadReference thread) {
        try {
            for (StackFrame f : thread.frames()) {
                if (projectClass.test(f.location().declaringType().name())) {
                    return true;
                }
            }
        } catch (IncompatibleThreadStateException ex) {
            return false;
        }
        return false;
    }

    private static String where(Location l) {
        String file;
        try {
            file = l.sourceName();
        } catch (AbsentInformationException ex) {
            file = "?";
        }
        return l.declaringType().name() + "." + l.method().name() + "(" + file + ":" + l.lineNumber() + ")";
    }

    private static String message(ObjectReference exception) {
        Field f = exception.referenceType().fieldByName("detailMessage");
        Value v = f == null ? null : exception.getValue(f);
        return v instanceof StringReference s ? truncate(s.value()) : null;
    }

    /** Raw values first (invoking toString resumes the thread and invalidates frames), then formatted. */
    private Snapshot snapshot(ThreadReference thread, String reason, List<String> expressions) {
        record RawFrame(String method, String file, int line, boolean project, Map<String, Value> locals) {
        }
        List<RawFrame> raw = new ArrayList<>();
        try {
            int withLocals = 0;
            for (StackFrame f : thread.frames()) {
                Location l = f.location();
                boolean project = projectClass.test(l.declaringType().name());
                Map<String, Value> locals = new LinkedHashMap<>();
                if (project && withLocals < LOCAL_FRAMES) {
                    withLocals++;
                    ObjectReference self = f.thisObject();
                    if (self != null) {
                        locals.put("this", self);
                    }
                    try {
                        for (LocalVariable v : f.visibleVariables()) {
                            locals.put(v.name(), f.getValue(v));
                        }
                    } catch (AbsentInformationException ex) {
                        locals.put("(locals)", null); // compiled without -g
                    }
                }
                String file;
                try {
                    file = l.sourceName();
                } catch (AbsentInformationException ex) {
                    file = "?";
                }
                raw.add(new RawFrame(l.declaringType().name() + "." + l.method().name(), file, l.lineNumber(), project, locals));
            }
        } catch (IncompatibleThreadStateException ex) {
            return new Snapshot(reason + " (thread state unavailable)", thread.name(), List.of(), Map.of(), output());
        }
        Map<String, String> evaluated = new LinkedHashMap<>();
        Map<String, Value> scope = raw.stream().filter(RawFrame::project).findFirst().map(RawFrame::locals).orElse(Map.of());
        try (Invoker invoker = new Invoker(thread)) {
            for (String e : expressions) {
                evaluated.put(e, evaluate(e, scope, invoker));
            }
            List<Frame> frames = new ArrayList<>();
            for (RawFrame f : raw) {
                Map<String, String> locals = new LinkedHashMap<>();
                for (Map.Entry<String, Value> v : f.locals().entrySet()) {
                    locals.put(v.getKey(), v.getKey().equals("(locals)") ? "not available (compiled without -g)" : format(v.getValue(), invoker));
                }
                frames.add(new Frame(f.method(), f.file(), f.line(), f.project(), locals));
            }
            return new Snapshot(reason, thread.name(), frames, evaluated, null);
        }
    }

    /** Why a path could not be evaluated. */
    private record Problem(String message) {
    }

    /** The literal null of a condition, told apart from "not a literal". */
    private static final Object NULL = new Object();

    /** An expression's value as shown, or "? reason". */
    private String evaluate(String expression, Map<String, Value> scope, Invoker invoker) {
        Object v = resolve(expression, scope, invoker);
        return v instanceof Problem p ? "? " + p.message() : format((Value) v, invoker);
    }

    /**
     * {@code a.b.size()}: a local (or {@code this}, or a field of {@code this}), then fields and
     * no-argument methods. The value (possibly null), or a {@link Problem}.
     */
    private static Object resolve(String expression, Map<String, Value> scope, Invoker invoker) {
        String[] parts = expression.strip().split("\\.");
        Value v;
        int i = 0;
        if (scope.containsKey(parts[0])) {
            v = scope.get(parts[0]);
            i = 1;
        } else if (scope.get("this") instanceof ObjectReference self && self.referenceType().fieldByName(parts[0]) != null) {
            v = self;
        } else {
            return new Problem("no local or field '" + parts[0] + "' in the stopped frame");
        }
        for (; i < parts.length; i++) {
            String p = parts[i];
            if (!(v instanceof ObjectReference o)) {
                return new Problem("'" + p + "' of " + (v == null ? "null" : v.type().name()));
            }
            if (p.endsWith("()")) {
                String name = p.substring(0, p.length() - 2);
                Method m = o.referenceType().methodsByName(name).stream().filter(x -> x.argumentTypeNames().isEmpty())
                        .findFirst().orElse(null);
                if (m == null) {
                    return new Problem("no method " + name + "() in " + o.referenceType().name());
                }
                v = invoker.invoke(o, m);
                if (v == Invoker.FAILED) {
                    return new Problem(p + " failed: " + invoker.lastError);
                }
            } else {
                Field f = o.referenceType().fieldByName(p);
                if (f == null) {
                    return new Problem("no field " + p + " in " + o.referenceType().name());
                }
                v = o.getValue(f);
            }
        }
        return v;
    }

    /** A value as an agent reads it: primitives and strings as is, arrays and collections shortened, objects by toString. */
    private String format(Value v, Invoker invoker) {
        if (v == null) {
            return "null";
        }
        if (v instanceof PrimitiveValue) {
            return v.toString();
        }
        if (v instanceof StringReference s) {
            return "\"" + truncate(s.value()) + "\"";
        }
        if (v instanceof ArrayReference a) {
            StringBuilder sb = new StringBuilder(a.type().name().replaceFirst("\\[\\]$", "[" + a.length() + "]")).append(" {");
            List<Value> values = a.getValues(0, Math.min(a.length(), 10));
            for (int i = 0; i < values.size(); i++) {
                sb.append(i == 0 ? "" : ", ").append(format(values.get(i), invoker));
            }
            return truncate(sb.append(a.length() > 10 ? ", ...}" : "}").toString());
        }
        ObjectReference o = (ObjectReference) v;
        String type = o.referenceType().name();
        Method toString = o.referenceType() instanceof ClassType ct ? ct.concreteMethodByName("toString", "()Ljava/lang/String;") : null;
        if (toString != null && !toString.declaringType().name().equals("java.lang.Object")) {
            Value s = invoker.invoke(o, toString);
            if (s instanceof StringReference str) {
                // text (StringBuilder, ...) in quotes like a String: an empty one stays visible
                boolean text = o.referenceType() instanceof ClassType ct
                        && ct.allInterfaces().stream().anyMatch(i -> i.name().equals("java.lang.CharSequence"));
                return simple(type) + " " + (text ? "\"" + truncate(str.value()) + "\"" : truncate(str.value()));
            }
        }
        // no toString of its own: the fields, one level deep
        StringBuilder sb = new StringBuilder(simple(type)).append(" {");
        int n = 0;
        for (Field f : o.referenceType().allFields()) {
            if (f.isStatic()) {
                continue;
            }
            Value fv = o.getValue(f);
            String shown = fv == null ? "null" : fv instanceof PrimitiveValue || fv instanceof StringReference
                    ? format(fv, invoker) : simple(fv.type().name());
            sb.append(n++ == 0 ? "" : ", ").append(f.name()).append('=').append(shown);
        }
        return truncate(sb.append('}').toString());
    }

    private static String simple(String type) {
        return type.substring(type.lastIndexOf('.') + 1);
    }

    private static String truncate(String s) {
        String one = s.replace("\n", "\\n");
        return one.length() > MAX_VALUE ? one.substring(0, MAX_VALUE) + "..." : one;
    }

    /** Invokes methods on the stopped thread, each with a time limit; after one hangs, no more. */
    private static final class Invoker implements AutoCloseable {

        static final Value FAILED = new FailedValue();
        private final ThreadReference thread;
        private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "dukehoff-mcp debug invoke");
            t.setDaemon(true);
            return t;
        });
        private boolean broken;
        String lastError;

        Invoker(ThreadReference thread) {
            this.thread = thread;
        }

        Value invoke(ObjectReference target, Method method) {
            if (broken) {
                lastError = "an earlier call did not return";
                return FAILED;
            }
            Future<Value> f = executor.submit(() -> target.invokeMethod(thread, method, List.of(), ObjectReference.INVOKE_SINGLE_THREADED));
            try {
                return f.get(INVOKE_MILLIS, TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException ex) {
                broken = true;
                lastError = "did not return within " + INVOKE_MILLIS + " ms";
            } catch (java.util.concurrent.ExecutionException ex) {
                lastError = String.valueOf(ex.getCause());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                lastError = "interrupted";
            }
            return FAILED;
        }

        @Override
        public void close() {
            executor.shutdownNow();
        }
    }

    /** Marks a failed invocation; never shown. */
    private static final class FailedValue implements Value {

        @Override
        public com.sun.jdi.Type type() {
            throw new UnsupportedOperationException();
        }

        @Override
        public VirtualMachine virtualMachine() {
            throw new UnsupportedOperationException();
        }
    }
}
