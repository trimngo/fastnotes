#!/usr/bin/env bash
# Read-only USB diagnostics for Viwoods AiPaper / Android 13 / build 3.15.15.
# Bash 3 and ordinary macOS/Linux utilities; adb must already be installed.
set -u
set -o pipefail
umask 077

usage() {
    cat <<'HELP'
Usage: bash collect-viwoods-ink.sh [--output PATH] [--seconds 5..60]

Connect one AiPaper by USB and approve its USB-debugging prompt first.
The collector asks you to draw in Fastnotes System ink, then built-in Notes.
It reads device metadata and counts selected existing log messages. It changes
no tablet settings and never saves raw logs, pen points, notes or device serials.
ADB=/path/to/adb selects an existing Android platform-tools installation.
HELP
}

seconds=15
output="${PWD}/fastnotes-ink-evidence-$(date -u +%Y%m%dT%H%M%SZ).tar.gz"
while [ "$#" -gt 0 ]; do
    case "$1" in
        --help|-h) usage; exit 0 ;;
        --output) [ "$#" -ge 2 ] || { usage >&2; exit 2; }; output=$2; shift 2 ;;
        --seconds) [ "$#" -ge 2 ] || { usage >&2; exit 2; }; seconds=$2; shift 2 ;;
        *) usage >&2; exit 2 ;;
    esac
