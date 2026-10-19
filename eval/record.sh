#!/usr/bin/env bash
#
# Copyright 2026 Dukehoff GmbH
# SPDX-License-Identifier: Apache-2.0
#

# Records an evaluation task as a terminal video: Claude Code runs interactively (the normal screen,
# not -p) without the MCP server ("baseline", pane A) and with it ("netbeans", pane B), side by side
# in tmux and at the same time, each on its own copy of the repository. asciinema records the screen;
# agg and ffmpeg turn the recording into a GIF and an MP4. Each pane's title shows the real elapsed
# time and, once the agent is done, the task's check (check.sh) and the time it took.
#
# THIS USES YOUR CLAUDE CODE LOGIN AND COSTS TOKENS: every pane is a real session.
#
# Usage: record.sh --task DIR --repo DIR --dukehoff-mcp PATH [--workspace SUBDIR] [--model M]
#                  [--modes baseline,netbeans] [--allow CMDS] [--permission-mode MODE]
#                  [--hints FILE] [--size COLSxROWS] [--timeout MINUTES] [--idle SECONDS]
#   --task       a task directory with prompt.md and check.sh (e.g. oss-tasks/03-rename-overload)
#   --repo       a git checkout; each pane gets a clone of its HEAD (uncommitted changes are not copied)
#   --workspace  the project inside the repository, where the agent starts (default: the root)
#   --modes      one mode records a single pane
#   --allow      further shell commands the agent may run, comma separated (as in run.sh)
#   --permission-mode  passed to claude (default auto; tools not allowed otherwise would ask, and a
#                pane waiting for permission ends the recording)
#   --hints      a CLAUDE.md for the netbeans pane (e.g. the tool hints from the README), put in the
#                folder above its clone
#   --size       the terminal of the recording (default 200x50)
#   --idle       pauses longer than this are shortened in the GIF and MP4 (default 2); the cast keeps
#                the real timing and the pane titles show the real elapsed time
# Environment: RECORD_DIR output directory (default ~/dukehoff-recordings); the panes' clones and the
#              server's data stay in its work/ folder, which shows in the video and must not lie
#              under ~/.claude, where Claude Code refuses edits
# Needs: claude, tmux, asciinema (3.x), agg, ffmpeg, python3, git
# Output: $RECORD_DIR/<time>-<task>/: recording.cast, recording.gif, recording.mp4,
#         summary.md (real time, check, tool calls per pane), the checks and the diffs

set -u

HERE=$(cd "$(dirname "$0")" && pwd)
RECORD_DIR=${RECORD_DIR:-$HOME/dukehoff-recordings}
# fixed paths, so Claude Code's trust and the server's index stay between recordings
WORKDIR=$RECORD_DIR/work
TASK=
REPO=
WORKSPACE=.
DUKEHOFF_MCP=${DUKEHOFF_MCP:-}
MODEL=
MODES=baseline,netbeans
ALLOW=
PERMISSION_MODE=auto
HINTS=
SIZE=200x50
TIMEOUT=20
IDLE=2

while [ $# -gt 0 ]; do
    case "$1" in
        --task) TASK=$(cd "$2" && pwd); shift 2 ;;
        --repo) REPO=$(cd "$2" && pwd); shift 2 ;;
        --workspace) WORKSPACE=$2; shift 2 ;;
        --dukehoff-mcp) DUKEHOFF_MCP=$2; shift 2 ;;
        --model) MODEL=$2; shift 2 ;;
        --modes) MODES=$2; shift 2 ;;
        --allow) ALLOW=$2; shift 2 ;;
        --permission-mode) PERMISSION_MODE=$2; shift 2 ;;
        --hints) HINTS=$(cd "$(dirname "$2")" && pwd)/$(basename "$2"); shift 2 ;;
        --size) SIZE=$2; shift 2 ;;
        --timeout) TIMEOUT=$2; shift 2 ;;
        --idle) IDLE=$2; shift 2 ;;
        *) echo "unknown argument $1" >&2; exit 2 ;;
    esac
done
for tool in claude tmux asciinema agg ffmpeg python3 git; do
    command -v $tool > /dev/null || { echo "$tool is required" >&2; exit 2; }
