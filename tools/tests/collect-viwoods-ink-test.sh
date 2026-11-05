#!/usr/bin/env bash
# Host-only fixture. No Android device or network is used.
set -eu
base=$(cd "$(dirname "$0")/../.." && pwd)
scratch=$(mktemp -d "${TMPDIR:-/tmp}/fastnotes-collector-test.XXXXXX")
trap 'rm -rf "$scratch"' EXIT
cat > "$scratch/adb" <<'FAKE'
#!/usr/bin/env bash
set -eu
[ "$1" = -d ] || exit 90
shift
case "$1" in
    get-state)
        if [ "${FAKE_UNAUTHORIZED:-}" = yes ]; then
            echo 'error: device PRIVATE_SERIAL_SENTINEL unauthorized' >&2; exit 1
        fi
        echo device ;;
    shell)
        if [ "$#" -gt 2 ]; then
            case "$2" in
                getprop)
                    [ "$#" = 3 ] || exit 91
                    case "$3" in
                        ro.product.model) echo 'Viwoods AiPaper' ;;
                        ro.build.display.id) echo '3.15.15' ;;
                        *) exit 92 ;;
                    esac ;;
                dumpsys)
                    [ "$#" = 4 ] || exit 97
                    case "$3 $4" in
                        '--pid ENoteSetting') echo 777 ;;
                        '--pid WritingProducer'|'--clients WritingProducer')
                            if [ "${FAKE_PRODUCER_FAIL:-}" = yes ]; then
                                echo PRIVATE_NOTE_SENTINEL; echo PRIVATE_SERIAL_SENTINEL >&2; exit 13
                            fi
                            if [ "$3" = --pid ]; then echo 888
                            else echo 'Client PIDs: 777, 999'; fi ;;
                        *) exit 98 ;;
                    esac ;;
                *) exit 91 ;;
            esac
        else
            case "$2" in *'uname -r'*'pidof system_server'*'dumpsys package dev.fastnotes'*) ;; *) exit 93 ;; esac
            printf 'kernel.release=4.14-fixture\nsystem_server.pid=777\nversionCode=13\nversionName=0.3.2-test9\n'
            if [ "${FAKE_METADATA_FAIL:-}" = yes ]; then exit 7; fi
        fi ;;
    logcat)
        [ "$#" = 9 ] || exit 94
        [ "$2" = -v ] && [ "$3" = threadtime ] && [ "$4" = -T ] && [ "$5" = 1 ] || exit 95
        [ "$6" = HandWritePolicy:D ] && [ "$7" = libpaintworker:D ] && [ "$8" = SoftSolutionBufTransfer:D ] && [ "$9" = '*:S' ] || exit 99
        # The pre-existing tail must not be counted.
        echo '11-05 08:59:59.000 777 778 D HandWritePolicy: test_dirty onInput: finish'
        echo '11-05 09:00:00.000 777 778 D HandWritePolicy: test_dirty onInput: finish'
        echo '11-05 09:00:00.100 777 778 D HandWritePolicy: test_dirty onInput: finish'
        echo '11-05 09:00:00.150 777 778 D HandWritePolicy: test_dirty ENoteWritingInputListener onInput:: x:PRIVATE_COORDINATE_SENTINEL y:900 pressedValue:PRIVATE_PRESSURE_SENTINEL'
        echo '11-05 09:00:00.200 777 778 E libpaintworker: [libpaintworker.so T1000SpiBufTransfer::setAutoDrawPenPress 100] ioctl T1000_CMD_SET_MIPI_PRESSURE_LEVEL failed press:PRIVATE_PRESSURE_SENTINEL'
        echo '11-05 09:00:00.300 999 888 D OtherApp: PRIVATE_NOTE_SENTINEL'
        echo '11-05 09:00:00.400 777 778 D HandWritePolicy: test_dirty onInput: finish PRIVATE_NOTE_SENTINEL'
        echo '11-05 09:00:00.500 777 778 D SoftSolutionBufTransfer: WritingSurface::init#473 mConnectedToCpu:0'
        echo '11-05 09:00:00.501 777 778 D SoftSolutionBufTransfer: WritingSurface::init#473 mConnectedToCpu:1'
        echo '11-05 09:00:00.600 777 778 E SoftSolutionBufTransfer: WritingSurface::init#482 lock error:-16 PRIVATE_COORDINATE_SENTINEL'
        echo '11-05 09:00:00.700 777 778 E SoftSolutionBufTransfer: WritingSurface::sendBuffer#517 dequeueBuffer error:-1 PRIVATE_PRESSURE_SENTINEL'
        echo '11-05 09:00:00.800 777 778 E SoftSolutionBufTransfer: WritingSurface::sendBuffer#523 queueBuffer error:-32 PRIVATE_NOTE_SENTINEL'
        echo '11-05 09:00:00.900 777 778 W SoftSolutionBufTransfer: WritingSurface::sendBuffer#503 sendBuffer isInit:0, isShouldInit:1'
        echo '11-05 09:00:01.000 777 778 D SoftSolutionBufTransfer: WritingSurface # exit()'
        echo '11-05 09:00:01.100 777 778 D SoftSolutionBufTransfer: unknown PRIVATE_COORDINATE_SENTINEL PRIVATE_PRESSURE_SENTINEL PRIVATE_NOTE_SENTINEL'
        # Ending early must be recorded rather than claiming a full time window.
        exit 0 ;;
    *) exit 96 ;;