done
case "$seconds" in ''|*[!0-9]*) usage >&2; exit 2 ;; esac
[ "$seconds" -ge 5 ] && [ "$seconds" -le 60 ] || { usage >&2; exit 2; }
[ -n "$output" ] && [ ! -e "$output" ] && [ ! -L "$output" ] || {
    echo 'The output path already exists or is invalid; choose another --output path.' >&2; exit 2;
}
case "$output" in /*) ;; *) output="$PWD/$output" ;; esac
[ -d "$(dirname "$output")" ] || { echo 'The output directory does not exist.' >&2; exit 2; }
adb_bin=${ADB:-adb}
command -v "$adb_bin" >/dev/null 2>&1 || {
    echo 'Android platform-tools adb was not found. Install it or set ADB=/path/to/adb.' >&2; exit 1;
}
work=$(mktemp -d "${TMPDIR:-/tmp}/fastnotes-ink.XXXXXX") || exit 1
active_pid=''
timer_pid=''
cleanup() {
    [ -z "$timer_pid" ] || kill "$timer_pid" 2>/dev/null || :
    [ -z "$active_pid" ] || kill "$active_pid" 2>/dev/null || :
    rm -rf "$work"
}
trap cleanup EXIT
trap 'exit 130' INT TERM HUP

# Bound even a stalled USB command. These signals affect host adb processes only.
bounded_adb() {
    "$adb_bin" -d "$@" &
    active_pid=$!
    (sleep 10; kill "$active_pid" 2>/dev/null) >/dev/null 2>&1 &
    timer_pid=$!
    wait "$active_pid"
    result=$?
    kill "$timer_pid" 2>/dev/null || :
    wait "$timer_pid" 2>/dev/null || :
    active_pid=''; timer_pid=''
    return "$result"
}

state=$(bounded_adb get-state 2>&1) || {
    case "$state" in
        *unauthorized*) echo 'USB debugging is not authorized. Unlock the tablet and approve its USB-debugging prompt.' >&2 ;;
        *offline*) echo 'The USB tablet is offline. Reconnect its USB cable and unlock it.' >&2 ;;
        *'more than one'*) echo 'More than one USB device is connected. Leave only the AiPaper connected.' >&2 ;;
        *) echo 'No single authorized USB tablet is available. Connect one tablet, unlock it and approve USB debugging.' >&2 ;;
    esac
    echo 'If needed, inspect adb devices locally; serial numbers are not collected by this script.' >&2
    exit 1
}
[ "$(printf '%s\n' "$state" | tr -d '\r' | tail -n 1)" = device ] || { echo 'The USB tablet is not ready.' >&2; exit 1; }
model=$(bounded_adb shell getprop ro.product.model 2>/dev/null | tr -d '\r\n')
build=$(bounded_adb shell getprop ro.build.display.id 2>/dev/null | tr -d '\r\n')
if [ "$model" != 'Viwoods AiPaper' ] || [ "$build" != '3.15.15' ]; then
    echo 'This collector targets Viwoods AiPaper build 3.15.15; the connected device did not match.' >&2
    exit 1
fi

mkdir "$work/report" || exit 1
cat > "$work/report/README.txt" <<EOF
Fastnotes ink investigation, collected $(date -u +%Y-%m-%dT%H:%M:%SZ)
Viwoods AiPaper / build 3.15.15. Read-only USB commands; no device settings changed.
No raw logs, handwriting coordinates, pressure values, notes or serials are saved.

callback-summary.txt counts the exact existing HandWritePolicy marker emitted
after the system policy's onInput for DOWN/MOVE/UP. A count is evidence that this
callback ran, not evidence of visible ink or successful hardware drawing. Zero
can also mean logging was unavailable or filtered. Native failure counts retain
only fixed categories and selected surface error codes, not raw messages or pen
values. SoftSolutionBufTransfer messages include writing-surface init/exit,
connected-to-CPU and reinitialization states, plus lock/dequeue/queue failures.
The first parseable log entry is discarded because logcat -T 1 can include a
previous tail entry. This can also discard one fresh entry; compare sustained
drawing rather than interpreting a single count. Requested and elapsed capture
times and the host logcat exit status are recorded for incomplete captures.

Metadata access via adb shell can differ from app access. Missing module files
do not prove a built-in driver. No driver binary or pressure curve is collected.
WritingProducer owner/client PID snapshots are taken before each phase prompt
and after its capture. --pid/--clients query Binder metadata, not vendor dump
options. Unavailable or permission-restricted queries are recorded explicitly.
EOF

# Android 13 dumpsys --pid uses Binder getDebugPid, not the service dump handler.
# Keep only numeric results; unexpected output and error messages stay in memory.
service_pid() {
    local key=$1 service=$2 value query_status
    value=$(bounded_adb shell dumpsys --pid "$service" 2>/dev/null | head -c 256)
    query_status=$?
    value=$(printf '%s' "$value" | tr -d '\r\n')
    case "$value" in ''|*[!0-9]*) value=unavailable ;; esac
    printf '%s=%s exit=%s\n' "$key" "$value" "$query_status"
}

producer_snapshot() {
    local label=$1 stage=$2 clients query_status
    {
        printf '\nproducer_snapshot phase=%s stage=%s\n' "$label" "$stage"
        service_pid writing_producer.pid WritingProducer
        clients=$(bounded_adb shell dumpsys --clients WritingProducer 2>/dev/null | head -c 4096)
        query_status=$?
        clients=$(printf '%s\n' "$clients" | tr -d '\r' | awk '
            /^Client PIDs: / {
                sub(/^Client PIDs: /, "")
                if ($0 !~ /^[0-9, ]*$/) exit
                if ($0 == "") { print "none"; exit }
                n=split($0, pids, /[, ]+/); out=""; count=0
                for (i=1; i<=n; i++) if (pids[i] ~ /^[0-9]+$/) {
                    if (++count > 32) { print out " limit_reached=true"; exit }
                    out=out (out == "" ? "" : ",") pids[i]
                }
                if (out != "") print out
                exit
            }')
        [ -n "$clients" ] || clients=unavailable
        printf 'writing_producer.clients=%s exit=%s\n' "$clients" "$query_status"
    } >> "$work/report/callback-summary.txt"
}

# All remote commands are fixed reads. Output is limited at the producer and host.
# No full getprop, /proc dump, raw input stream, bugreport or unfiltered uevent.
{ bounded_adb shell 'printf "kernel.release="; uname -r
printf "kernel.version="; uname -v
printf "kernel.machine="; uname -m
for prop in ro.product.model ro.build.display.id ro.hardware ro.board.platform ro.build.version.sdk ro.eink.type; do
    printf "%s=" "$prop"; getprop "$prop"
done
printf "system_server.pid="; pidof system_server
printf "\ninstalled_fastnotes_version:\n"
dumpsys package dev.fastnotes 2>/dev/null | awk "NR>10000 {exit} \$1 ~ /^versionCode=/ {print substr(\$1,1,128); n++} \$1 ~ /^versionName=/ {print substr(\$1,1,128); n++} n>=2 {exit}"
printf "\n/dev/t1000_spi metadata (no device open):\n"
ls -ldn /dev/t1000_spi 2>/dev/null; printf "stat_command_status=%s\n" "$?"
for base in /sys/class/misc/t1000_spi /sys/class/t1000/t1000_spi /sys/class/t1000_spi/t1000_spi /sys/devices/platform/t1000_spi; do
    printf "\ncandidate=%s\n" "$base"
    ls -dn "$base" 2>/dev/null; printf "lookup_status=%s\n" "$?"
    for link in driver driver/module device/driver device/driver/module; do
        target=$(readlink "$base/$link" 2>/dev/null)
        if [ -n "$target" ]; then printf "%s.name=%s\n" "$link" "${target##*/}"; fi
    done
    for leaf in uevent device/uevent; do
        if [ -r "$base/$leaf" ]; then
            printf "%s:\n" "$leaf"
            awk -F= "NR>64 {exit} \$1 ~ /^(DRIVER|MODALIAS|MAJOR|MINOR|DEVNAME|DEVTYPE|OF_NAME)$/ {print substr(\$0,1,256)}" "$base/$leaf"
        fi
    done
