#!/usr/bin/env bash
#
# Copyright 2026 Dukehoff GmbH
# SPDX-License-Identifier: Apache-2.0
#

# Runs the evaluation tasks with Claude Code in headless mode, once with only
# its built-in tools ("baseline") and once with the NetBeans MCP server added
# ("netbeans"), each on a fresh copy of the fixture project, and records
# correctness (the task's check.sh), turns, tokens, cost, time and which tools
# the agent called (from the stream-json transcript, kept as <run>.jsonl).
#
# THIS USES YOUR CLAUDE CODE LOGIN AND COSTS TOKENS: every run is a real session.
#
# Usage: run.sh [--reps N] [--model M] [--modes baseline,netbeans] [--tasks GLOB]
#               [--dukehoff-mcp PATH] [--budget USD] [--tasks-dir DIR]
#               [--repo DIR --workspace SUBDIR] [--plugin DIR] [--stop-after N]
#   --budget     spending cap per session (claude --max-budget-usd), default 3
#   --repo       run on an existing git checkout instead of the generated fixture; the
#                workspace SUBDIR must have no uncommitted changes and is reset with
#                git checkout/clean before every session (use a clone, not your checkout)
#   --plugin     load this Claude Code plugin (e.g. skills) in netbeans mode (--plugin-dir)
#   --stop-after end each session from outside after N tool calls (stop_after.py), to measure only
#                which tools a session starts with; unlike --budget, the model does not see the limit
# Environment: EVAL_DIR work and result directory (default ${TMPDIR:-/tmp}/dukehoffmcp-eval)
# Needs: claude, python3, git, javac

set -u

# sessions and their builds must not reach cloud accounts: no AWS credentials or config (SDK v1 and v2,
# CLI) and no instance metadata. Found 2026-10-10: a project's "unit" tests assumed roles with ~/.aws.
unset AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_SESSION_TOKEN AWS_PROFILE AWS_DEFAULT_PROFILE
export AWS_CREDENTIAL_PROFILES_FILE=/dev/null AWS_SHARED_CREDENTIALS_FILE=/dev/null AWS_CONFIG_FILE=/dev/null \
    AWS_EC2_METADATA_DISABLED=true

# keep the machine awake: an idle-sleeping Mac stalled a session and the MCP startup
if [ -z "${DUKEHOFF_MCP_EVAL_AWAKE:-}" ] && command -v caffeinate > /dev/null; then
    DUKEHOFF_MCP_EVAL_AWAKE=1 exec caffeinate -i "$0" "$@"
fi

HERE=$(cd "$(dirname "$0")" && pwd)
EVAL_DIR=${EVAL_DIR:-${TMPDIR:-/tmp}/dukehoffmcp-eval}
REPS=1
MODEL=
MODES=baseline,netbeans
TASKS='*'
DUKEHOFF_MCP=${DUKEHOFF_MCP:-}
BUDGET=3
TASKS_DIR=$HERE/tasks
GIT_REPO=
GIT_WORKSPACE=
PLUGIN=
STOP_AFTER=

while [ $# -gt 0 ]; do
    case "$1" in
        --reps) REPS=$2; shift 2 ;;
        --model) MODEL=$2; shift 2 ;;
        --modes) MODES=$2; shift 2 ;;
        --tasks) TASKS=$2; shift 2 ;;
        --dukehoff-mcp) DUKEHOFF_MCP=$2; shift 2 ;;
        --budget) BUDGET=$2; shift 2 ;;
        --tasks-dir) TASKS_DIR=$(cd "$2" && pwd); shift 2 ;;
        --repo) GIT_REPO=$(cd "$2" && pwd); shift 2 ;;
        --workspace) GIT_WORKSPACE=$2; shift 2 ;;
        --plugin) PLUGIN=$(cd "$2" && pwd); shift 2 ;;
        --stop-after) STOP_AFTER=$2; shift 2 ;;
        *) echo "unknown argument $1" >&2; exit 2 ;;
    esac
