#!/usr/bin/env bash
# One conformance attempt on the emulator: install the pre-boot APKs, run the
# instrumentation inside a bounded window, capture evidence BEFORE the
# pass/fail guard, then guard.
# Usage: scripts/emu-run.sh <fixture-filter> [instrument-timeout-sec]
#
# Why one am instrument per attempt (not per fixture): the observed guest
# failures freeze or crash the WHOLE VM (run 45735253590 shard 0: logcat
# silent 01:28:00-01:45:54, UsageStatsService flush resumes the clock;
# shards 10/45729893709-5: system_server SIGSEGV memcpy+465 in the
# android.display thread at activity launch). A per-fixture watchdog cannot
# run inside a frozen guest; the workflow re-rolls a fresh emulator instead.
# The window (1200 s) is sized to the shard, not blind: the slowest healthy
# pace observed on a TCG VM is 85-150 s per fixture, a typical VM needs
# 3-8 s; the shard holds 6-7 fixtures.
set -u

filter="$1"
tmo="${2:-1200}"

adb install -r app/build/outputs/apk/debug/app-debug.apk || exit 1
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk || exit 1

timeout "$tmo" adb shell am instrument -w -e fixtureFilter "$filter" \
  com.test.jsr.test/androidx.test.runner.AndroidJUnitRunner > instrument.log 2>&1 || true
tail -20 instrument.log

# Evidence BEFORE the guard: a failed attempt must carry logcat and the
# partial result.json files for root-cause analysis (the "System has
# crashed" shard of run 45729893709 shipped no evidence because the script
# exited at the grep guard before the capture).
adb logcat -d > logcat.txt 2>&1 || true
timeout 60 adb shell su 0 ls /data/user/0/com.test.jsr/files/conformance > conffiles.txt 2>&1 || true
mkdir -p app/build/reports/conformance
# Only real result names are collected. When the guest hiccups, `su 0 ls`
# prints its error text ("ls: /data/...: No such file…"); word-splitting that
# output used to create stray files (run ce07399 shard 2: a file literally
# named "ls:" sank the if:always() artifact upload with "Colon :" path error).
while IFS= read -r f; do
  f="${f%$'\r'}"
  case "$f" in
    *.result.json) ;;
    *) continue ;;
  esac
  timeout 30 adb exec-out su 0 cat "/data/user/0/com.test.jsr/files/conformance/$f" \
    > "app/build/reports/conformance/$f" || rm -f "app/build/reports/conformance/$f"
done < conffiles.txt
ls app/build/reports/conformance/ || true

# Vacuous-pass guard: am instrument prints "OK (n tests)" only when every
# test of the filter finished.
grep -q "OK (" instrument.log || { tail -40 instrument.log; exit 1; }
