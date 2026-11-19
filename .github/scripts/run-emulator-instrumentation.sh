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
# :llm-engine is an Android library test target. Its generated androidTest APK
# declares both package and instrumentation targetPackage as
# "com.mydocanizer.llm.engine.test". ApplicationProvider's filesDir belongs to
# that package (the test log records /data/user/0/.../cache). The root :app
# module's .dmmv applicationId is not installed by connectedDebugAndroidTest.
target_package="com.mydocanizer.llm.engine.test"
instrumentation_package="$target_package"
printf '%s\n' "$target_package" > "$diagnostics_dir/diagnostics-target-package.txt"
printf '%s\n' "$instrumentation_package" > "$diagnostics_dir/instrumentation-package.txt"
adb shell pm path "$target_package" \
  > "$diagnostics_dir/target-app-package-path.txt" \
  2> "$diagnostics_dir/target-app-package-path.stderr" || true
: > "$diagnostics_dir/app-process-diagnostics.txt"
: > "$diagnostics_dir/app-diagnostics-pull.stderr"
: > "$diagnostics_dir/activity-exit-info.txt"
: > "$diagnostics_dir/exit-info-pull.stderr"
# Exit information is independent of run-as access; collect it even if the
# app-private diagnostics directory cannot be read.
adb shell dumpsys activity exit-info "$target_package" \
  > "$diagnostics_dir/activity-exit-info.txt" \
  2> "$diagnostics_dir/exit-info-pull.stderr" || true
if adb shell run-as "$target_package" sh -c 'test -d files/diagnostics' \
    >/dev/null 2> "$diagnostics_dir/run-as-target.stderr"; then
  adb exec-out run-as "$target_package" sh -c '
    for file in files/diagnostics/engine-*.log; do
      [ -f "$file" ] || continue
      printf "\n===== %s =====\n" "$file"
      cat "$file"
    done
  ' > "$diagnostics_dir/app-process-diagnostics.txt" \
    2> "$diagnostics_dir/app-diagnostics-pull.stderr" || true
else
  printf 'No readable app-private diagnostics directory for target package: %s\n' "$target_package" \
    > "$diagnostics_dir/app-diagnostics-pull.stderr"
fi
adb shell ps -A \
  > "$diagnostics_dir/process-list.txt" \
  2> "$diagnostics_dir/process-list.stderr" || true

find "$diagnostics_dir" -type f -printf '%P\n' | sort \
  > "$diagnostics_dir/files.txt" || true
exit "$test_status"
