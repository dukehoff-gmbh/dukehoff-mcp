<!--

    Copyright 2026 Dukehoff GmbH
    SPDX-License-Identifier: Apache-2.0

-->
# Evaluation results

These measurements were taken while the module still lived in a fork of apache/netbeans as
`java/java.mcp.server`. The runs on NetBeans' `java/` folder used a checkout that contained the
module itself (29 callers in the first task; 24 in a plain apache/netbeans checkout, which the
checks in `nb-tasks/` now expect).

## 2026-09-30: 4 tasks × 2 modes × 3 repetitions

Claude Code 2.1.285 in headless mode (`claude -p`), model `claude-opus-5-5`, on the generated
fixture (`fixture.sh`), NetBeans index warmed up before the timed runs. `baseline` has Claude Code's
built-in tools only (Read, Edit, Write, Glob, Grep, restricted Bash); `netbeans` has the same plus this
server. Mean ± standard deviation over the repetitions; tokens include cache reads.

| task | mode | correct | turns | tokens | cost (USD) | seconds | tool calls | runs using MCP | top tools (all reps) |
|---|---|---|---|---|---|---|---|---|---|
| 01-find-callers | baseline | 3/3 | 9.7 ± 4.0 | 135064 | 0.174 ± 0.021 | 23 | 8.7 | - | Read 13, Bash 7, Write 3, Grep 2 |
| 01-find-callers | netbeans | 3/3 | 5.0 ± 0.0 | 112801 | 0.119 ± 0.001 | 20 | 4.0 | 3/3 | ToolSearch 3, nb.find_usages 3, Grep 3, Write 3 |
| 02-rename-method | baseline | 3/3 | 15.7 ± 3.1 | 196908 | 0.216 ± 0.018 | 35 | 14.7 | - | Read 17, Bash 14, Edit 12, Grep 1 |
| 02-rename-method | netbeans | 3/3 | 6.0 ± 1.7 | 124176 | 0.128 ± 0.017 | 21 | 5.0 | 3/3 | Bash 7, ToolSearch 3, nb.rename 3, Read 1 |
| 03-remove-deprecated | baseline | 3/3 | 13.7 ± 0.6 | 171310 | 0.176 ± 0.006 | 27 | 12.7 | - | Read 12, Edit 12, Bash 8, Grep 6 |
| 03-remove-deprecated | netbeans | 3/3 | 12.0 ± 5.3 | 209292 | 0.189 ± 0.037 | 34 | 11.0 | 3/3 | Bash 10, Edit 6, Read 5, ToolSearch 3 |
| 04-api-migration | baseline | 3/3 | 13.7 ± 3.2 | 172008 | 0.195 ± 0.019 | 28 | 12.7 | - | Edit 12, Bash 11, Read 10, Grep 5 |
| 04-api-migration | netbeans | 3/3 | 7.7 ± 1.2 | 142765 | 0.145 ± 0.010 | 22 | 6.7 | 3/3 | nb.apply_rule 6, Grep 6, Bash 5, ToolSearch 3 |

| mode | correct | mean turns | mean cost per run (USD) | total cost (USD) | MCP calls | MCP errors |
|---|---|---|---|---|---|---|
| baseline | 12/12 | 13.2 | 0.190 | 2.28 | 0 | 0 |
| netbeans | 12/12 | 7.7 | 0.145 | 1.74 | 20 | 0 |

Model: claude-opus-5-5

Observations (from the recorded tool calls):

* Correctness was the same: 24 of 24 runs passed their checks. The fixture's traps (overloads,
  same-named methods, names in comments and strings, `String` vs. `StringBuilder`) did not trip the
  baseline agent either, so on a project this small the gain is efficiency, not correctness.
* With the server, the agent used it in every run (20 calls, no errors) and needed 42% fewer turns
  and 24% less cost overall. The gain comes from single calls replacing read-and-edit loops:
  `find_usages` (task 1, 5 turns in every repetition vs. 6 to 14), `rename` (6 vs. 16 turns) and
  `apply_rule` (8 vs. 14 turns).
* Task 3 (remove a deprecated method) shows no gain and the largest spread. The agent chose
  different routes: twice `inline` (the deprecated method just returned `total()`), once
  `safe_delete` after migrating the callers by hand; each first explored with `find_usages`.
* Every run with the server starts with Claude Code's `ToolSearch`, which loads the MCP tool
  schemas on demand; that costs one turn per session.
* Small sample (3 repetitions, one fixture, one model). A larger real project, where text search
  actually gets things wrong, is the next step.

## 2026-09-30: NetBeans' own code base

Workspace: the `java/` folder of a NetBeans checkout (158 NetBeans module projects, 272 Java source
roots, about 8,900 Java files), started through `dukehoff-mcp` with an empty index, built with
`cluster.config=basic` (Java plus apisupport, which recognizes NetBeans module projects). The
repository was an APFS clone, so the refactoring did not touch the real checkout.

| Call | Seconds | Result | Checked against |
|---|---|---|---|
| start until ready (empty index) | 344 | 158 projects open and indexed | |
| `find_symbol` WhereUsedQueryConst | 2.4 | the enum in refactoring.java | |
| `find_symbol` JavaRefactoringPlugin#process* | 1.2 | the `processFiles` overloads | |
| `find_usages` JavaRefactoringUtils#getClasspathInfoFor | 12.8 | 29 usages | identical to a grep for qualified calls; plain grep for the name: 65 lines, most of them the same-named internal `RefactoringUtils` overloads |
| `find_implementations` JavaRefactoringPlugin | 13.0 | 32 subclasses | 27 direct ones by grep, plus 5 indirect via `PersistenceXmlRefactoring` |
| `outline` CasualDiff (6,558 lines) | 0.8 | first 25 members | |
| `find_usages` ElementHandle | 43.9 | 1,952 usages in 320 files | |
| `diagnostics` whole workspace | 1.0 | 1 error | NetBeans' Ant build fails on the same line (a test importing `javax.annotation.Resource`) |
| `apply_rule` search `String.length() == 0` | 15.2 | 248 lines in 163 files | 335 lines by grep; the rest are other types, strings and comments (e.g. the two in `java.mcp.server` are rule examples in string literals) |
| `rename` Json#string to text | 2.0 | 10 files changed | no call of the old name left; `diagnostics` afterwards unchanged |

Found on the way: a NetBeans installation with the ergonomics cluster (every full IDE; also the
`basic` build) disables this module until the IDE's UI asks for it, so `--start-mcp-server` was an
unknown option. `dukehoff-mcp` now starts NetBeans without the ergonomics cluster.

## 2026-09-30: agent with vs. without the server on NetBeans' `java/` folder

Tasks in `nb-tasks/`, run with `run.sh --repo <clone> --workspace java` (3 repetitions, `$3` cap per
session, model `claude-opus-5-5`). The server was started by each session (warm index, see below).

| Task | Mode | Correct | Turns | Tokens | Cost (USD) | Seconds |
|---|---|---|---|---|---|---|
| 01 find the 29 callers of `JavaRefactoringUtils#getClasspathInfoFor` (grep for the name: 65 lines) | baseline | 3/3 | 6.0 | 170,553 | 0.202 | 36 |
| | netbeans | 2/2 * | 5.0 | 134,240 | 0.160 | 47 |
| 02 find the 32 subclasses of `JavaRefactoringPlugin` (5 indirect) | baseline | 3/3 | 7.7 | 198,013 | 0.245 | 42 |
| | netbeans | 3/3 | 6.3 | 170,393 | 0.156 | 56 |
| 03 rename that method in 22 files, not the same-named internal ones | baseline | 3/3 | 51.3 | 357,632 | 0.559 | 111 |
| | netbeans | 3/3 | 9.0 | 247,328 | 0.220 | 141 |
| **all** | baseline | 9/9 | 21.7 | 242,066 | 0.335 | 63 |
| | netbeans | 8/8 | 7.0 | 190,205 | 0.181 | 86 |