done
for tool in claude python3 git javac; do
    command -v $tool > /dev/null || { echo "$tool is required" >&2; exit 2; }
done
[ -x "$DUKEHOFF_MCP" ] || { echo "give the dukehoff-mcp launcher with --dukehoff-mcp or \$DUKEHOFF_MCP" >&2; exit 2; }

if [ -n "$GIT_REPO" ]; then
    [ -n "$GIT_WORKSPACE" ] && [ -d "$GIT_REPO/$GIT_WORKSPACE" ] || { echo "--repo needs --workspace <subdir>" >&2; exit 2; }
    if [ -n "$(git -C "$GIT_REPO" status --porcelain -- "$GIT_WORKSPACE")" ]; then
        echo "$GIT_REPO/$GIT_WORKSPACE has uncommitted changes; refusing to reset it" >&2
        exit 2
    fi
    WORK=$GIT_REPO/$GIT_WORKSPACE
else
    # always the same path, so the NetBeans index of the project stays warm between runs
    WORK=$EVAL_DIR/work/project
fi
RESULTS=$EVAL_DIR/results/$(date +%Y%m%d-%H%M%S)
mkdir -p "$RESULTS"
echo "Results: $RESULTS"

# built-in tools both modes may use; edits are auto-accepted, everything else is denied
BASE_TOOLS=(Read Edit Write Glob Grep "Bash(./compile.sh)" "Bash(javac:*)" "Bash(java:*)"
    "Bash(grep:*)" "Bash(find:*)" "Bash(ls:*)" "Bash(cat:*)" "Bash(git diff:*)" "Bash(git status:*)"
    "Bash(mvn:*)" "Bash(ant:*)")

fresh_project() {
    if [ -n "$GIT_REPO" ]; then
        # back to the committed state (verified clean at start, so only session changes are lost)
        # (unstage first: the diff of the previous session was recorded with git add)
        git -C "$GIT_REPO" reset -q -- "$GIT_WORKSPACE" && git -C "$GIT_REPO" checkout -q -- "$GIT_WORKSPACE" \
            && git -C "$GIT_REPO" clean -fdq -- "$GIT_WORKSPACE"
        return
    fi
    "$HERE/fixture.sh" "$WORK" > /dev/null
    (cd "$WORK" && git init -q && git add -A \
        && git -c user.name=eval -c user.email=eval@example.invalid -c commit.gpgsign=false commit -qm fixture)
}

mcp_config() {
    if [ "$1" = netbeans ]; then
        python3 -c 'import json,sys; print(json.dumps({"mcpServers": {"java": {"command": sys.argv[1], "args": ["--data", sys.argv[2], sys.argv[3]]}}}))' \
            "$DUKEHOFF_MCP" "$EVAL_DIR/nb-data" "$WORK"
    else
        echo '{"mcpServers": {}}'
    fi
}

if [[ ",$MODES," == *",netbeans,"* ]]; then
    # index the fixture once before the timed runs; the first start of a workspace indexes the JDK too
    echo "Warming up the NetBeans index..."
    fresh_project
    python3 "$HERE/warmup.py" "$DUKEHOFF_MCP" "$EVAL_DIR/nb-data" "$WORK" || { echo "warm-up failed" >&2; exit 1; }
fi