done
count=0
for base in /sys/module/*t1000* /sys/module/*T1000*; do
    [ -d "$base" ] || continue
    count=$((count+1)); [ "$count" -le 8 ] || break
    printf "\nmodule=%s\n" "${base##*/}"
    for leaf in version srcversion initstate; do
        if [ -r "$base/$leaf" ]; then
            printf "%s=" "$leaf"; head -c 256 "$base/$leaf"; printf "\n"
        fi
    done
done
for base in /system/lib/modules /vendor/lib/modules /vendor_dlkm/lib/modules /system_dlkm/lib/modules /odm/lib/modules /odm_dlkm/lib/modules /lib/modules; do
    for leaf in modules.dep modules.builtin modules.load; do
        printf "\nindex=%s/%s\n" "$base" "$leaf"
        if [ -r "$base/$leaf" ]; then
            awk "NR>20000 {print \"scan_limit_reached\"; exit} tolower(\$0) ~ /t1000/ {print substr(\$0,1,512); n++; if(n>=32) {print \"match_limit_reached\"; exit}}" "$base/$leaf"
        else printf "unavailable_or_not_readable\n"; fi
    done
done' 2>/dev/null; printf '%s' "$?" > "$work/metadata-status"; } | head -c 65536 > "$work/report/metadata.txt"
metadata_status=$(cat "$work/metadata-status" 2>/dev/null || printf unavailable)
printf '\nmetadata_collection_exit=%s\n' "$metadata_status" >> "$work/report/metadata.txt"
if [ "$(wc -c < "$work/report/metadata.txt")" -ge 65536 ]; then
    printf 'host_metadata_limit_reached=true\n' >> "$work/report/metadata.txt"
fi
service_pid enote_service.pid ENoteSetting >> "$work/report/metadata.txt"