\* One more session was correct too but is left out: the Mac went to idle sleep one second after the
server was started (power log: 543 s asleep, the same 543 s after which Claude Code reported the
connection timeout), so the agent worked with grep only and took 1,059 s. `run.sh` now keeps the
machine awake with `caffeinate -i`.

Observations:

* Every session was correct in both modes. On this code base, too, grep suffices for lookups: the
  names are distinctive, the baseline agent filters the same-named methods itself.
* With the server: 21% fewer tokens, 46% lower cost, 68% fewer turns. The difference is the
  refactoring: without the server the agent reads and edits each of the 22 files (51 turns); with it,
  one `rename` call (9 turns, 61% cheaper).
* Wall-clock time is still worse with the server (86 vs. 63 s), because each session starts NetBeans
  and a refactoring waits for the index check after startup (~100 s on this folder). Read tools no
  longer wait (see below); a server that keeps running between sessions would remove the rest.

An earlier run (1 repetition) failed the rename with the server: tool calls waited only 60 s for
the workspace, NetBeans needed 105 s after a restart (it re-checks 1,129 source roots, 80 s of it the
workspace's own 272 roots, with no changed file), and the agent gave up while waiting. Since then read
tools answer at once from the previous run's index during that check (their answer says so),
refactorings wait up to 5 minutes, and `run.sh` allows long tool calls (`MCP_TOOL_TIMEOUT`).

## 2026-10-02: the same tasks with Claude Sonnet 5

Like the run above, but model `claude-sonnet-5` (the strongest model the pilot team's Copilot plan
allows), 3 repetitions, on a plain apache/netbeans checkout (`c080a039d`, `nb-tasks/` expectations),
with the module built from this repository and run in NetBeans 31. AI credits re-price the measured
tokens at GitHub Copilot's Sonnet 5 rates ($2 input, $0.20 cached input, $2.50 cache write, $10 output
per million tokens; 1 credit = $0.01).

| Task | Mode | Correct | Turns | Tokens | Output tokens | Copilot AI credits | Seconds |
|---|---|---|---|---|---|---|---|
| 01 find the 24 callers | alone | 3/3 | 5.7 | 239,941 | 3,490 | 12.5 | 37 |
| | dukehoff-mcp | 3/3 | 5.7 | 229,706 | 3,969 | 12.2 | 41 |
| 02 find the 32 subclasses | alone | 3/3 | 9.0 | 362,833 | 6,718 | 17.8 | 143 |
| | dukehoff-mcp | 3/3 | 9.0 | 380,820 | 4,783 | 16.6 | 77 |
| 03 rename the method (16 files) | alone | 3/3 | 47.7 | 1,352,946 | 16,090 | 54.3 | 170 |
| | dukehoff-mcp | 3/3 | 16.3 | 757,826 | 4,631 | 26.9 | 155 |
| **all** | alone | 9/9 | 20.8 | 651,907 | 8,766 | 28.2 | 117 |
| | dukehoff-mcp | 9/9 | 10.3 | 456,117 | 4,461 | 18.6 | 91 |

With dukehoff-mcp: 34% fewer Copilot credits, 30% fewer tokens, 49% fewer output tokens, half the turns;
the rename alone 50% fewer credits. Observations from the transcripts:

* Sonnet did not use dukehoff-mcp at all for finding callers (0 of 3 sessions; it never looked at the MCP
  tools, which Claude Code lists only on demand). How often agents reach for the tools is a question
  for real usage data.
* Found a bug: right after a restart, while NetBeans re-checks the index, `find_symbol` answered "no
  types matching" for an existing class (9 times in the 3 rename sessions; `find_usages` and
  `find_implementations` were correct during the check). Sonnet retried, waited and polled, which
  accounts for most of the 16 turns of the rename with dukehoff-mcp (Opus needed 9). Fixed afterwards: a
  read query that finds nothing in the unverified index waits for the check and asks again. These
  numbers are from before the fix.

### Rerun after the fix (dukehoff-mcp sessions only)

The same 9 dukehoff-mcp sessions again, with the fixed build (the sessions without dukehoff-mcp are unchanged and
taken from the run above). 9/9 correct, $1.21.

| Task | Mode | Turns | Tokens | Output tokens | Copilot AI credits | Seconds |
|---|---|---|---|---|---|---|
| 01 callers | alone | 5.7 | 239,941 | 3,490 | 12.5 | 37 |
| | dukehoff-mcp | 7.0 | 212,169 | 3,838 | 11.0 | 39 |
| 02 subclasses | alone | 9.0 | 362,833 | 6,718 | 17.8 | 143 |
| | dukehoff-mcp | 11.3 | 353,743 | 5,459 | 16.3 | 85 |
| 03 rename | alone | 47.7 | 1,352,946 | 16,090 | 54.3 | 170 |
| | dukehoff-mcp | 4.7 | 180,745 | 795 | 6.9 | 124 |
| **all** | alone | 20.8 | 651,907 | 8,766 | 28.2 | 117 |
| | dukehoff-mcp | 7.7 | 248,886 | 3,364 | 11.4 | 83 |

With dukehoff-mcp: 60% fewer Copilot credits, 62% fewer tokens, 63% fewer turns. The rename now takes
`find_symbol` + `rename` in every session: 87% fewer credits, 5 instead of 48 turns.

* Callers: again 0 of 3 sessions used dukehoff-mcp.
* Subclasses: two sessions took the 32 subclasses from `find_implementations` (6 turns). In the
  third, `find_implementations` returned **only 9 of 32** while NetBeans was checking the index after
  the restart; the agent noticed via grep and spent 22 turns. The retry-when-empty fix does not cover
  partial results: answers from the unverified index can be incomplete, not just empty.

### Rerun with read tools waiting for the index check, plus time measurement

dukehoff-mcp sessions only, with the build where every tool waits for the index check after a restart.
9/9 correct, $1.06. New in this run: the server logs its startup time and how long tool calls
waited for the initial scan, so the time a server kept running between sessions would need can be
estimated. Time without dukehoff-mcp is from the run above. Its time in the model is from the transcripts.

| Task | Mode | Turns | Copilot AI credits | Seconds | Without startup and scan | In the model |
|---|---|---|---|---|---|---|
| 01 callers | alone | 5.7 | 12.5 | 36 | 36 | 33 |
| | dukehoff-mcp | 5.3 | 13.8 | 123 | 122 (47 without one outlier) | 118 |
| 02 subclasses | alone | 9.0 | 17.8 | 142 | 142 | 138 |
| | dukehoff-mcp | 5.3 | 9.1 | 148 | 46 | 32 |
| 03 rename | alone | 47.7 | 54.3 | 169 | 169 | 164 |
| | dukehoff-mcp | 4.7 | 7.2 | 123 | 27 | 13 |
| **all** | alone | 20.8 | 28.2 | 116 | 116 | 112 |
| | dukehoff-mcp | 5.1 | 10.0 | 131 | 65 | 54 |

* Credits: 65% fewer with dukehoff-mcp; subclasses 49% fewer, rename 87% fewer.
* Subclasses now take `find_implementations` in all 3 sessions (5–6 turns, all 32 found).
* Time: NetBeans started in about 2 s. The index check after the restart cost about 100 s per
  session, and as long as each session starts its own server it eats the gain. Without it the
  subclasses took 46 instead of 142 s (−68%), the rename 27 instead of 169 s (−84%).
* Callers: again no dukehoff-mcp use. One session spent 276 s in the model for 5 turns, which was model
  latency. The other two took 47 and 37 s.
* The one MCP "error" was the agent checking after the rename that the old name was gone.

