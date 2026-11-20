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
: > "$diagnostics_dir/app-diagnostics-capture-attempts.txt"
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
  local loop_count=0 package_path app_pids app_tmp app_err app_clean_tmp
  local exit_tmp exit_err process_tmp app_captured=false exit_info_captured=false process_list_captured=false
  local last_app_attempt="No app process observed yet."

  while [[ ! -e "$collector_stop_file" ]]; do
    loop_count=$((loop_count + 1))
    package_path="$(adb shell pm path "$target_package" \
      2> "$diagnostics_dir/target-app-package-path.stderr" | tr -d '\r' || true)"

    if [[ "$package_path" == package:* ]]; then
      printf '%s\n' "$package_path" > "$diagnostics_dir/target-app-package-path.txt"

      # run-as cannot read app files until Android has created the package data
      # directory. Gate the attempt on the actual instrumentation process, not
      # merely on pm path (which becomes available immediately after install).
      app_pids="$(adb shell pidof "$target_package" 2>/dev/null | tr -d '\r' || true)"
      if [[ -n "$app_pids" ]]; then
        app_tmp="$diagnostics_dir/.app-process-diagnostics.tmp"
        app_err="$diagnostics_dir/.app-diagnostics-pull.stderr.tmp"
        app_clean_tmp="$diagnostics_dir/.app-process-diagnostics.clean.tmp"

        # The explicit completion marker is important: adb shell/exec transports
        # can return success even when run-as itself prints an error to stdout.
        # Never accept a non-empty error message as a captured diagnostics file.
        if adb shell run-as "$target_package" sh -c '
          set -eu
          found=0
          for file in files/diagnostics/engine-*.log; do
            [ -f "$file" ] || continue
            printf "\\n===== %s =====\\n" "$file"
            cat "$file"
            found=$((found + 1))
          done
          [ "$found" -gt 0 ]
          printf "APP_DIAGNOSTICS_CAPTURE_OK files=%s\\n" "$found"
        ' > "$app_tmp" 2> "$app_err"; then
          tr -d '\r' < "$app_tmp" > "$app_clean_tmp"
          if grep -Eq '^APP_DIAGNOSTICS_CAPTURE_OK files=[1-9][0-9]*$' "$app_clean_tmp" &&
              grep -Eq '^===== files/diagnostics/engine-[0-9]+\.log =====$' "$app_clean_tmp" &&
              grep -q 'APP_PROCESS_LOG_PERSISTENCE_PROBE' "$app_clean_tmp"; then
            sed -E '/^APP_DIAGNOSTICS_CAPTURE_OK files=[0-9]+$/d' "$app_clean_tmp" \
              > "$diagnostics_dir/app-process-diagnostics.txt"
            : > "$diagnostics_dir/run-as-target.stderr"
            : > "$diagnostics_dir/app-diagnostics-pull.stderr"
            app_captured=true
            last_app_attempt="Captured engine logs for app PID(s) ${app_pids}."
            printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$last_app_attempt" \
              >> "$diagnostics_dir/app-diagnostics-capture-attempts.txt"
          else
            last_app_attempt="run-as returned without a valid engine-log completion marker for app PID(s) ${app_pids}."
            if [[ "$app_captured" != true ]]; then
              cp "$app_err" "$diagnostics_dir/run-as-target.stderr" 2>/dev/null || true
              {
                printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$last_app_attempt"
                sed -n '1,20p' "$app_clean_tmp"
              } >> "$diagnostics_dir/app-diagnostics-capture-attempts.txt"
            fi
          fi
        else
          last_app_attempt="run-as command failed for app PID(s) ${app_pids}."
          if [[ "$app_captured" != true ]]; then
            cp "$app_err" "$diagnostics_dir/run-as-target.stderr" 2>/dev/null || true
            {
              printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$last_app_attempt"
              sed -n '1,20p' "$app_tmp" 2>/dev/null || true
              sed -n '1,20p' "$app_err" 2>/dev/null || true
            } >> "$diagnostics_dir/app-diagnostics-capture-attempts.txt"
          fi
        fi
        rm -f "$app_tmp" "$app_err" "$app_clean_tmp"
      fi

      # These snapshots are independent of run-as and are sampled less often
      # so a slow dumpsys call cannot delay the next app-private log attempt.
      if (( loop_count % 4 == 1 )); then
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
    fi

    sleep 0.25
  done

  # Take final independent snapshots after instrumentation teardown as well.
  exit_tmp="$diagnostics_dir/.activity-exit-info.final.tmp"
  if adb shell dumpsys activity exit-info "$target_package" \
      > "$exit_tmp" 2> "$diagnostics_dir/exit-info-pull.stderr" && [[ -s "$exit_tmp" ]]; then
    mv "$exit_tmp" "$diagnostics_dir/activity-exit-info.txt"
    exit_info_captured=true
  else
    rm -f "$exit_tmp"
  fi
  if adb shell ps -A > "$diagnostics_dir/process-list.txt" \
      2> "$diagnostics_dir/process-list.stderr" && [[ -s "$diagnostics_dir/process-list.txt" ]]; then
    process_list_captured=true
  fi

  if [[ "$app_captured" != true ]]; then
    printf 'No valid app-private engine-*.log snapshot was captured for %s. Last attempt: %s\n' \
      "$target_package" "$last_app_attempt" > "$diagnostics_dir/app-diagnostics-pull.stderr"
  fi
  if [[ "$exit_info_captured" != true && ! -s "$diagnostics_dir/exit-info-pull.stderr" ]]; then
    printf 'ActivityManager exit-info was not captured for target package %s.\n' \
      "$target_package" > "$diagnostics_dir/exit-info-pull.stderr"
  fi
  if [[ "$process_list_captured" != true && ! -s "$diagnostics_dir/process-list.stderr" ]]; then
    printf 'Process list was not captured before emulator shutdown.\n' \
      > "$diagnostics_dir/process-list.stderr"
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