done
[ -f "$TASK/prompt.md" ] && [ -f "$TASK/check.sh" ] || { echo "--task needs a directory with prompt.md and check.sh" >&2; exit 2; }
[ -d "$REPO/.git" ] || { echo "--repo needs a git checkout" >&2; exit 2; }
if [[ ",$MODES," == *",netbeans,"* ]]; then
    [ -x "$DUKEHOFF_MCP" ] || { echo "give the dukehoff-mcp launcher with --dukehoff-mcp or \$DUKEHOFF_MCP" >&2; exit 2; }
fi
case "$RECORD_DIR" in "$HOME/.claude"*) echo "RECORD_DIR must not lie under ~/.claude" >&2; exit 2 ;; esac
COLS=${SIZE%x*}
ROWS=${SIZE#*x}

# keep the machine awake: an idle-sleeping Mac stalled sessions (see run.sh)
if [ -z "${DUKEHOFF_MCP_RECORD_AWAKE:-}" ] && command -v caffeinate > /dev/null; then
    export DUKEHOFF_MCP_RECORD_AWAKE=1
    exec caffeinate -i "$0" --task "$TASK" --repo "$REPO" --workspace "$WORKSPACE" --dukehoff-mcp "$DUKEHOFF_MCP" \
        ${MODEL:+--model "$MODEL"} --modes "$MODES" ${ALLOW:+--allow "$ALLOW"} --permission-mode "$PERMISSION_MODE" \
        ${HINTS:+--hints "$HINTS"} --size "$SIZE" --timeout "$TIMEOUT" --idle "$IDLE"
fi

# the recorded sessions must be ordinary top-level sessions, not children of a Claude Code session this
# script may run in (inherited variables gave them that session's title and permission state)
for var in $(env | sed -n 's/^\(CLAUDE[A-Z_]*\)=.*/\1/p'); do
    unset "$var"
done
# no cloud accounts for the sessions and their builds (see run.sh)
unset AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_SESSION_TOKEN AWS_PROFILE AWS_DEFAULT_PROFILE
export AWS_CREDENTIAL_PROFILES_FILE=/dev/null AWS_SHARED_CREDENTIALS_FILE=/dev/null AWS_CONFIG_FILE=/dev/null \
    AWS_EC2_METADATA_DISABLED=true

task=$(basename "$TASK")
OUT=$RECORD_DIR/$(date +%Y%m%d-%H%M%S)-$task
mkdir -p "$OUT"
echo "Output: $OUT"
SOCKET=dukehoff-record
TMUX_CMD=(tmux -L "$SOCKET" -f /dev/null)
SHA=$(git -C "$REPO" rev-parse HEAD)
NAME=$(basename "$REPO")

label() {
    case "$1" in
        baseline) echo "A · without Dukehoff MCP" ;;
        netbeans) echo "B · with Dukehoff MCP" ;;
    esac
}

pane_dir() {
    case "$1" in
        baseline) echo "$WORKDIR/a" ;;
        netbeans) echo "$WORKDIR/b" ;;
    esac
}

# a clone of the repository's HEAD, at a fixed path per pane (Claude Code's trust and the server's
# index stay), reset to that commit
fresh_copy() {
    local copy
    copy=$(pane_dir "$1")/$NAME
    if [ ! -d "$copy/.git" ]; then
        mkdir -p "$(dirname "$copy")"
        git clone -q --no-checkout "$REPO" "$copy" || exit 1
    fi
    git -C "$copy" fetch -q "$REPO" "$SHA" 2> /dev/null
    git -C "$copy" checkout -q --force --detach "$SHA" && git -C "$copy" clean -fdxq || exit 1
    if [ "$WORKSPACE" = . ]; then echo "$copy"; else echo "$copy/$WORKSPACE"; fi
}

# built-in tools both panes may use (as in run.sh)
BASE_TOOLS=(Read Edit Write Glob Grep "Bash(./compile.sh)" "Bash(javac:*)" "Bash(java:*)"
    "Bash(grep:*)" "Bash(find:*)" "Bash(ls:*)" "Bash(cat:*)" "Bash(git diff:*)" "Bash(git status:*)"
    "Bash(mvn:*)" "Bash(ant:*)")
IFS=, read -r -a extra <<< "$ALLOW"
for cmd in ${extra[@]+"${extra[@]}"}; do
    BASE_TOOLS+=("Bash($cmd:*)")
done