### 2026-10-02: tool-choice hint in the server instructions, and a find-callers task grep gets wrong

Sonnet never used dukehoff-mcp for task 01 (9 of 9 sessions): Claude Code shows only tool names and the
server instructions until a tool is loaded, the name `find_usages` does not suggest "callers", and
the task is easy for grep (distinctive name, all calls qualified). The server instructions now map
questions to tools ("who calls a method ... -> find_usages", with why grep falls short). Same setup
as above (Sonnet 5, warm index); credits priced as above; time without startup and index check.

| Task | Mode | Correct | Turns | Copilot AI credits | Seconds | Sessions using dukehoff-mcp |
|---|---|---|---|---|---|---|
| 01 callers, with hint | dukehoff-mcp | 3/3 | 6.0 | 13.2 | 51 | 1/3 |
| 04 callers of an overload (new) | alone | 3/3 | 19.3 | 34.6 | 388 (96 without one outlier) | - |
| | dukehoff-mcp | 3/3 | 12.7 | 20.0 | 99 | 2/3 |

* Task 01: one session reached for `find_usages` after grep showed a same-named method in another
  class; the other two finished with 2–3 greps in 4–6 turns. The hint helps a little; on an easy
  grep task grep is the cheaper path anyway.
* Task 04 (new, `nb-tasks/04-find-overload-callers`): callers of the package-private
  `TreeUtilities#isSynthetic(CompilationUnitTree, Tree)`, 8 call sites. Traps: two calls inside
  `TreeUtilities` without a receiver, and `Reformatter` and `spi.java.hints`' `Utilities` have
  private methods of the same signature called the same way. Without dukehoff-mcp all sessions got it
  right but needed 18–21 turns (reading files, listing the package); one spent 972 s, 7.5 minutes
  of it thinking in a single step. With dukehoff-mcp: 42% fewer credits overall; the two sessions that
  used `find_usages` (loaded it first thing) took 7 and 10 turns, 9.9 and 14.7 credits (−64%
  against without). The third never loaded it and took 21 turns like the sessions without.
* Both sessions that used `find_usages` still grepped afterwards to double-check the result.

### 2026-10-02: completeness note in find_usages