esac
FAKE
chmod +x "$scratch/adb"
bash -n "$base/tools/collect-viwoods-ink.sh"
ADB="$scratch/adb" bash "$base/tools/collect-viwoods-ink.sh" --help > "$scratch/help"
started=$(date +%s)
printf '\n\n' | ADB="$scratch/adb" bash "$base/tools/collect-viwoods-ink.sh" --seconds 5 --output "$scratch/report.tar.gz" > "$scratch/output"
elapsed=$(($(date +%s)-started))
[ "$elapsed" -lt 8 ] || { echo 'Fast fake commands unexpectedly waited for timeout.' >&2; exit 1; }
mkdir "$scratch/unpacked"
tar -xzf "$scratch/report.tar.gz" -C "$scratch/unpacked"
[ "$(find "$scratch/unpacked" -type f | wc -l | tr -d ' ')" = 3 ]
summary="$scratch/unpacked/report/callback-summary.txt"
[ "$(awk '/system_policy_callback pid=777 count=2 / {n++} END {print n+0}' "$summary")" = 2 ]
[ "$(awk '/native_pressure_failure pid=777 count=1 / {n++} END {print n+0}' "$summary")" = 2 ]
[ "$(awk '/soft_surface_init connected_to_cpu=0 pid=777 count=1 / {n++} END {print n+0}' "$summary")" = 2 ]
[ "$(awk '/soft_surface_init connected_to_cpu=1 pid=777 count=1 / {n++} END {print n+0}' "$summary")" = 2 ]
[ "$(awk '/soft_surface_lock_failure code=-16 pid=777 count=1 / {n++} END {print n+0}' "$summary")" = 2 ]
[ "$(awk '/soft_surface_dequeue_failure code=-1 pid=777 count=1 / {n++} END {print n+0}' "$summary")" = 2 ]
[ "$(awk '/soft_surface_queue_failure code=-32 pid=777 count=1 / {n++} END {print n+0}' "$summary")" = 2 ]
[ "$(awk '/soft_surface_send_state is_init=0 should_init=1 pid=777 count=1 / {n++} END {print n+0}' "$summary")" = 2 ]
[ "$(awk '/soft_surface_exit pid=777 count=1 / {n++} END {print n+0}' "$summary")" = 2 ]
[ "$(awk '/recognized_messages=10/ {n++} END {print n+0}' "$summary")" = 2 ]
[ "$(awk '/writing_producer.pid=888 exit=0/ {n++} END {print n+0}' "$summary")" = 4 ]
[ "$(awk '/writing_producer.clients=777,999 exit=0/ {n++} END {print n+0}' "$summary")" = 4 ]
rg -q 'enote_service.pid=777 exit=0' "$scratch/unpacked/report/metadata.txt"
if rg 'PRIVATE_(SERIAL|COORDINATE|PRESSURE|NOTE)_SENTINEL' "$scratch/unpacked" "$scratch/output"; then
    echo 'Private fixture text escaped filtering.' >&2; exit 1
fi
rg -q 'metadata_collection_exit=0' "$scratch/unpacked/report/metadata.txt"
if ADB="$scratch/adb" bash "$base/tools/collect-viwoods-ink.sh" --output "$scratch/report.tar.gz" > "$scratch/overwrite" 2>&1; then
    echo 'Existing archive was not protected.' >&2; exit 1
fi
if FAKE_UNAUTHORIZED=yes ADB="$scratch/adb" bash "$base/tools/collect-viwoods-ink.sh" --output "$scratch/unauthorized.tar.gz" > "$scratch/unauthorized" 2>&1; then
    echo 'Unauthorized device unexpectedly accepted.' >&2; exit 1
fi
rg -q 'not authorized' "$scratch/unauthorized"
if rg -q PRIVATE_SERIAL_SENTINEL "$scratch/unauthorized"; then exit 1; fi
printf '\n\n' | FAKE_METADATA_FAIL=yes ADB="$scratch/adb" bash "$base/tools/collect-viwoods-ink.sh" --seconds 5 --output "$scratch/partial.tar.gz" > /dev/null
mkdir "$scratch/partial"
tar -xzf "$scratch/partial.tar.gz" -C "$scratch/partial"
rg -q 'metadata_collection_exit=7' "$scratch/partial/report/metadata.txt"
printf '\n\n' | FAKE_PRODUCER_FAIL=yes ADB="$scratch/adb" bash "$base/tools/collect-viwoods-ink.sh" --seconds 5 --output "$scratch/producer-denied.tar.gz" > "$scratch/producer-output"
mkdir "$scratch/producer-denied"
tar -xzf "$scratch/producer-denied.tar.gz" -C "$scratch/producer-denied"
rg -q 'writing_producer.pid=unavailable exit=13' "$scratch/producer-denied/report/callback-summary.txt"
rg -q 'writing_producer.clients=unavailable exit=13' "$scratch/producer-denied/report/callback-summary.txt"
if rg 'PRIVATE_(SERIAL|COORDINATE|PRESSURE|NOTE)_SENTINEL' "$scratch/producer-denied" "$scratch/producer-output"; then exit 1; fi
if ADB="$scratch/no-such-adb" bash "$base/tools/collect-viwoods-ink.sh" --output "$scratch/missing.tar.gz" > "$scratch/missing" 2>&1; then exit 1; fi
rg -q 'adb was not found' "$scratch/missing"
if ADB="$scratch/adb" bash "$base/tools/collect-viwoods-ink.sh" --seconds 0 > /dev/null 2>&1; then exit 1; fi
echo 'Collector fixture checks passed (no physical-device validation).'
