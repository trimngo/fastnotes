#!/usr/bin/env bash
# Host-only installed-code fixture. No tablet or network is used.
set -eu
test_bash=${BASH:-/bin/bash}
base=$(cd "$(dirname "$0")/../.." && pwd)
scratch=$(mktemp -d "${TMPDIR:-/tmp}/fastnotes-settings-test.XXXXXX")
trap 'rm -rf "$scratch"' EXIT
mkdir -p "$scratch/apk/assets" "$scratch/apk/lib/arm64-v8a"
printf '<manifest package="com.wisky.setting.se01"/>' > "$scratch/apk/AndroidManifest.xml"
printf 'dex\n035\000FAKE_SETTINGS_CODE_1' > "$scratch/apk/classes.dex"
printf 'dex\n035\000FAKE_SETTINGS_CODE_2' > "$scratch/apk/classes2.dex"
printf 'dex\n035\000FAKE_SETTINGS_CODE_10' > "$scratch/apk/classes10.dex"
printf 'PRIVATE_NOTE_SENTINEL' > "$scratch/apk/assets/private.txt"
printf 'NOT_INCLUDED_NATIVE_CODE' > "$scratch/apk/lib/arm64-v8a/libVendor.so"
printf 'NOT_INCLUDED_RESOURCES' > "$scratch/apk/resources.arsc"
(cd "$scratch/apk" && zip -q -r "$scratch/settings.apk" .)
export FAKE_APK="$scratch/settings.apk"
export FAKE_TRACE="$scratch/trace"
cat > "$scratch/adb" <<'FAKE'
#!/bin/bash
set -eu
[ "$1" = -d ] || exit 90
shift
printf '%s\n' "$*" >> "$FAKE_TRACE"
case "$1" in
    get-state)
        if [ "${FAKE_UNAUTHORIZED:-}" = yes ]; then
            echo 'PRIVATE_SERIAL_SENTINEL unauthorized' >&2; exit 1
        fi
        echo device ;;
    shell)
        shift
        case "$1" in
            getprop)
                [ "$#" = 2 ] || exit 91
                case "$2" in
                    ro.product.model) echo 'Viwoods AiPaper' ;;
                    ro.build.display.id) echo '3.15.15' ;;
                    ro.build.version.sdk) echo 33 ;;
                    *) exit 92 ;;
                esac ;;
            dumpsys)
                [ "$#" = 3 ] || exit 93
                case "$2 $3" in
                    'activity activities')
                        echo 'irrelevant PRIVATE_NOTE_SENTINEL PRIVATE_SERIAL_SENTINEL'
                        case "${FAKE_ACTIVITY_STYLE:-top}" in
                            top) echo "topResumedActivity=ActivityRecord{example u0 ${FAKE_PACKAGE:-com.wisky.setting.se01}/.PenGestureCanvas t7}" ;;
                            resumed) echo "mResumedActivity: ActivityRecord{example u0 ${FAKE_PACKAGE:-com.wisky.setting.se01}/.PenGestureCanvas t7}" ;;
                            alternate) echo "ResumedActivity: ActivityRecord{example u0 ${FAKE_PACKAGE:-com.wisky.setting.se01}/.PenGestureCanvas t7}" ;;
                            empty) ;;
                            *) exit 102 ;;
                        esac ;;
                    'window windows')
                        echo 'irrelevant PRIVATE_NOTE_SENTINEL PRIVATE_SERIAL_SENTINEL'
                        case "${FAKE_WINDOW_STYLE:-current}" in
                            current) echo "mCurrentFocus=Window{example u0 ${FAKE_PACKAGE:-com.wisky.setting.se01}/.PenGestureCanvas}" ;;
                            focused) echo 'mCurrentFocus=null'; echo "mFocusedApp=ActivityRecord{example u0 ${FAKE_PACKAGE:-com.wisky.setting.se01}/.PenGestureCanvas t7}" ;;
                            foreign)
                                echo 'mCurrentFocus=Window{example u0 com.private.app/.PRIVATE_NOTE_SENTINEL}'
                                echo 'mFocusedApp=ActivityRecord{example u0 com.wisky.setting.se01/.PenGestureCanvas t7}' ;;
                            empty) ;;
                            *) exit 103 ;;
                        esac ;;
                    'package com.wisky.setting.se01'|'package com.android.settings')
                        echo 'PRIVATE_NOTE_SENTINEL ignored_field'
                        echo 'versionCode=31515 minSdk=29 targetSdk=33'
                        echo 'versionName=3.15.15'
                        echo 'PRIVATE_SERIAL_SENTINEL ignored_field' ;;
                    *) exit 94 ;;
                esac ;;
            pm)
                case "$2" in
                    list)
                        [ "$#" = 5 ] && [ "$3" = packages ] && [ "$4" = -s ] || exit 95
                        if [ "${FAKE_NOT_SYSTEM:-}" != yes ]; then echo "package:$5"; fi ;;
                    path)
                        [ "$#" = 3 ] || exit 96
                        echo "package:${FAKE_PATH:-/system_ext/priv-app/Settings/Settings.apk}" ;;
                    *) exit 97 ;;
                esac ;;
            stat)
                [ "$#" = 4 ] && [ "$2" = -c ] && [ "$3" = %s ] || exit 98
                if [ -n "${FAKE_SIZE:-}" ]; then echo "$FAKE_SIZE"
                else wc -c < "$FAKE_APK" | tr -d ' '; fi ;;
            *) exit 99 ;;
        esac ;;
    pull)
        [ "$#" = 3 ] || exit 100
        if [ "${FAKE_PULL_FAIL:-}" = yes ]; then echo PRIVATE_NOTE_SENTINEL >&2; exit 13; fi
        cp "$FAKE_APK" "$3" ;;
    *) exit 101 ;;