`find_usages` now ends with what the list covers ("includes calls without a receiver, through
subclasses and via static imports; excludes other overloads, same-named members of other types ...
No need to verify with grep."). Task 04 again, dukehoff-mcp only, 5 sessions:

| Task 04 | Correct | Turns | Copilot AI credits | Sessions using dukehoff-mcp | Re-checked with grep after find_usages |
|---|---|---|---|---|---|
| alone (above) | 3/3 | 19.3 | 34.6 | - | - |
| dukehoff-mcp, before the note (above) | 3/3 | 12.7 | 20.0 | 2/3 | 2 of 2 |
| dukehoff-mcp, with the note | 5/5 | 8.4 | 12.4 | 5/5 | 1 of 4 |

* Three sessions: `find_symbol`, `find_usages`, write the answer: 5 turns, 5.3–5.5 credits (−85%
  against without dukehoff-mcp). One still grepped and read files afterwards (7 turns). One went the grep
  way first and called `find_usages` only at the end to confirm (20 turns).
* With the note: 64% fewer credits than without dukehoff-mcp, 38% fewer than dukehoff-mcp before the note.
* Time without startup and index check is still ~96 s although the short sessions spent only 10–30 s
  in the model. The rest is the `find_usages` call itself: 48–50 s in every session (tool-call
  timestamps in the transcripts), the same when repeated in a running server, so a persistent server
  would not remove it. Cause: javac lists packages over the classpath of hundreds of roots NetBeans'
  modules have. On Commons Lang (one Maven project) the same kind of search takes 1–5 s.

## 2026-10-02: Apache Commons Lang 3.17.0 on the dukehoff-mcp runtime (product evaluation)

`eval/oss-tasks`, Claude Sonnet 5, 3 sessions per task and mode, dukehoff-mcp from the runtime built in
this repository (`runtime/`, 270 MB). Expected answers from javac/javap. 24/24 correct, $5.65.
Credits priced as above. The index check after a restart takes 3 s here, so the times are close
to wall-clock (seconds without startup and index check, total in parentheses).

| Task | Mode | Turns | Copilot AI credits | Seconds | Sessions using dukehoff-mcp |
|---|---|---|---|---|---|
| 01 callers of a distinctive method (12) | alone | 3.0 | 5.7 | 13 (13) | - |
| | dukehoff-mcp | 3.7 | 4.5 | 15 (16) | 2/3 |
| 02 subclasses of CharSequenceTranslator (12) | alone | 6.7 | 7.9 | 29 (29) | - |
| | dukehoff-mcp | 5.3 | 6.6 | 27 (28) | 1/3 |
| 03 rename one overload (3 files) | alone | 22.3 | 35.0 | 108 (108) | - |
| | dukehoff-mcp | 12.0 | 14.1 | 39 (40) | 3/3 |
| 04 callers of one overload (19 lines) | alone | 54.0 | 75.5 | 192 (192) | - |
| | dukehoff-mcp | 11.7 | 15.9 | 68 (69) | 3/3 |
| **all** | alone | 21.5 | 31.0 | 86 | - |
| | dukehoff-mcp | 8.2 | 10.3 | 37 | 9/12 |

With dukehoff-mcp: 67% fewer credits, 62% fewer turns, 56% less time. Rename: −60% credits, −64%
time; overload callers: −79% credits, −65% time. The easy tasks (01, 02) are cheap either way.

* 02: one session used `find_implementations` (4 turns); two grepped `extends` for the three base
  classes, which works here because the hierarchy is shallow.
* 04 without dukehoff-mcp: 41–81 turns of reading `ArrayUtils`/`StringUtils` to resolve argument types.
  With dukehoff-mcp: 5, 9 and 21 turns. The 21-turn session reconciled "19 usages" with 21 calls:
  `find_usages` counts lines, and two lines hold two calls each (fix: count calls).
* 03: `mvn` is not among the allowed commands of `run.sh`; sessions in both modes tried it several
  times before checking otherwise (dukehoff-mcp: `diagnostics`). Allow `Bash(mvn:*)` for oss-tasks.
* One rename session also updated a `{@code ClassUtils.getShortClassName(cls)}` text in Javadoc by
  hand, which the refactoring does not touch.

### Rerun of 03 and 04 with both fixes (both modes)

`find_usages` now counts usages, not lines ("21 usages ... on 19 lines", "6460 (2 usages): ..."),
and `run.sh` allows `mvn`. Tasks 03 and 04 again, 3 sessions per mode; 01 and 02 unchanged from above.

| Task | Mode | Correct | Turns | Copilot AI credits | Seconds |
|---|---|---|---|---|---|
| 03 rename one overload | alone | 3/3 | 23.3 | 34.8 | 133 |
| | dukehoff-mcp | 3/3 | 7.7 | 9.8 | 53 |
| 04 callers of one overload | alone | **2/3** | 33.7 | 69.6 | 188 |
| | dukehoff-mcp | 3/3 | 4.0 | 6.4 | 28 |
| **all four tasks** (01, 02 from above) | alone | 11/12 | 16.7 | 29.5 | 91 |
| | dukehoff-mcp | 12/12 | 5.2 | 6.8 | 31 |

With dukehoff-mcp: 77% fewer credits, 69% fewer turns, 66% less time; overload callers −91% credits,
−85% time, rename −72% credits, −60% time.

* 04 with dukehoff-mcp: all three sessions `find_usages`, write the answer: 4 turns, no re-check.
* 04 without dukehoff-mcp: one session missed 5 of the 19 call sites (the first wrong answer on Commons Lang).
* 03: sessions without dukehoff-mcp now compile with `mvn` (3–4 times each); with dukehoff-mcp `rename` and one
  `mvn`, and two sessions also updated a Javadoc `{@code ...}` mention by hand.

## 2026-10-06: baseline of everyday tasks (roadmap item 7)

Where do agents spend their turns on ordinary work, and so where would replacement tools save most?
Three new tasks in `eval/daily-tasks` on Commons Lang 3.17.0, **baseline only** (no dukehoff-mcp), 3
sessions each, Claude Opus 5.5, `run.sh` unchanged (no web access, Bash limited to mvn, grep, find,
ls, cat, git diff/status, javac, java). `eval/turns.py` attributes every tool call to a category.

* **01-feature:** add `StringUtils.countMatchesIgnoreCase` with Javadoc and tests (hidden checks).
* **02-bugfix:** a user report of symptoms (`endsWithIgnoreCase`/`containsIgnoreCase` fail for a
  `StringBuilder`); the injected bug is in `CharSequenceUtils.regionMatches`, on a single-commit
  copy (`setup-bug.sh`), so no history points at it.
* **03-add-test:** a test class for `StringUtils.abbreviateMiddle`; it must kill three mutants.

| Task | Correct | Turns | Tool calls | Cost (USD) | Seconds |
|---|---|---|---|---|---|
| 01 feature | 3/3 | 11.0 | 10 | 0.22 | 58 |
| 02 bug fix | 3/3 | 9.0 | 8 | 0.15 | 150 |
| 03 add a test | 3/3 | 6.0 | 5 | 0.16 | 42 |

Tool calls by category (all 9 sessions; "result" = text the calls put into the context):

| Category | Calls/run | Share of calls | Result KB/run | Share of results |
|---|---:|---:|---:|---:|
| read | 1.0 | 13% | 1.8 | 26% |
| search | 2.4 | 32% | 3.3 | 46% |
| edit | 2.0 | 26% | 0.4 | 6% |
| compile | 0.2 | 3% | 0.4 | 5% |
| test | 2.0 | 26% | 1.3 | 18% |

No dependency, web or git calls.

* **Short sessions:** everyday tasks of this size cost Opus 5.5 5–12 turns and $0.12–0.27; there is
  little to save per session. Search and read put 72% of the result text into the context, tests 18%.
* **Tests:** a quarter of all calls, but the agents keep the output small themselves (`mvn -q`,
  `-Dtest=...`, `| grep`, skipping rat/jacoco/checkstyle/...). Running the whole suite is what costs
  time (bug fix: 150 s per session, mostly `mvn test`). A `run_tests` tool would save wall-clock time
  (warm daemon, only the affected tests) more than tokens or turns.
* **Prior knowledge distorts navigation:** all three bug-fix sessions started with
  `Grep "static boolean regionMatches"`, straight at the injected bug, without reading `StringUtils`
  or running a test: the model knows Commons Lang's internals from training. Well-known open-source
  code understates what navigation tools are worth; that needs code the model has not seen (a
  customer's code base, or a renamed/obfuscated copy).
* **Setup lesson:** workspaces under `~/.claude` do not work: Claude Code refuses edits there as
  "sensitive file". In two discarded sessions the agent reported success anyway, so only `check.sh`
  results count.
* **Limits:** one model, 9 sessions, a mid-sized well-known library, well-specified prompts. Next:
  the same tasks in netbeans mode and with Sonnet, and tasks on unfamiliar code.

### The same tasks with dukehoff-mcp (2026-10-06)

Netbeans mode, 3 sessions per task, the current runtime (persistent server, all 12 tools; `run.sh`
warms the index before the first session and stops the server at the end).

| Task | Mode | Correct | Turns | Tokens | Cost (USD) | Seconds |
|---|---|---|---|---|---|---|
| 01 feature | alone | 3/3 | 11.0 | 204k | 0.22 | 58 |
| | dukehoff-mcp | 3/3 | 11.0 | 233k | 0.23 | 63 |
| 02 bug fix | alone | 3/3 | 9.0 | 146k | 0.15 | 150 |
| | dukehoff-mcp | 3/3 | 10.7 | 205k | 0.17 | 190 |
| 03 add a test | alone | 3/3 | 6.0 | 140k | 0.16 | 42 |
| | dukehoff-mcp | 3/3 | 6.0 | 134k | 0.14 | 40 |

* **No session called dukehoff-mcp**, not even a tool search to load its schemas, although the server was
  connected and offered 12 tools. The categories match the baseline (search 35% of calls, read 18%,
  edit 22%, test 22%). The differences in the table are noise of a few sessions (one bug-fix
  session took 273 s, mostly its full `mvn test` runs).
* Nothing in these tasks matches the questions the server instructions map to tools (callers,
  implementations, renaming, moving, signatures); the agents know where to look (Commons Lang) and
  check their work with `mvn test`, which also compiles, so not even `diagnostics` was needed.
* So for everyday work on well-known code the tool set neither helps nor costs measurably; dukehoff-mcp's
  value stays where the earlier evaluations found it: refactorings and cross-file questions grep gets
  wrong. The only step these sessions take every time that a tool could replace is running tests
  (2 calls per session); whether a `run_tests` tool would be adopted is open.

### The same tasks with Sonnet 5.5 (2026-10-06)

Both modes, 3 sessions per task and mode, otherwise as above.

| Task | Mode | Correct | Turns | Tokens | Cost (USD) | Seconds |
|---|---|---|---|---|---|---|
| 01 feature | alone | 3/3 | 11.3 | 171k | 0.10 | 36 |
| | dukehoff-mcp | 3/3 | 7.7 | 143k | 0.10 | 36 |
| 02 bug fix | alone | 3/3 | 6.7 | 93k | 0.06 | 25 |
| | dukehoff-mcp | 3/3 | 6.3 | 103k | 0.05 | 25 |
| 03 add a test | alone | 3/3 | 5.7 | 104k | 0.07 | 35 |
| | dukehoff-mcp | 3/3 | 6.3 | 126k | 0.09 | 37 |

* **Again no dukehoff-mcp call** in any of the 9 sessions with the server. The differences between the
  modes are noise of three sessions each.
* **Sonnet was cheaper and faster than Opus** on all three tasks (about half the cost; the bug fix
  25 s instead of 150 s): it ran the matching tests once (`-Dtest='StringUtils*Test,...'`), where Opus
  also ran the whole suite.
* **Sonnet knows Commons Lang too:** every bug-fix session put `regionMatches` into its first grep,
  next to the `StringUtils` methods of the report.
* Together with Opus: 36 sessions with and without dukehoff-mcp, 36/36 correct, 0 dukehoff-mcp calls. For
  everyday tasks on well-known code, agents of either model do not reach for the server, and the
  tool set does not change cost or time. Measuring navigation needs code the models have not seen.

### The same tasks on unfamiliar code (2026-10-07)

`eval/unfamiliar/make-copy.py` turned Commons Lang into "Lumens Toolkit" (renamed package, `*Utils`
-> `*Tools`, task methods renamed, no Apache/Commons traces; 104 renames by dukehoff-mcp, full test suite
green). Same three tasks in the new names (`eval/unfamiliar-tasks`), Sonnet 5.5, both modes, 3
sessions each. API time instead of wall time for the bug fix: see below.

| Task | Mode | Correct | Turns | Tokens | Cost (USD) | API seconds |
|---|---|---|---|---|---|---|
| 01 feature | alone | 3/3 | 10.0 | 198k | 0.12 | 23 |
| | dukehoff-mcp | 3/3 | 9.0 | 182k | 0.11 | 23 |
| 02 bug fix | alone | 3/3 | 11.7 | 236k | 0.12 | 45 |
| | dukehoff-mcp | 3/3 | 11.0 | 224k | 0.09 | 34 |
| 03 add a test | alone | 3/3 | 6.7 | 119k | 0.08 | 21 |
| | dukehoff-mcp | 3/3 | 6.7 | 137k | 0.08 | 19 |

* **Again no dukehoff-mcp call** in any of the 9 sessions with the server: 54 sessions on everyday tasks
  so far (Opus and Sonnet, known and unfamiliar code), 54/54 correct, 0 dukehoff-mcp calls.
* **Unfamiliar code made the bug fix harder, not different in kind:** compared with Sonnet on
  Commons Lang, about 75% more turns (11.3 instead of 6.5) and twice the cost. All six sessions
  navigated with grep and Read: `Caseless` in `TextTools`, read the method, follow the call to
  `segmentsMatch` in `SequenceTools`, 3–5 steps instead of one. Four of six still grepped for
  `regionMatches` on the way: the model recognises the library's shape and guesses old names.
* **Test runs:** not knowing which test classes matter, every bug-fix session ran the whole suite
  (on Commons Lang: only `StringUtils*Test,CharSequenceUtilsTest`). Claude Code moved the run to the
  background after its 120 s Bash timeout, the agents could not wait for it and ended, and the
  headless session then waited 30–55 minutes for the background `mvn test` before exiting. Wall
  time of these sessions measures that, not the agent; the API time is comparable.
* **For tool groups:** the one-hop navigation these tasks need is easy with grep, so they still do
  not show where dukehoff-mcp's tools pay off; that stays refactorings and cross-file questions. What the
  agents visibly lacked was knowing which tests to run, and waiting for a long test run: a
  `run_tests` tool that picks the tests of the changed code and returns only failures would address
  both, which makes it the strongest candidate from this series.

### run_tests offered (2026-10-07)

The same three tasks on the unfamiliar copy, Sonnet 5.5, netbeans mode with `run_tests` (13 tools,
server instructions: "running tests, e.g. after a change -> run_tests"), 3 sessions each. Bash with
`mvn` stays allowed, so the agents choose.

| Task | Correct | `run_tests` used | What the agents ran instead |
|---|---|---|---|
| 01 feature | 3/3 | 0/3 | `mvn -q -o test -Dtest=TextToolsSubstringTest` (the prompt names where the tests go) |
| 02 bug fix | 3/3 | 0/3 | the whole suite, `mvn -q -o test`, as without `run_tests` |
| 03 add a test | 3/3 | 1/3 | `mvn -q test -Dtest=TextToolsShortenMiddleTest` |

* **The tool works:** the one call was `run_tests {}`; it found the new test class via `git status`
  and returned "Ran 7 tests in 1 class: 7 passed" after 9 s.
* **Adoption is the problem, not the tool:** in the bug fix, where an agent does not know which tests
  matter and runs the whole suite (the case `run_tests` is for), no session called it. Agents keep
  their own way of doing a step as long as it works, as with `find_usages` before the instructions
  mapped "find callers" to it. The tool only pays off if the agent reaches for it.
* Next: test adoption levers with 3 bug-fix sessions each: (a) stronger server instructions ("do not
  run mvn/gradle test yourself"), (b) a line in the client's project instructions (CLAUDE.md,
  copilot-instructions.md), which users control and agents follow closely.
* **Decision (2026-10-07): `run_tests` removed again** (`03d79a3`). Against dukehoff-mcp's aims (fewer
  tokens, less time, correct results) it brought nothing measurable: agents trim test output
  themselves, a run takes as long as their own `mvn`, results are the same, and it was rarely used.

## 2026-10-07: debug on bugs meant to need runtime values

Two bug tasks on the unfamiliar copy, each with its own single-commit copy (`setup-bug.sh`), the
prompt naming only the symptom: **04-debug-cache** (the format cache key drops its last part, the
locale, in a "defensive copy": German day names come out English after an English request) and
**05-debug-number** (`createNumber`'s int-size check for hex uses `>= '7'`: `0x7FFFFFFF` becomes a
Long). Sonnet 5.5, without and with dukehoff-mcp (13 tools including `debug`), 3 sessions each.

| Task | Mode | Correct | Turns | Cost (USD) | API seconds | `debug` used |
|---|---|---|---|---|---|---|
| 04 cache key | alone | 3/3 | 5.0 | 0.09 | 27 | - |
| | dukehoff-mcp | 3/3 | 8.0 | 0.10 | 18 | 0/3 |
| 05 hex number | alone | 3/3 | 6.0 | 0.07 | 14 | - |
| | dukehoff-mcp | 3/3 | 7.7 | 0.08 | 19 | 0/3 |

* **No session used `debug`**, and none needed it: every session found the bug by reading in 2–4
  steps (grep for the class, read the one file, the line stands out: `Arrays.copyOf(keys,
  keys.length - 1)`, `>= '7'`), then edited and ran `mvn test`. Several cache sessions grepped for
  `MultipartKey`, Commons Lang's name left in a Javadoc comment.
* **The tasks, not the tool, failed the purpose:** a bug in one file with a conspicuous line is
  found by reading; an agent, like a developer, reaches for a debugger only when reading does not
  suffice. Candidates where it would: a cause in code without source (a library behaving
  unexpectedly), a wrong value built up over many steps (parsers, state machines), large unfamiliar
  code bases where many classes could produce the value (first user site).
* Decision: `debug` stays an opt-in group, in no preset; next, a task of one of those kinds before
  more work on the tool.

### A cause spread over code and configuration (2026-10-07, `eval/debug-tasks`)

**Kestrel Billing** (`eval/debug-tasks/billing`, invented, 20 classes, 17 tests): usage lines
converted into billing units, a layered tariff configuration (base properties plus a per-tariff
overlay), rules created by name via reflection and applied in priority order, then tax. The bug
(`setup-bug.sh`): `RuleSpec.compareTo` compares the configured priorities as strings. Only the
business overlay has two-digit priorities (`volume:9, loyalty:12`), so only long-term business
customers get the loyalty credit before the volume discount (692.88 instead of 688.42); one test
fails. Every class looks right on its own. The check (`check.sh`) adds a hidden tariff with other
priorities and refuses changed configuration files; `validate.sh` checks the bug, a zero-padding
configuration "fix" (fails) and the reference fix (passes). Spike: `debug stop_at RuleChain#apply`,
`expressions: [specs]` shows `[base:1, loyalty:12, volume:9, cap:95]` in 2 s.

Sonnet 5.5, 3 sessions each; 07 is 06 plus "Use the debugger to find the cause." at the end.

| Task | Mode | Correct | Turns | Cost (USD) | API seconds | `debug` used |
|---|---|---|---|---|---|---|
| 06 billing | alone | 3/3 | 7.3 | 0.089 | 21 | - |
| | dukehoff-mcp | 3/3 | 8.0 | 0.096 | 22 | 0/3 |
| 07 billing, "use the debugger" | dukehoff-mcp | 3/3 | 10.7 | 0.103 | 26 | 3/3 (4 calls, 1 error) |

* **Without the hint the project is read whole:** every session `cat`s the properties, the rules
  and the config classes in one or two Bash calls (20 small classes fit easily) and spots
  `priority.compareTo` together with `9`/`12` in the overlay. Reading wins on a project this size.
* **With the hint every session calls `debug` first, at the wrong place:** twice
  `stop_at InvoiceService#invoice` (the entry point: shows only the arguments, nothing about the
  rules), once `InvoiceService:1` (no code on that line: "the test ended without stopping"). Then
  they read the code as without the hint and find the bug; one confirmed it afterwards with
  `stop_at RuleChain#apply, expressions: [this.specs]`. The debugger added 3 turns and no insight.
  That is how a developer debugs too (stop early, then step), but a stateless call cannot step:
  the first stop has to be the right one, which presumes the agent already suspects where.
* Tool friction seen: a line without code ends with "did not stop" instead of using the next line
  with code; collection fields of `this` print only their type (`specs=ImmutableCollections$ListN`),
  so the useful value needs an extra expression.
* Conclusion: a small project is not where reading fails; a task for `debug` needs code too large
  to read whole (or no source), so the agent cannot find the line by reading and the runtime value
  narrows it down.

### A cause hidden in data: the billing platform (2026-10-07, `eval/debug-tasks/platform`)

**Kestrel Billing Platform** (invented, 46 classes, 1,900 lines with tests, `make-platform-data.py`):
a month of data for 120 customers (4,024 meter records with mixed units, redeliveries, 45
corrections), tariff changes with proration, graduated prices, rules, tax per country, credits,
CHF cash rounding. The bug (`setup-platform-bug.sh`): `CorrectionMerger` orders records by the
written timestamp as text (`comparing(MeterReading::recordedAt)`) instead of as an instant. Only one
correction (c-1187, written 30 minutes after its reading on the same day, in UTC while the meter wrote
+01:00) sorts wrong, so only c-1187's invoice differs: 5997.05 instead of 5987.15 CHF; one test fails.
`validate-platform.sh`: the bug fails, "corrections always win" fails the hidden check (two
corrections in different offsets), the reference fix passes.

| Task | Mode | Correct | Turns | Cost (USD) | API seconds | `debug` used |
|---|---|---|---|---|---|---|
| 08 platform | alone | 3/3 | 11.7 | 0.175 | 31 | - |
| | dukehoff-mcp | 3/3 | 15.7 | 0.219 | 40 | 0/3 |
| 09 platform, "use the debugger" | dukehoff-mcp | 3/3 | 24.7 | 0.276 | 74 | 3/3 (11 calls, 5 errors) |

* **The data was greppable after all:** every session grepped `c-1187` in the data, two filtered the
  corrections (`grep -v ";reading"`), read the usage package (6 small classes, one `cat`) and found
  the comparator. A bug whose trigger sits in a few visible lines of data is a reading task.
* **With the hint, the debugger cost twice the turns:** sessions stopped in `InvoiceBuilder#build`
  for c-1187 and looked at net, tax, credits and usage, which shows *that* storage is off but not
  why; then they read the usage code as without the hint. Nobody stopped in the merger.
* **All 5 errors were tool friction, now fixed:** agents wrote `when` in Java
  (`customer.id().equals("c-1187")`; only `path op value` was accepted), and a line breakpoint on a
  blank line (`InvoiceBuilder.java:77`) never armed while the result blamed the condition ("held 0
  times"). Now `a.equals(b)` / `!a.equals(b)` work and a line without code moves to the next line with
  code in its method, as in the IDE.
* Conclusion for invented tasks: as long as the cause can be reached by grep plus reading a package,
  Sonnet reads, and a hint to debug makes it slower. Real code bases with unknown bugs are next:
  open NetBeans issues (no fix exists anywhere, so no model knows it), for which `debug` now runs
  NetBeans modules' tests through Ant.

### A real open bug: NetBeans #9399 (2026-10-07, `eval/debug-tasks/netbeans-9399`)

The first task whose fix no model can know: issue #9399 (open, no PR), a false "assigned value is
never used" in `java.hints` when a switch arm throws. Workspace: a clone of a built NetBeans
checkout, `java/java.hints` (~1,000 classes in the module, 100+ module classpath), with only the
reproducing tests committed; full NetBeans 31 with the NBM (the runtime has no apisupport). Cause
three classes from the symptom: `Utilities.ExitsFromAllBranches` (no `visitSwitchExpression`, rule
cases treated as falling through) misleads `Flow.visitBlock`. The check runs the issue's tests, a
hidden test (block-bodied arms; a switch whose rules all throw still exits) and NPECheck, Flow and
IntroduceHint tests (~2.5 min). Sonnet 5.5, 3 sessions each.

| Task | Mode | Correct | Turns | Cost (USD) | Seconds | `debug` used |
|---|---|---|---|---|---|---|
| 10 #9399 | alone | 3/3 | 38.0 | 1.07 | 1497 | - |
| | dukehoff-mcp | 3/3 | 52.3 | 0.93 | 1082 | 0/3 |
| 11 #9399, "use the debugger" | dukehoff-mcp | 2/3 | 36.3 | 1.03 | 1032 | 3/3 (21 calls, 12 errors) |

* **Sonnet solves a real unknown bug in a large module:** 8 of 9 fixed `Utilities` (most also
  changed `switch` handling the way the reference fix does); the one failure only patched
  `Flow.visitThrow`. Each session took 15–45 minutes and about a dollar.
* **They debug with println:** 7 of 9 sessions added 3–8 temporary `System.err.println`/`DBG`
  lines to `Flow` and reran Ant, the loop `debug` is meant to replace.
* **`debug` failed on nested classes, in this code base everywhere:** 10 of the 12 errors were
  "did not stop" on `Flow.VisitorImpl` methods: the binary name was derived from a file named like the
  nested class (none), and JDI's `Outer$Inner` parameter types did not match the signature's
  `Outer.Inner`. A line in a nested class also landed in the outer class's static initializer (the
  blank-line fallback took the innermost "method" around the line, which was `<clinit>`). One error:
  `Flow$VisitorImpl:123` was not accepted. All four fixed afterwards (`95e02c8` and the next commit);
  the hint sessions are rerun with the fixed tool.
* **The Ant harness is the hardest part for agents:** `ant -Dtest.class=X test` runs the whole
  module suite (over an hour; `test` ignores `test.class`), and sessions did that 0–16 times, waited
  in the background, or hit the 10-minute background limit. Three sessions (10-baseline-1,
  10-netbeans-3, 11-hint-2) were ended by hand after their final answer while a leftover full-suite
  run kept the headless session alive; their seconds include that wait. The prompt now says the
  suite takes over an hour; `test-method` and `test-single` are the targets to name (a later prompt
  may spell out `test-single -Dtest.includes=`).

Rerun of 11 with the fixed `debug` (nested classes, `Outer$Inner` names; 2026-10-07):

| Task | Mode | Correct | Turns | Cost (USD) | Seconds | `debug` calls (errors) | println edits | Ant runs |
|---|---|---|---|---|---|---|---|---|
| 11 #9399, "use the debugger" | dukehoff-mcp | 2/3 | 34.3 | 0.52 | 257 | 11 (2) | 0 | 9 |

* **Half the cost and a sixth of the time of the sessions without dukehoff-mcp** (alone: $1.07, 1497 s;
  dukehoff-mcp unprompted: $0.93, 1082 s), and no println loop at all (before: 3–8 print edits in 7 of 9
  sessions). The two errors were the agents' own (a line the test never reached, a type that does
  not exist); no full-suite Ant runs.
