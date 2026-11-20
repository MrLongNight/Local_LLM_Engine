#!/usr/bin/env bash
set -uo pipefail

diagnostics_dir="${RUNNER_TEMP:?RUNNER_TEMP must be set}/local-llm-emulator-diagnostics"
mkdir -p "$diagnostics_dir" || exit 1

# :llm-engine is an Android library test target. Its generated androidTest APK
# declares both package and instrumentation targetPackage as
# "com.mydocanizer.llm.engine.test". The root :app module's .dmmv applicationId
# is not installed by connectedDebugAndroidTest.
target_package="com.mydocanizer.llm.engine.test"
instrumentation_package="$target_package"
printf '%s\n' "$target_package" > "$diagnostics_dir/diagnostics-target-package.txt"
printf '%s\n' "$instrumentation_package" > "$diagnostics_dir/instrumentation-package.txt"
: > "$diagnostics_dir/target-app-package-path.txt"
: > "$diagnostics_dir/target-app-package-path.stderr"
: > "$diagnostics_dir/app-process-diagnostics.txt"
: > "$diagnostics_dir/run-as-target.stderr"
: > "$diagnostics_dir/app-diagnostics-pull.stderr"
: > "$diagnostics_dir/activity-exit-info.txt"
: > "$diagnostics_dir/exit-info-pull.stderr"
: > "$diagnostics_dir/process-list.txt"
: > "$diagnostics_dir/process-list.stderr"

# Gradle removes the library test package during connectedDebugAndroidTest
# teardown. Start collecting before Gradle installs/runs it, and retain the
# latest successful snapshots so they survive that uninstall.
collector_stop_file="$diagnostics_dir/.stop-device-diagnostics-collector"
rm -f "$collector_stop_file"

collect_device_diagnostics() {
  local package_path app_tmp app_err exit_tmp exit_err process_tmp
  local app_captured=false exit_info_captured=false process_list_captured=false

  while [[ ! -e "$collector_stop_file" ]]; do
    package_path="$(adb shell pm path "$target_package" \
      2> "$diagnostics_dir/target-app-package-path.stderr" | tr -d '\r' || true)"

    if [[ "$package_path" == package:* ]]; then
      printf '%s\n' "$package_path" > "$diagnostics_dir/target-app-package-path.txt"

      app_tmp="$diagnostics_dir/.app-process-diagnostics.tmp"
      app_err="$diagnostics_dir/.app-diagnostics-pull.stderr.tmp"
      if adb exec-out run-as "$target_package" sh -c '
        found=0
        for file in files/diagnostics/engine-*.log; do
          [ -f "$file" ] || continue
          printf "\\n===== %s =====\\n" "$file"
          cat "$file" || exit 1
          found=1
        done
        [ "$found" -eq 1 ]
      ' > "$app_tmp" 2> "$app_err"; then
        if [[ -s "$app_tmp" ]]; then
          mv "$app_tmp" "$diagnostics_dir/app-process-diagnostics.txt"
          : > "$diagnostics_dir/run-as-target.stderr"
          : > "$diagnostics_dir/app-diagnostics-pull.stderr"
          app_captured=true
        fi
      elif [[ "$app_captured" != true && -s "$app_err" ]]; then
        cp "$app_err" "$diagnostics_dir/run-as-target.stderr" || true
      fi
      rm -f "$app_tmp" "$app_err"

      exit_tmp="$diagnostics_dir/.activity-exit-info.tmp"
      exit_err="$diagnostics_dir/.exit-info-pull.stderr.tmp"
      if adb shell dumpsys activity exit-info "$target_package" \
          > "$exit_tmp" 2> "$exit_err"; then
        if [[ -s "$exit_tmp" ]]; then
          mv "$exit_tmp" "$diagnostics_dir/activity-exit-info.txt"
          : > "$diagnostics_dir/exit-info-pull.stderr"
          exit_info_captured=true
        fi
      elif [[ "$exit_info_captured" != true && -s "$exit_err" ]]; then
        cp "$exit_err" "$diagnostics_dir/exit-info-pull.stderr" || true
      fi
      rm -f "$exit_tmp" "$exit_err"

      process_tmp="$diagnostics_dir/.process-list.tmp"
      if adb shell ps -A > "$process_tmp" 2> "$diagnostics_dir/process-list.stderr"; then
        if [[ -s "$process_tmp" ]]; then
          mv "$process_tmp" "$diagnostics_dir/process-list.txt"
          process_list_captured=true
        fi
      fi
      rm -f "$process_tmp"
    fi

    sleep 0.25
  done

  if [[ "$app_captured" != true ]]; then
    if [[ ! -s "$run-as-target.stderr" ]]; then
      printf 'No engine-*.log files were readable while target package %s was installed.\n' \
        "$target_package" > "$diagnostics_dir/app-diagnostics-pull.stderr"
    else
      printf 'App-private diagnostics could not be captured while target package %s was installed.\n' \
        "$target_package" > "$diagnostics_dir/app-diagnostics-pull.stderr"
    fi
  fi
  if [[ "$exit_info_captured" != true && ! -s "$diagnostics_dir/exit-info-pull.stderr" ]]; then
    printf 'ActivityManager exit-info was not captured while target package %s was installed.\n' \
      "$target_package" > "$diagnostics_dir/exit-info-pull.stderr"
  fi
  if [[ "$process_list_captured" != true ]]; then
    adb shell ps -A > "$diagnostics_dir/process-list.txt" \
      2> "$diagnostics_dir/process-list.stderr" || true
  fi
}

collect_device_diagnostics &
collector_pid=$!

test_status=0
if gradle --no-daemon :llm-engine:connectedDebugAndroidTest; then
  test_status=0
else
  test_status=$?
fi
printf '%s\n' "$test_status" > "$diagnostics_dir/gradle-exit-code.txt"

: > "$collector_stop_file"
wait "$collector_pid" || true
rm -f "$collector_stop_file"

# Copy reports and the final logcat before the emulator runner shuts down the device.
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

find "$diagnostics_dir" -type f -printf '%P\n' | sort \
  > "$diagnostics_dir/files.txt" || true
exit "$test_status"
