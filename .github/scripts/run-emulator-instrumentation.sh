#!/usr/bin/env bash
set -uo pipefail

diagnostics_dir="${RUNNER_TEMP:?RUNNER_TEMP must be set}/local-llm-emulator-diagnostics"
mkdir -p "$diagnostics_dir" || exit 1

test_status=0
if gradle --no-daemon :llm-engine:connectedDebugAndroidTest; then
  test_status=0
else
  test_status=$?
fi
printf '%s\n' "$test_status" > "$diagnostics_dir/gradle-exit-code.txt"

# Copy reports and process diagnostics before the emulator runner shuts down the device.
mkdir -p "$diagnostics_dir/reports"
for report_root in \
  llm-engine/build/outputs/androidTest-results \
  llm-engine/build/reports/androidTests; do
  if [[ -d "$report_root" ]]; then
    destination="$diagnostics_dir/reports/$(basename "$report_root")"
    mkdir -p "$destination"
    cp -a "$report_root/." "$destination/" || true
  fi
done

adb logcat -b all -d -v threadtime \
  > "$diagnostics_dir/logcat-all.txt" \
  2> "$diagnostics_dir/logcat-pull.stderr" || true
adb shell pm list packages -3 \
  > "$diagnostics_dir/installed-third-party-packages.txt" \
  2> "$diagnostics_dir/package-list.stderr" || true
: > "$diagnostics_dir/app-process-diagnostics.txt"
: > "$diagnostics_dir/app-diagnostics-pull.stderr"
: > "$diagnostics_dir/activity-exit-info.txt"
: > "$diagnostics_dir/exit-info-pull.stderr"
printf '%s\\n' "No readable app-private diagnostics package discovered yet." > "$diagnostics_dir/diagnostics-package.txt"
while IFS= read -r package_line; do
  package="${package_line#package:}"
  case "$package" in
    *mydocanizer*|*localllm*) ;;
    *) continue ;;
  esac
  if adb shell run-as "$package" sh -c 'test -d files/diagnostics' \
      >/dev/null 2> "$diagnostics_dir/run-as-${package}.stderr"; then
    printf '%s\\n' "$package" > "$diagnostics_dir/diagnostics-package.txt"
    adb exec-out run-as "$package" sh -c '
      for file in files/diagnostics/engine-*.log; do
        [ -f "$file" ] || continue
        printf "\\n===== %s =====\\n" "$file"
        cat "$file"
      done
    ' > "$diagnostics_dir/app-process-diagnostics.txt" \
      2> "$diagnostics_dir/app-diagnostics-pull.stderr" || true
    adb shell dumpsys activity exit-info "$package" \
      > "$diagnostics_dir/activity-exit-info.txt" \
      2> "$diagnostics_dir/exit-info-pull.stderr" || true
    break
  fi
done < "$diagnostics_dir/installed-third-party-packages.txt"
adb shell ps -A \
  > "$diagnostics_dir/process-list.txt" \
  2> "$diagnostics_dir/process-list.stderr" || true

find "$diagnostics_dir" -type f -printf '%P\n' | sort \
  > "$diagnostics_dir/files.txt" || true
exit "$test_status"