# Claude Code asks once per folder whether to trust it; answer that before recording
accept_trust() {
    "${TMUX_CMD[@]}" new-session -d -s trust -x "$COLS" -y "$ROWS" -c "$1" \
        "claude --strict-mcp-config --mcp-config '{\"mcpServers\": {}}'"
    local screen
    for _ in $(seq 1 30); do
        sleep 1
        screen=$("${TMUX_CMD[@]}" capture-pane -p -t trust 2> /dev/null)
        if grep -q "trust this folder" <<< "$screen"; then
            "${TMUX_CMD[@]}" send-keys -t trust Down Enter
            sleep 3
            break
        fi
        grep -q "for shortcuts" <<< "$screen" && break
    done
    "${TMUX_CMD[@]}" kill-session -t trust 2> /dev/null
}

prompt=$(sed '1,/-->/d' "$TASK/prompt.md")
"${TMUX_CMD[@]}" kill-server 2> /dev/null
modes=(${MODES//,/ })
for mode in "${modes[@]}"; do
    work=$(fresh_copy "$mode")
    echo "$work" > "$OUT/$mode.work"
    if [ "$mode" = netbeans ]; then
        # next to the clone, not in it: Claude Code reads CLAUDE.md in parent folders too, and inside
        # the project it would be a change (checks look at git status; Commons Lang's build checks
        # every file for a license header)
        rm -f "$(pane_dir "$mode")/CLAUDE.md"
        [ -n "$HINTS" ] && cp "$HINTS" "$(pane_dir "$mode")/CLAUDE.md"
        python3 -c 'import json,sys; print(json.dumps({"mcpServers": {"java": {"command": sys.argv[1], "args": ["--data", sys.argv[2], sys.argv[3]]}}}))' \
            "$DUKEHOFF_MCP" "$WORKDIR/nb-data" "$work" > "$OUT/$mode.mcp.json"
        echo "Warming up the server for $work..."
        python3 "$HERE/warmup.py" "$DUKEHOFF_MCP" "$WORKDIR/nb-data" "$work" || { echo "warm-up failed" >&2; exit 1; }
    else
        echo '{"mcpServers": {}}' > "$OUT/$mode.mcp.json"
    fi
    echo "{\"hooks\": {\"Stop\": [{\"hooks\": [{\"type\": \"command\", \"command\": \"touch '$OUT/$mode.done'\"}]}]}}" \
        > "$OUT/$mode.settings.json"
    accept_trust "$work"

    allowed=("${BASE_TOOLS[@]}")
    [ "$mode" = netbeans ] && allowed+=(mcp__java)
    args=(--strict-mcp-config --mcp-config "$OUT/$mode.mcp.json" --settings "$OUT/$mode.settings.json"
        --permission-mode "$PERMISSION_MODE" --allowedTools "${allowed[@]}")
    [ -n "$MODEL" ] && args+=(--model "$MODEL")
    # the pane waits for the recording to start, then runs the session with the task's prompt
    {
        echo '#!/usr/bin/env bash'
        printf 'while [ ! -e %q ]; do sleep 0.1; done\n' "$OUT/go"
        echo 'clear'
        # the prompt first: --allowedTools takes every argument after it
        printf 'cd %q && MCP_TIMEOUT=120000 MCP_TOOL_TIMEOUT=600000 exec claude' "$work"
        printf ' %q' "$prompt" "${args[@]}"
        echo
    } > "$OUT/$mode.pane.sh"
    chmod +x "$OUT/$mode.pane.sh"
done

# the session: one pane per mode, titled, no status line; the agents may not change the titles
"${TMUX_CMD[@]}" new-session -d -s rec -x "$COLS" -y "$ROWS" "$OUT/${modes[0]}.pane.sh"
for mode in "${modes[@]:1}"; do
    "${TMUX_CMD[@]}" split-window -h -t rec "$OUT/$mode.pane.sh"
done
"${TMUX_CMD[@]}" select-layout -t rec even-horizontal
"${TMUX_CMD[@]}" set -g status off \; set -g focus-events on \; set -g allow-set-title off \
    \; set -g pane-border-status top \; set -g pane-border-format ' #{pane_title} ' \
    \; set -g pane-border-style fg=colour240 \; set -g pane-active-border-style fg=colour240
i=0
for mode in "${modes[@]}"; do
    "${TMUX_CMD[@]}" select-pane -t "rec:0.$i" -T "$(label "$mode")"
    i=$((i + 1))
done

asciinema rec --headless --quiet --overwrite --window-size "$SIZE" --idle-time-limit "$IDLE" \
    --title "$task" --command "tmux -L $SOCKET attach -t rec" "$OUT/recording.cast" &
recorder=$!
sleep 1
start=$(date +%s)
touch "$OUT/go"

clock() { printf '%d:%02d' $(($1 / 60)) $(($1 % 60)); }

# until every pane is done and checked: the elapsed time in the titles, then the result
deadline=$((start + TIMEOUT * 60))
while :; do
    now=$(date +%s)
    pending=0
    i=0
    for mode in "${modes[@]}"; do
        pane="rec:0.$i"
        i=$((i + 1))
        if [ -e "$OUT/$mode.result" ]; then
            continue
        fi
        if [ ! -e "$OUT/$mode.done" ]; then
            if "${TMUX_CMD[@]}" capture-pane -p -t "$pane" 2> /dev/null | grep -q "Do you want to"; then
                echo "$(label "$mode"): waiting for a permission prompt; allow the tool with --allow" | tee "$OUT/$mode.result"
                "${TMUX_CMD[@]}" select-pane -t "$pane" -T "$(label "$mode") · stopped at a permission prompt"
                continue
            fi
            if [ "$now" -ge "$deadline" ]; then
                echo "not done after $TIMEOUT minutes" > "$OUT/$mode.result"
                "${TMUX_CMD[@]}" select-pane -t "$pane" -T "$(label "$mode") · not done after $TIMEOUT min"
                continue
            fi
            pending=1
            "${TMUX_CMD[@]}" select-pane -t "$pane" -T "$(label "$mode") · $(clock $((now - start)))"
            continue
        fi
        if [ ! -e "$OUT/$mode.elapsed" ]; then
            echo $(($(stat -f %m "$OUT/$mode.done" 2> /dev/null || stat -c %Y "$OUT/$mode.done") - start)) > "$OUT/$mode.elapsed"
            work=$(cat "$OUT/$mode.work")
            # check in the background, so the other pane's clock keeps running
            ( cd "$work" && bash "$TASK/check.sh" > "$OUT/$mode.check" 2>&1; echo $? > "$OUT/$mode.status" ) &
        fi
        pending=1
        elapsed=$(cat "$OUT/$mode.elapsed")
        if [ -e "$OUT/$mode.status" ]; then
            if [ "$(cat "$OUT/$mode.status")" = 0 ]; then verdict="check: correct"; else verdict="check: FAILED"; fi
            echo "$verdict" > "$OUT/$mode.result"
            "${TMUX_CMD[@]}" select-pane -t "$pane" -T "$(label "$mode") · done in $(clock "$elapsed") · $verdict"
        else
            "${TMUX_CMD[@]}" select-pane -t "$pane" -T "$(label "$mode") · done in $(clock "$elapsed") · checking..."
        fi
    done
    [ "$pending" = 0 ] && break
    sleep 1
done
# the final screen for a few seconds, then end the sessions and the recording
sleep 6
"${TMUX_CMD[@]}" kill-server 2> /dev/null
wait "$recorder"
# without tmux' goodbye (it leaves the alternate screen, clears it and prints "[server exited]"), so
# the video ends on the results
python3 - "$OUT/recording.cast" << 'EOF'
import sys
lines = open(sys.argv[1]).read().splitlines(True)
for i in range(len(lines) - 1, 0, -1):
    if "[?1049l" in lines[i]:
        open(sys.argv[1], "w").writelines(lines[:i])
        break
EOF

for mode in "${modes[@]}"; do
    work=$(cat "$OUT/$mode.work")
    (cd "$work" && git add -A && git diff --cached) > "$OUT/$mode.diff"
done
python3 "$HERE/record_summary.py" "$OUT" "${modes[@]}" | tee "$OUT/summary.md"
if [[ ",$MODES," == *",netbeans,"* ]]; then
    "$DUKEHOFF_MCP" --data "$WORKDIR/nb-data" --stop "$(cat "$OUT/netbeans.work")" 2>&1 | sed 's/^/    /'
fi

echo "Rendering..."
agg --quiet --idle-time-limit "$IDLE" --last-frame-duration 8 --font-size 14 "$OUT/recording.cast" "$OUT/recording.gif" \
    && ffmpeg -loglevel error -y -i "$OUT/recording.gif" -movflags faststart -pix_fmt yuv420p \
        -vf 'scale=trunc(iw/2)*2:trunc(ih/2)*2' "$OUT/recording.mp4"
ls -lh "$OUT"/recording.*