* The failure fixed switch expressions only and left `case 1 -> data = "a"; default -> throw` in a
  switch statement; it did not run the second issue test at the end. Correctness is not what the
  debugger changes; the time and cost to the cause is.
* Still 0 of 6 unprompted sessions called `debug`: the gain needs the user's "use the debugger" (the
  Copilot story) or better triggers in the instructions (Tool adoption, idea 4/5).

## 2026-10-08: GlassFish (584 Maven modules) and Linux, before a first external evaluation

Ubuntu 24.04 in Docker (OpenJDK 21 and Maven from apt), the evaluation archive unpacked as a user
would: smoke test passes on arm64 and amd64 (emulated). The smoke fixture needed the compiler plugin
pinned: Ubuntu's Maven 3.8 defaults to compiler 3.1, which ignores `maven.compiler.release`.

GlassFish (eclipse-ee4j/glassfish, shallow clone, 584 poms, 14,429 Java files; built once with
`-Pfastest`, Maven 3.10 as its enforcer wants 3.9.16+), whole repository as the workspace, macOS,
32 GB (default heap: a quarter):

| | first run, `~/.m2` filled while priming | fresh run, warm `~/.m2` | full NetBeans 31 + NBM |
|---|---|---|---|
| ready after | ~16 min (12 priming, 4 indexing) | ~6 min | ~8 min |
| memory | 4.5 GB ready, 6.6 GB after calls | | |
| files with compile errors | 1,988 (153 of 173 `glassfish-jar` modules) | 30 (5 of 173) | 30 |
| `find_usages StringUtils#ok` | 3 files (old scope) | 70 files, 191 usages, 10 s | 71 files |