cat > "$work/filter.awk" <<'AWK'
function record(category) {
    key = category " pid=" $3
    if (!(key in counts)) {
        if (groups >= 128) { summary_limit_reached = 1; return }
        groups++
    }
    counts[key]++
    if (!(key in first)) first[key] = $1 " " $2
    last[key] = $1 " " $2
}
function surface_error(category, pattern, detail) {
    if (match($0, pattern)) {
        detail=substr($0, RSTART, RLENGTH)
        sub(/^.*error:/, "", detail)
        record(category " code=" detail)
    }
}
{
    if ($1 !~ /^[0-9][0-9]-[0-9][0-9]$/ || $3 !~ /^[0-9]+$/) next
    if (!discarded_tail++) next
    if ($6 == "HandWritePolicy:" && NF == 9 && $7 == "test_dirty" && $8 == "onInput:" && $9 == "finish")
        record("system_policy_callback")
    if ($6 == "libpaintworker:") {
        if (index($0, "ioctl T1000_CMD_SEND_MIPI_PEN_TYPE failed")) record("native_pen_type_failure")
        if (index($0, "ioctl T1000_CMD_SEND_MIPI_PEN_WIDTH failed")) record("native_pen_width_failure")
        if (index($0, "ioctl T1000_CMD_SET_MIPI_PRESSURE_LEVEL failed")) record("native_pressure_failure")
        if (index($0, "ioctl T1000_CMD_SEND_MIPI_HANDWRITING_RANGE_MULTI failed")) record("native_region_failure")
    }
    if ($6 == "SoftSolutionBufTransfer:") {
        if (match($0, /WritingSurface::init#[0-9]+ mConnectedToCpu:[01]([[:space:]]|$)/)) {
            detail=substr($0, RSTART, RLENGTH)
            sub(/^.*mConnectedToCpu:/, "", detail); sub(/[[:space:]].*$/, "", detail)
            record("soft_surface_init connected_to_cpu=" detail)
        }
        if (index($0, "WritingSurface # exit()")) record("soft_surface_exit")
        if (match($0, /WritingSurface::sendBuffer#[0-9]+ sendBuffer isInit:[01], isShouldInit:[01]([[:space:]]|$)/)) {
            detail=substr($0, RSTART, RLENGTH)
            sub(/^.*isInit:/, "", detail); sub(/[[:space:]]*$/, "", detail)
            split(detail, state, ", isShouldInit:")
            record("soft_surface_send_state is_init=" state[1] " should_init=" state[2])
        }
        surface_error("soft_surface_lock_failure", "WritingSurface::[A-Za-z]+#[0-9]+ lock error:-?[0-9]+")
        surface_error("soft_surface_dequeue_failure", "WritingSurface::[A-Za-z]+#[0-9]+ dequeueBuffer error:-?[0-9]+")
        surface_error("soft_surface_queue_failure", "WritingSurface::[A-Za-z]+#[0-9]+ queueBuffer error:-?[0-9]+")
    }
}
END {
    total = 0
    for (key in counts) {
        print key " count=" counts[key] " first=" first[key] " last=" last[key]
        total += counts[key]
    }
    print "recognized_messages=" total
    if (summary_limit_reached) print "summary_group_limit_reached=true"
}
AWK

phase() {
    label=$1
    instruction=$2
    producer_snapshot "$label" before_prompt
    printf '\n%s\nPress Enter when ready; then draw continuously for %s seconds.\n' "$instruction" "$seconds"
    IFS= read -r answer || { echo 'Input closed; no comparison captured.' >&2; exit 1; }
    fifo="$work/log-pipe"
    mkfifo "$fifo" || exit 1
    awk -f "$work/filter.awk" < "$fifo" > "$work/phase-summary" &
    filter_pid=$!
    # -T 1 starts near the current log tail without clearing the tablet log.
    started=$(date +%s)
    "$adb_bin" -d logcat -v threadtime -T 1 'HandWritePolicy:D' 'libpaintworker:D' 'SoftSolutionBufTransfer:D' '*:S' > "$fifo" 2>/dev/null &
    active_pid=$!
    (sleep "$seconds"; kill "$active_pid" 2>/dev/null) >/dev/null 2>&1 &
    timer_pid=$!
    wait "$active_pid"
    log_status=$?
    kill "$timer_pid" 2>/dev/null || :
    wait "$timer_pid" 2>/dev/null || :
    active_pid=''; timer_pid=''
    wait "$filter_pid"
    elapsed=$(($(date +%s)-started))
    rm -f "$fifo"
    {
        printf '\nphase=%s requested_seconds=%s elapsed_seconds=%s host_logcat_exit=%s\n' "$label" "$seconds" "$elapsed" "$log_status"
        cat "$work/phase-summary"
    } >> "$work/report/callback-summary.txt"
    producer_snapshot "$label" after_capture
    echo 'Capture finished.'
}

phase fastnotes_system_ink 'Open Fastnotes Ink Test. After pressing Enter here, tap System ink (again if already selected), then draw.'
phase built_in_notes 'Open the built-in Viwoods Notes app and prepare an empty writing area.'

# No overwrite: create the destination exclusively before packaging the small report.
(set -C; : > "$output") 2>/dev/null || { echo 'The output path now exists; choose another path.' >&2; exit 1; }
if ! tar -czf "$output" -C "$work" report; then
    rm -f "$output"
    echo 'Could not create the report archive.' >&2
    exit 1
fi
printf '\nSaved: %s\nAttach this tar.gz to the Fastnotes conversation.\n' "$output"