esac
FAKE
chmod +x "$scratch/adb"
script="$base/tools/export-viwoods-settings-code.sh"
"$test_bash" -n "$script"
ADB="$scratch/adb" "$test_bash" "$script" --help > "$scratch/help"
started=$(date +%s)
printf '\n' | ADB="$scratch/adb" "$test_bash" "$script" --output "$scratch/settings-code.zip" > "$scratch/output"
elapsed=$(($(date +%s)-started))
[ "$elapsed" -lt 8 ] || { echo 'Fast fixture unexpectedly waited for timer.' >&2; exit 1; }
mkdir "$scratch/unpacked"
unzip -q "$scratch/settings-code.zip" -d "$scratch/unpacked"
[ "$(find "$scratch/unpacked" -type f | wc -l | tr -d ' ')" = 7 ]
cmp "$scratch/apk/classes.dex" "$scratch/unpacked/code/apk-1/classes.dex"
cmp "$scratch/apk/classes2.dex" "$scratch/unpacked/code/apk-1/classes2.dex"
cmp "$scratch/apk/classes10.dex" "$scratch/unpacked/code/apk-1/classes10.dex"
cmp "$scratch/apk/AndroidManifest.xml" "$scratch/unpacked/code/apk-1/AndroidManifest.xml"
rg -q '^package=com.wisky.setting.se01$' "$scratch/unpacked/metadata.txt"
rg -q '^component=com.wisky.setting.se01/.PenGestureCanvas$' "$scratch/unpacked/metadata.txt"
rg -q '^component_source=activity$' "$scratch/unpacked/metadata.txt"
rg -q '^dex_count=3$' "$scratch/unpacked/metadata.txt"
rg -q '^versionName=3.15.15$' "$scratch/unpacked/metadata.txt"
if rg 'PRIVATE_(NOTE|SERIAL)_SENTINEL|NOT_INCLUDED_(NATIVE_CODE|RESOURCES)' "$scratch/unpacked" "$scratch/output"; then
    echo 'Private/non-code fixture data escaped code filtering.' >&2; exit 1
fi
if ADB="$scratch/adb" "$test_bash" "$script" --output "$scratch/settings-code.zip" > /dev/null 2>&1; then
    echo 'Existing archive was replaced.' >&2; exit 1
fi
printf '\n' | FAKE_PATH='/data/app/~~token==/com.wisky.setting.se01-hash==/base.apk' ADB="$scratch/adb" "$test_bash" "$script" --output "$scratch/updated-code.zip" > /dev/null
for apk_root in system system_ext product vendor odm; do
    apk_path="/$apk_root/priv-app/Test_1-/Settings+@,~=.apk"
    printf '\n' | FAKE_PATH="$apk_path" ADB="$scratch/adb" "$test_bash" "$script" --output "$scratch/path-$apk_root.zip" > /dev/null
    unzip -p "$scratch/path-$apk_root.zip" metadata.txt | rg -Fq "apk_1_source=$apk_path"
