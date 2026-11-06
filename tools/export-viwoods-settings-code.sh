#!/usr/bin/env bash
# Read-only installed Settings code export. Requires Bash 3, adb, unzip and zip.
set -u
set -o pipefail
umask 077

usage() {
    cat <<'HELP'
Usage: bash export-viwoods-settings-code.sh [--output PATH]

Open the tablet's Settings → Pen and Gesture test canvas, then run this script.
It reads that known system Settings package's installed APK and exports only
AndroidManifest.xml and classes*.dex. It does not read notes or app data, change
settings, install anything, enable root, or run the exported code.
ADB=/path/to/adb selects an existing Android platform-tools installation.
HELP
}

output="$PWD/fastnotes-settings-code-$(date -u +%Y%m%dT%H%M%SZ).zip"
while [ "$#" -gt 0 ]; do
    case "$1" in
        --help|-h) usage; exit 0 ;;
        --output) [ "$#" -ge 2 ] || { usage >&2; exit 2; }; output=$2; shift 2 ;;
        *) usage >&2; exit 2 ;;
    esac
done
case "$output" in /*) ;; *) output="$PWD/$output" ;; esac
[ -n "$output" ] && [ ! -e "$output" ] && [ ! -L "$output" ] && [ -d "$(dirname "$output")" ] || {
    echo 'Choose a new --output path in an existing directory.' >&2; exit 2;
}
adb_bin=${ADB:-adb}
for command_name in "$adb_bin" unzip zip awk head wc; do
    command -v "$command_name" >/dev/null 2>&1 || {
        echo 'This export needs adb, unzip, zip, awk, head and wc. Install the missing tool or set ADB=/path/to/adb.' >&2
        exit 1
    }
done
if command -v sha256sum >/dev/null 2>&1; then
    hash_command=sha256sum
elif command -v shasum >/dev/null 2>&1; then
    hash_command=shasum
else
    echo 'A SHA-256 utility (sha256sum or shasum) is required.' >&2; exit 1
fi
hash_file() {
    if [ "$hash_command" = sha256sum ]; then sha256sum "$1" | awk '{print $1}'
    else shasum -a 256 "$1" | awk '{print $1}'; fi
}

work=$(mktemp -d "${TMPDIR:-/tmp}/fastnotes-settings.XXXXXX") || exit 1
active_pid=''; timer_pid=''
cleanup() {
    [ -z "$timer_pid" ] || kill "$timer_pid" 2>/dev/null || :
    [ -z "$active_pid" ] || kill "$active_pid" 2>/dev/null || :
    rm -rf "$work"
}
trap cleanup EXIT
trap 'exit 130' INT TERM HUP
bounded_adb() {
    local limit=$1 result
    shift
    "$adb_bin" -d "$@" &
    active_pid=$!
    (sleep "$limit"; kill "$active_pid" 2>/dev/null) >/dev/null 2>&1 &
    timer_pid=$!
    wait "$active_pid"; result=$?
    kill "$timer_pid" 2>/dev/null || :
    wait "$timer_pid" 2>/dev/null || :
    active_pid=''; timer_pid=''
    return "$result"
}

state=$(bounded_adb 10 get-state 2>/dev/null) || {
    echo 'Connect one USB tablet, unlock it and approve USB debugging first.' >&2; exit 1;
}
[ "$(printf '%s' "$state" | tr -d '\r\n')" = device ] || {
    echo 'The USB tablet is not ready.' >&2; exit 1;
}
model=$(bounded_adb 10 shell getprop ro.product.model 2>/dev/null | tr -d '\r\n')
build=$(bounded_adb 10 shell getprop ro.build.display.id 2>/dev/null | tr -d '\r\n')
api=$(bounded_adb 10 shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r\n')
if [ "$model" != 'Viwoods AiPaper' ] || [ "$build" != '3.15.15' ] || [ "$api" != 33 ]; then
    echo 'This exporter is for Viwoods AiPaper, Android 13/API 33, build 3.15.15.' >&2; exit 1
fi

printf '\nOpen Settings → Pen and Gesture → its pen test canvas on the tablet.\nKeep that screen in front, full-screen, then press Enter here.\n'
IFS= read -r answer || { echo 'Input closed; nothing exported.' >&2; exit 1; }
# Keep only component names from foreground metadata, never raw dumps.
component=$(bounded_adb 10 shell dumpsys activity activities 2>/dev/null | awk '
    function get_component(line) {
        if (match(line, /[A-Za-z0-9_.]+\/[A-Za-z0-9_.$]+/)) return substr(line, RSTART, RLENGTH)
        return ""
    }
    /(^|[[:space:]])topResumedActivity[[:space:]:=]/ { c=get_component($0); if (c != "" && top == "") top=c }
    /(^|[[:space:]])mResumedActivity[[:space:]:=]/ { c=get_component($0); if (c != "" && resumed == "") resumed=c }
    /(^|[[:space:]])ResumedActivity[[:space:]:=]/ { c=get_component($0); if (c != "" && fallback == "") fallback=c }
    END { if (top != "") print top; else if (resumed != "") print resumed; else if (fallback != "") print fallback }
')
component_source=activity
case "${component%%/*}" in
    com.wisky.setting.se01|com.android.settings) ;;
    *)
        # Some OEM builds omit the resumed-activity fields. Prefer current focus
        # over the fallback focused app; never select a background Settings app
        # just because its component appears elsewhere in a window dump.
        component=$(bounded_adb 10 shell dumpsys window windows 2>/dev/null | awk '
            function get_component(line) {
                if (match(line, /[A-Za-z0-9_.]+\/[A-Za-z0-9_.$]+/)) return substr(line, RSTART, RLENGTH)
                return ""
            }
            /(^|[[:space:]])mCurrentFocus[[:space:]:=]/ { c=get_component($0); if (c != "" && current == "") current=c }
            /(^|[[:space:]])mFocusedApp[[:space:]:=]/ { c=get_component($0); if (c != "" && fallback == "") fallback=c }
            END { if (current != "") print current; else if (fallback != "") print fallback }
        ')
        component_source=window ;;
esac
package=${component%%/*}
case "$package" in
    com.wisky.setting.se01|com.android.settings) ;;
    *) echo 'The known built-in Settings package is not in front. Open its Pen and Gesture test canvas and try again.' >&2; exit 1 ;;
esac
system_match=$(bounded_adb 10 shell pm list packages -s "$package" 2>/dev/null | tr -d '\r' | awk -v expected="package:$package" '$0 == expected {print "yes"}')
[ "$system_match" = yes ] || {
    echo 'The selected Settings package could not be confirmed as a system app; nothing exported.' >&2; exit 1;
}
paths=$(bounded_adb 10 shell pm path "$package" 2>/dev/null | tr -d '\r') || {
    echo 'Could not resolve installed Settings APK paths.' >&2; exit 1;
}
[ -n "$paths" ] || { echo 'No installed Settings APK path was returned.' >&2; exit 1; }

mkdir -p "$work/export/code" || exit 1
cat > "$work/export/README.txt" <<'README'
Installed Viwoods Settings code for static handwriting API investigation.
Only AndroidManifest.xml and top-level classes*.dex are included.
No assets, resources, native libraries, app data, notes, pen traces or serials.
The device was accessed read-only. No APK was installed and no exported code ran.
The foreground component identifies the screen the user opened; it does not
prove the pen implementation is contained entirely in the selected DEX units.
README
{
    printf 'schema=1\nmodel=%s\nbuild=%s\napi=%s\npackage=%s\ncomponent=%s\ncomponent_source=%s\n' "$model" "$build" "$api" "$package" "$component" "$component_source"
    bounded_adb 10 shell dumpsys package "$package" 2>/dev/null | awk '
        $1 ~ /^versionCode=/ || $1 ~ /^versionName=/ {print substr($1,1,160); if(++n == 2) exit}'
} > "$work/export/metadata.txt"
printf 'apk\tentry\tbytes\tsha256\n' > "$work/export/inventory.tsv"

max_apk_bytes=268435456
max_code_bytes=83886080
max_dex_bytes=25165824
max_zip_bytes=25165824
apk_bytes=0; code_bytes=0; apk_count=0; dex_count=0; entry_count=0
while IFS= read -r item; do
    [ -n "$item" ] || continue
    case "$item" in package:/*) remote=${item#package:} ;; *) echo 'Unexpected APK path response; nothing exported.' >&2; exit 1 ;; esac
    case "$remote" in */../*|*/./*) echo 'An APK source path contained traversal; nothing exported.' >&2; exit 1 ;; esac
    # Exclude app-data paths and shell metacharacters. APK paths can include base64-like install tokens.
    # Use a string ERE: macOS awk treats an unescaped slash inside a regex
    # literal's bracket expression as the closing delimiter (mawk accepts it).
    if ! printf '%s\n' "$remote" | awk '
        $0 ~ "^/(system|system_ext|product|vendor|odm|data/app)/[-A-Za-z0-9_./=+@,~]+[.]apk$" {ok=1}
        END {exit !ok}'; then
        echo 'An APK source path was outside the permitted installed-code locations.' >&2; exit 1
    fi
    apk_count=$((apk_count+1))
    [ "$apk_count" -le 8 ] || { echo 'More than eight APK splits; export budget exceeded.' >&2; exit 1; }
    bytes=$(bounded_adb 10 shell stat -c %s "$remote" 2>/dev/null | tr -d '\r\n') || {
        echo 'Installed APK size could not be read.' >&2; exit 1;
    }
    case "$bytes" in ''|*[!0-9]*) echo 'Invalid installed APK size.' >&2; exit 1 ;; esac
    [ "${#bytes}" -le 9 ] || { echo 'Installed APK exceeds the read budget.' >&2; exit 1; }
    apk_bytes=$((apk_bytes+bytes))
    [ "$bytes" -gt 0 ] && [ "$apk_bytes" -le "$max_apk_bytes" ] || {
        echo 'Installed APKs exceed the 256 MiB read budget; nothing exported.' >&2; exit 1;
    }
    apk="$work/apk-$apk_count.apk"
    printf 'Reading installed Settings APK %s…\n' "$apk_count"
    bounded_adb 60 pull "$remote" "$apk" >/dev/null 2>&1 || {
        echo 'Reading the installed APK failed or timed out; no access was elevated.' >&2; exit 1;
    }
    [ "$(wc -c < "$apk" | tr -d ' ')" = "$bytes" ] || {
        echo 'The APK read was incomplete.' >&2; exit 1;
    }
    printf 'apk_%s_source=%s\napk_%s_bytes=%s\napk_%s_sha256=%s\n' "$apk_count" "$remote" "$apk_count" "$bytes" "$apk_count" "$(hash_file "$apk")" >> "$work/export/metadata.txt"
    # Strict top-level names only. No unzip extraction paths or executable files are used.
    unzip -l "$apk" 2>/dev/null | awk '
        $1 ~ /^[0-9]+$/ && ($4 == "AndroidManifest.xml" || $4 ~ /^classes([2-9]|[1-9][0-9]+)?\.dex$/) {
            if (seen[$4]++) {bad=1; next}
            print $1 " " $4
        }
        END {if(bad) exit 1}
    ' > "$work/entries" || { echo 'APK code inventory was invalid.' >&2; exit 1; }
    mkdir "$work/export/code/apk-$apk_count" || exit 1
    while read -r length entry; do
        [ -n "$entry" ] || continue
        entry_count=$((entry_count+1))
        [ "$entry_count" -le 48 ] || { echo 'Too many code entries; nothing exported.' >&2; exit 1; }
        case "$entry" in
            AndroidManifest.xml) limit=1048576 ;;
            classes*.dex) limit=$max_dex_bytes; dex_count=$((dex_count+1)) ;;
        esac
        [ "${#length}" -le 9 ] && [ "$length" -gt 0 ] && [ "$length" -le "$limit" ] || {
            echo 'A manifest/DEX exceeds its code budget; nothing exported.' >&2; exit 1;
        }
        code_bytes=$((code_bytes+length))
        [ "$code_bytes" -le "$max_code_bytes" ] || {
            echo 'Combined manifest/DEX exceeds the 80 MiB code budget; nothing exported.' >&2; exit 1;
        }
        destination="$work/export/code/apk-$apk_count/$entry"
        unzip -p "$apk" "$entry" 2>/dev/null | head -c $((length+1)) > "$destination" || {
            echo 'A code entry failed its bounded read or CRC check.' >&2; exit 1;
        }
        [ "$(wc -c < "$destination" | tr -d ' ')" = "$length" ] || {
            echo 'A code entry had an unexpected length.' >&2; exit 1;
        }
        printf 'apk-%s\t%s\t%s\t%s\n' "$apk_count" "$entry" "$length" "$(hash_file "$destination")" >> "$work/export/inventory.tsv"
    done < "$work/entries"
    rm -f "$apk"
done <<PATHS
$paths
PATHS
[ "$dex_count" -gt 0 ] || { echo 'No readable DEX code was found in the installed APKs.' >&2; exit 1; }
printf 'apk_count=%s\ndex_count=%s\ncode_bytes=%s\n' "$apk_count" "$dex_count" "$code_bytes" >> "$work/export/metadata.txt"
(cd "$work/export" && zip -q -r -X "$work/code.zip" README.txt metadata.txt inventory.tsv code) || {
    echo 'Could not package the code export.' >&2; exit 1;
}
[ "$(wc -c < "$work/code.zip" | tr -d ' ')" -le "$max_zip_bytes" ] || {
    echo 'Compressed code exceeds the 24 MiB upload budget; nothing exported.' >&2; exit 1;
}
# Create exclusively; do not replace an existing archive, including dangling symlinks.
(set -C; : > "$output") 2>/dev/null || { echo 'The output path now exists; choose another path.' >&2; exit 1; }
if ! cat "$work/code.zip" > "$output"; then rm -f "$output"; exit 1; fi
printf '\nSaved: %s\nOnly installed Settings manifest/DEX code is included; share this ZIP for static analysis.\n' "$output"