for task_dir in "$TASKS_DIR"/$TASKS/; do
    task=$(basename "$task_dir")
    # the prompt without its license header
    prompt=$(sed '1,/-->/d' "$task_dir/prompt.md")
    for mode in ${MODES//,/ }; do
        for rep in $(seq 1 "$REPS"); do
            run=$task-$mode-$rep
            echo "--- $run"
            fresh_project
            allowed=("${BASE_TOOLS[@]}")
            [ "$mode" = netbeans ] && allowed+=(mcp__java)
            [ "$mode" = netbeans ] && [ -n "$PLUGIN" ] && allowed+=(Skill)
            args=(-p "$prompt" --output-format stream-json --verbose --no-session-persistence
                --strict-mcp-config --mcp-config "$(mcp_config "$mode")"
                --permission-mode acceptEdits --max-budget-usd "$BUDGET" --allowedTools "${allowed[@]}")
            [ -n "$MODEL" ] && args+=(--model "$MODEL")
            if [ "$mode" = netbeans ] && [ -n "$PLUGIN" ]; then
                args+=(--plugin-dir "$PLUGIN")
            fi
            # the persistent server's log spans sessions: remember where this one starts
            log_offset=$(cat "$EVAL_DIR"/nb-data/*/userdir/var/log/messages.log 2> /dev/null | wc -c)
            start=$(date +%s)
            if [ -n "$STOP_AFTER" ]; then
                (cd "$WORK" && MCP_TIMEOUT=120000 MCP_TOOL_TIMEOUT=600000 python3 "$HERE/stop_after.py" "$STOP_AFTER" \
                    "$RESULTS/$run.jsonl" -- claude "${args[@]}" 2> "$RESULTS/$run.stderr")
            else
                (cd "$WORK" && MCP_TIMEOUT=120000 MCP_TOOL_TIMEOUT=600000 claude "${args[@]}" > "$RESULTS/$run.jsonl" 2> "$RESULTS/$run.stderr")
            fi
            end=$(date +%s)
            server_log=-
            if [ "$mode" = netbeans ]; then
                server_log=$RESULTS/$run.server.log
                if "$DUKEHOFF_MCP" --data "$EVAL_DIR/nb-data" --check "$WORK" 2>&1 | grep -q '^server: *running'; then
                    # the persistent server (runtime) stays for the next session; NetBeans writes its
                    # log in batches, so give it time, then keep this session's part
                    sleep 10
                    cat "$EVAL_DIR"/nb-data/*/userdir/var/log/messages.log 2> /dev/null \
                        | tail -c +$((log_offset + 1)) > "$server_log" || server_log=-
                else
                    # a server per session (NetBeans installation) exits when Claude Code closes its
                    # stdin; wait so its log is complete
                    for _ in $(seq 60); do pgrep -f "$EVAL_DIR/nb-data" > /dev/null || break; sleep 1; done
                    if pgrep -f "$EVAL_DIR/nb-data" > /dev/null; then
                        # it sometimes hangs on exit; a leftover instance would take over the next start
                        echo "    dukehoff-mcp did not exit within 60 s; killing it"
                        pkill -f "$EVAL_DIR/nb-data"; sleep 5
                    fi
                    cat "$EVAL_DIR"/nb-data/*/userdir/var/log/messages.log > "$server_log" 2> /dev/null || server_log=-
                fi
            fi
            check=$(cd "$WORK" && bash "$task_dir/check.sh" 2>&1)
            status=$?
            (cd "$WORK" && git add -A && git diff --cached) > "$RESULTS/$run.diff"
            printf '%s\n' "$check" > "$RESULTS/$run.check"
            python3 "$HERE/metrics.py" "$RESULTS/$run.jsonl" "$task" "$mode" "$rep" "$status" "$((end - start))" "$server_log" \
                >> "$RESULTS/runs.tsv"
            echo "    $(tail -1 "$RESULTS/runs.tsv" | cut -f4) ($(printf '%s' "$check" | tail -1))"
        done
    done
done

# leave a --repo checkout clean, so the next invocation accepts it
[ -n "$GIT_REPO" ] && fresh_project
[[ ",$MODES," == *",netbeans,"* ]] && "$DUKEHOFF_MCP" --data "$EVAL_DIR/nb-data" --stop "$WORK" 2>&1 | sed 's/^/    /'

python3 "$HERE/summarize.py" "$RESULTS/runs.tsv" | tee "$RESULTS/summary.md"