* **`find_usages` missed 70 of 73 files** (ground truth by text: 62 qualified calls, 11 static
  imports): our custom `Scope.create(roots, …, true)` searched only modules that see the declaring
  module as sources; GlassFish modules see siblings as jars. NetBeans' default scope (projects
  depending on the symbol's project) finds them all; `find_implementations` of `AdminCommand` likewise
  (362 in 361 files, text count consistent). The rename (its own dependent-roots search) was right
  all along (72 files). Fixed by dropping the custom scope.
* **A first start that downloads dependencies leaves broken classpaths in the index:** 1,988 files
  in error until a fresh data folder; the same project then had 30. Not refreshed by itself. Advice
  for users until fixed: build the project once (or `mvn dependency:go-offline`) before the first
  start. Open: refresh classpaths/reindex roots in error after priming.
* **`find_usages` no longer claims completeness when files in error mention the name:** "Possibly
  incomplete: N files with compile errors mention 'ok'; … Check them with grep: …" (here the 2
  remaining files).
* Call times on the warm index: `find_symbol` 4 s, `outline` 3 s, `find_usages` 10–12 s,
  `find_implementations` 33–52 s, `diagnostics` (whole workspace) 10–80 s, `rename` of a method used
  in 72 files 52–66 s.

Follow-up (2026-10-09): two more fresh runs had 30 files in error, so the 1,988 of the first run are
not reproduced. One showed the server ready after 2 minutes, with `find_usages` at that moment
finding 60 of 73 files: later class-path registrations start further scans the startup wait did not
cover. With the wait for indexing to stay quiet (5 s), a fresh run was ready after ~6.5 min and
`find_usages` right then found 71 files (the other 2 do not compile).