done
printf '\n' | FAKE_PACKAGE=com.android.settings ADB="$scratch/adb" "$test_bash" "$script" --output "$scratch/android-code.zip" > /dev/null
for style in resumed alternate; do
    printf '\n' | FAKE_ACTIVITY_STYLE="$style" ADB="$scratch/adb" "$test_bash" "$script" --output "$scratch/activity-$style.zip" > /dev/null
    unzip -p "$scratch/activity-$style.zip" metadata.txt | rg -q '^component_source=activity$'
done
for style in current focused; do
    printf '\n' | FAKE_ACTIVITY_STYLE=empty FAKE_WINDOW_STYLE="$style" ADB="$scratch/adb" "$test_bash" "$script" --output "$scratch/window-$style.zip" > "$scratch/output-window-$style"
    mkdir "$scratch/window-$style"
    unzip -q "$scratch/window-$style.zip" -d "$scratch/window-$style"
    rg -q '^component=com.wisky.setting.se01/.PenGestureCanvas$' "$scratch/window-$style/metadata.txt"
    rg -q '^component_source=window$' "$scratch/window-$style/metadata.txt"
    if rg 'PRIVATE_(NOTE|SERIAL)_SENTINEL' "$scratch/window-$style" "$scratch/output-window-$style"; then exit 1; fi
done

expect_failure() {
    local name=$1
    shift
    : > "$scratch/trace-$name"
    if printf '\n' | env FAKE_TRACE="$scratch/trace-$name" "$@" ADB="$scratch/adb" "$test_bash" "$script" --output "$scratch/$name.zip" > "$scratch/error-$name" 2>&1; then
        echo "Unexpected success for $name" >&2; exit 1
    fi
    [ ! -e "$scratch/$name.zip" ]
    if rg 'PRIVATE_(NOTE|SERIAL)_SENTINEL' "$scratch/error-$name"; then exit 1; fi
}
expect_failure foreground FAKE_PACKAGE=com.private.app
expect_failure no_foreground FAKE_ACTIVITY_STYLE=empty FAKE_WINDOW_STYLE=empty
expect_failure foreign_focus FAKE_ACTIVITY_STYLE=empty FAKE_WINDOW_STYLE=foreign
expect_failure non_system FAKE_NOT_SYSTEM=yes
expect_failure private_path FAKE_PATH=/data/user/0/com.wisky.setting.se01/private.apk
expect_failure traversal FAKE_PATH=/data/app/../../data/user/0/private.apk
expect_failure dot_path FAKE_PATH=/system/./priv-app/Settings.apk
expect_failure root_prefix FAKE_PATH=/systematic/priv-app/Settings.apk
expect_failure app_prefix FAKE_PATH=/data/app-private/com.wisky.setting.se01/base.apk
expect_failure wrong_extension FAKE_PATH=/system/priv-app/Settings/Settings.apk.bak
expect_failure path_space 'FAKE_PATH=/system/priv-app/Settings Dir/Settings.apk'
expect_failure path_dollar 'FAKE_PATH=/system/priv-app/$Settings/Settings.apk'
expect_failure path_backtick 'FAKE_PATH=/system/priv-app/`id`/Settings.apk'
expect_failure path_semicolon 'FAKE_PATH=/system/priv-app/Settings;id/Settings.apk'
expect_failure path_glob 'FAKE_PATH=/system/priv-app/Settings*/Settings.apk'
expect_failure budget FAKE_SIZE=268435457
expect_failure unauthorized FAKE_UNAUTHORIZED=yes
expect_failure read_denied FAKE_PULL_FAIL=yes
for name in foreground no_foreground foreign_focus non_system private_path traversal dot_path root_prefix app_prefix wrong_extension path_space path_dollar path_backtick path_semicolon path_glob budget unauthorized; do
    if rg -q '^pull ' "$scratch/trace-$name"; then
        echo "Read happened before $name validation" >&2; exit 1
    fi
done
echo 'Settings code export fixture checks passed (no physical-device validation).'