## 2026-10-10: tool names, A/B

A: today's names, one registration `netbeans` (`mcp__netbeans__find_usages`). B: new names in five
disjoint registrations (`search-java`: references, implementations, find_type, outline;
`check-java`: compile_errors, status; `refactor-java`: rename, move, change_signature, delete_unused,
inline; `migrate-java`: structural_replace; `debug-java`: stop_in_test), as `--config` prints them.
Everything else equal (same server fixes). Sonnet 5.5, netbeans mode only, 2 sessions per variant
and task. Commons Lang 3.17.0 (fresh clone; the old one was half deleted by macOS's temp cleanup)
and NetBeans #9399 without the debugger hint (workspace rebuilt outside the temp folder, the first
attempt failed on files the cleanup had deleted).

| Task | A correct | A uses the tool | B correct | B uses the tool | Turns A / B | Cost A / B (USD) |
|---|---|---|---|---|---|---|
| rename an overload | 2/2 | 2/2 `rename` | 2/2 | 2/2 `rename` | 3 / 3 | 0.038 / 0.036 |
| callers of an overload | 2/2 | 2/2 `find_usages` | 2/2 | 2/2 `references` | 5 / 5 | 0.054 / 0.057 |
| subclasses | 2/2 | 2/2 `find_implementations` | 2/2 | 0/2 (grep) | 5 / 4 | 0.053 / 0.053 |
| #9399, no hint | 1/2 | 0/2 | 2/2 | 0/2 | 18.5 / 40.5 | 0.29 / 0.81 |

* **No measurable gain from the new names.** Where the tool fits obviously (rename, callers) both
  variants use it every time; on #9399 neither variant calls any dukehoff-mcp tool (no session ever has
  without the hint). The #9399 differences (correctness, cost) come from how the sessions fought the
  Ant harness, not from tools.
* **One hint against bare nouns:** for "find all classes that extend X", A's sessions loaded
  `find_implementations`, B's grepped (Sonnet knows Commons Lang's subclasses) and never looked at
  `implementations`. Two sessions each, so a signal at most; verb phrases (`find_…`) may match an
  agent's intent better than nouns.
* Ant harness again: three of eight #9399 sessions ran `ant -Dtest.class=X test` (the whole module
  suite, over an hour) and waited; ended by hand after their final answer, their seconds include it.

### Tickets that do not point at a tool (2026-10-10, customer backend copy, Sonnet)

The runs above put the tool's job into the prompt ("rename", "find callers"), so both variants used
the tools: a ceiling, not a test of names. A valid test needs a task where a tool would pay but agents
do not reach for it. Screened first with the old names (variant A) on ticket-style prompts on a
redacted customer backend (multi-module Maven; local only, not in this repository):

| ticket | correct | dukehoff-mcp calls | turns | cost (USD) |
|---|---|---|---|---|
| hide a field from all API responses | 2/2 | 0/2 | 16.5 | 0.21 |
| single-line JSON in all log messages | 2/2 | 0/2 | 22.5 | 0.23 |
| PATCH loses a value (injected bug) | 2/2 | 0/2 | 6 | 0.06 |
| rename two accessors (names given) | 2/2 | 2/2 `rename` | 20 | 0.24 |
| `Repository.delete` returns the deleted entity (~24 implementations) | 2/2 | 0/2 | 108 | 1.37 |

The first three need no tool (grep finds every site, diffs reviewed complete); the rename names its
target and gets `rename`. Only the last one qualifies: 42 files, ~95 edits per session, where
`implementations` and one `change_signature` would do the declarations. It was run with the new names:

| variant | correct | dukehoff-mcp calls | tool searches | turns | cost (USD) |
|---|---|---|---|---|---|
| A: old names, server `netbeans` | 2/2 | 0 | 0 | 108 | 1.37 |
| B1: new names, server `netbeans` | 2/2 | 0 | 0 | 101 | 1.21 |
| B2: new names, five servers (`refactor-java`, `search-java`, ...) | 2/2 | 0 | 0 | 95.5 | 1.24 |

* **Neither confirmed nor refuted: no session even searched for a tool.** Claude Code lists the
  deferred tool names, and all six sessions went straight to grep, Read and ~95 Edits, so the names
  never entered a decision. (Turn and cost differences are within the spread.)
* What remains open: the claims may not hold; the old names may already be good enough; or the effect
  lives between the two extremes measured so far (prompt names the tool's job: always used; ticket
  far from it: never considered) and is small enough to need many repetitions. The one signal so far
  sits in that middle: "find all classes that extend X", A 2/2 `find_implementations`, B 0/2.
* Clients differ: Claude Code defers MCP schemas behind a tool search, while clients that load all
  schemas up front put names and descriptions into every prompt, where names may weigh more.

### The same ticket with skills (2026-10-10)

Skills are the channel whose descriptions Claude Code always shows (MCP tool descriptions wait for a
tool search). `eval/skills-plugin` holds two workflow skills, loaded per session with
`run.sh --plugin`: `java-change-api` ("Use when a Java change touches a method ... implemented,
overridden or called in several places - changing a return type or parameters ...") and
`java-impact` (who uses/implements X). Ticket 9, old tool names, Sonnet, 2 sessions:

| variant | correct | Skill calls | dukehoff-mcp calls | turns | cost (USD) |
|---|---|---|---|---|---|
| A, no skills | 2/2 | - | 0 | 108 | 1.37 |
| A + skills | 1/2 | 0 | 0 | 65 (47 / 139 messages) | 0.96 |
| A + skills named by intent (2026-10-11) | 2/2 | 0 | 0 | 95.5 | 1.10 |

The second skills run (`eval/skills-intent`, plugin `java`) names the skills after the agent's intent:
`refactor-java-across-files` ("Change a Java method's return type or parameters, rename it, or move
a class - in all implementations, overrides and callers at once. Use instead of many edits or a
rewrite script ...") and `find-java-usages`; bodies unchanged. Both sessions listed them; no
assistant message mentions a skill, ~107 Edits as before.

* Both sessions listed both skills at start; neither loaded one, although the description names the
  task's exact situation. One session saw the bulk change coming and wrote a Python script to rewrite
  the `delete` methods; the eval's permissions blocked it and the session stopped asking for approval
  (the failure). It reached for its own scripting, not for the skill.
* Together with the names A/B: on this ticket, Sonnet in Claude Code consults neither tool names, nor
  tool search, nor skill descriptions. What has worked so far is the user's prompt or project
  instructions naming the tool's job.

### The same ticket with all tool schemas up front (2026-10-11)

`ENABLE_TOOL_SEARCH=false` makes Claude Code load every MCP tool with its description into the
context, like Cursor or Claude Desktop (checked: with it Haiku quotes `change_signature`'s
description, without it answers "NOT VISIBLE ... deferred"). Ticket 9, Sonnet, 2 sessions each:

| variant | correct | sessions using dukehoff-mcp | calls (position among all tool calls) | turns | cost (USD) |
|---|---|---|---|---|---|
| A deferred (above) | 2/2 | 0/2 | - | 108 | 1.37 |
| B2 deferred (above) | 2/2 | 0/2 | - | 95.5 | 1.24 |
| A up front | 2/2 | 2/2 | `diagnostics` #72/98, #118/124 | 112 | 1.55 |
| B2 up front | 2/2 | 2/2 | `compile_errors` #56/96; `change_signature` #13/128 + `compile_errors` #111/128 | 113 | 1.45 |

* **Descriptions in context bring the tools into play:** 4/4 sessions used one (0/10 with deferred
  schemas in all variants). Mostly the compile check, late, in place of a Maven compile: the tool that
  replaces a step the agent takes anyway (as at the first user site with Copilot).
* One B2 session used `change_signature` early: 34 files in one call. Old vs. new names, 0/2 vs. 1/2:
  too few sessions to credit the names.
* It saved nothing here: that session still made 128 tool calls, since every implementation's body
  (`return true;` -> `Optional...`) needs a hand edit; the declarations were the small part. Eager
  schemas cost ~10-15% more per session (all descriptions in every turn).

### Invalid: cutting sessions short with a budget (2026-10-11)

To measure only tool usage cheaply, 2 × 10 sessions (up-front schemas, A vs. B2) ran with
`--budget 0.40`. Claude Code tells the model its budget, and the sessions changed course: most
planned and then stopped without editing ("The task is bigger than the budget I have left, and a
half-finished change would leave the project not compiling"), some after a bulk-edit script. 7–23
tool calls each, dukehoff-mcp in 2/10 (A: `find_implementations`, `find_usages`, `outline`) vs. 0/10 (B2).
Not comparable with full sessions; discarded. (`--max-turns` is accepted by the CLI but not
enforced.) A cut that the model cannot see has to stop the process from outside, e.g. after N tool
calls in the stream.

### Tool usage, invisible cut (2026-10-11)

`run.sh --stop-after N` (`eval/stop_after.py`) ends a session from outside after N tool calls; the
model sees the usual $3 budget and no limit. Ticket 9, schemas up front, Sonnet, 10 sessions per arm,
cut after 30 tool calls (the full sessions chose `change_signature` at #13). All 20 reached 30 calls.

| first 30 tool calls | any dukehoff-mcp tool | `change_signature` | lookups | compile check |
|---|---|---|---|---|
| A: old names | 2/10 | 1/10 (#19) | 1/10 (`find_symbol`, `find_implementations`) | 0/10 |
| B2: new names, five servers | 3/10 | 0/10 | 2/10 (`references`, `implementations`) | 1/10 (#22) |

With the full up-front sessions above: `change_signature` 1/12 with old names, 1/12 with new names;
any dukehoff-mcp tool early 2/10 vs. 3/10. **No difference between the names**; the earlier 0/2 vs. 1/2 was
chance. What changes usage is whether the descriptions are in the context at all (0/10 deferred,
2-3/10 early and 4/4 overall up front), not what the tools are called.

A first try of this run is discarded: `stop_after.py` crashed on `permission_denied` events (their
`message` is a string), which closed the pipe but left each session running orphaned; the sessions
kept editing the workspace and started servers while later sessions ran. The script now ends the
session's process group whenever it exits, also on an error.

## 2026-10-15: a JUnit 4 -> 5 migration (`eval/migrate-tasks`)

Question: does `apply_rule` (NetBeans Jackpot rules) help with a migration of many call sites, and
does a skill with rule documentation help writing the rules? Fixture (`make-fixture.py`): a Maven
project with 30 JUnit 4 test classes, 92 tests and 354 assertions, built to be hard as text edits:
the message moves from the first to the last argument, with nested calls and string literals
containing commas, calls over several lines, `delta` overloads, three `String` arguments (a message
left first still compiles in JUnit 5 and then fails), static, explicit and qualified imports,
`@Before`/`@BeforeClass`/`@After`/`@Ignore`, `@Test(expected = ...)`. `check.sh`: no JUnit 4 left in
code or `pom.xml`, as many assertions as before, all 92 tests run (4 skipped) and pass offline; a
correct migration passes, one message left first fails. The prompt asks for the migration without
naming a tool; `run.sh --allow python3,sed,perl,awk` lets every mode script it. Skill:
`eval/skills-migrate` (workflow, syntax, one rule per overload, typed variables, examples; not the
JUnit rules). Claude Code 2.x, default tool deferral.

| model | mode | correct | turns | cost (USD) | our tools / skill used |
|---|---|---|---|---|---|
| Sonnet 5.5 | baseline | 3/3 | 18.0 ± 8.5 | 0.230 | - |
| Sonnet 5.5 | netbeans | 3/3 | 21.7 ± 7.4 | 0.250 | 0/3 |
| Sonnet 5.5 | netbeans + skill | 3/3 | 19.7 ± 2.5 | 0.246 | 0/3, 0/3 |
| Haiku 4.5 | baseline | 3/3 | 51.0 ± 11.8 | 0.042 | - |
| Haiku 4.5 | netbeans | 3/3 | 29.3 ± 11.8 | 0.034 | 0/3 |
| Haiku 4.5 | netbeans + skill | 3/3 | 26.3 ± 7.1 | 0.030 | 0/3, 0/3 |

- All 18 sessions are correct, and none used `apply_rule` or the skill (both loaded: the skill is in
  the session's skill list, the tools in its tool list; no `ToolSearch`, no `Skill` call). The Haiku
  difference between baseline and netbeans is noise (no tool of ours was called).
- Every session wrote a migration script (Python with a small argument splitter that respects
  parentheses and string literals, or `sed`/`perl`) and got it right in one go. For JUnit the number
  of arguments decides the meaning (three without a `delta`: the message is first), so a script needs
  no types: the premise that text edits fail here was wrong.
- Writing the rules works: a rule set with one rule per overload migrates all 354 assertions,
  imports and annotations (`@Test(expected)`, old imports and `pom.xml` by hand), and passes the
  check. Finding: untyped variables (`org.junit.Assert.assertEquals($e, $a)`) miss statically
  imported calls of the `Object` overloads; with `:: $e instanceof java.lang.Object && $a instanceof
  java.lang.Object` they match. The skill says to type every variable.
- Next: the same task with an explicit instruction to use `apply_rule` (does it save tokens or time,
  is a correct result easier to get), and a migration whose meaning depends on types a script cannot
  see.
