#!/usr/bin/env bash
#
# ig-walkthrough-capture.sh — logcat/screencap capture harness for a HUMAN-DRIVEN
# Instagram detection walkthrough (GitHub issues #18, #22).
#
# WHY THIS EXISTS / WHY IT DOESN'T DRIVE THE APP ITSELF:
#   Verifying #18 (does tab-selection detection still fire on current Instagram?)
#   and #22 (does the clips-viewer container check false-positive on Explore/
#   Stories browsing?) requires tapping around INSIDE Instagram — Reels tab, home
#   feed, Explore, Stories. Automation is never allowed to drive Instagram/TikTok
#   over adb (instant account-ban risk to the device's real IG login) — that's a
#   hard rule, not a judgment call, and it has no carve-out for "it's just for
#   QA." So a HUMAN drives Instagram by hand from the companion checklist
#   (walkthrough.md) while this script only watches Nudge's own logcat output
#   and grabs screencaps — it never sends a tap/swipe to the device.
#
# WHAT IT DOES:
#   1. Confirms the device is reachable and prints Nudge's installed version.
#   2. Screenshots the Instagram rule config (setup step).
#   3. Clears logcat, then walks the human through 6 segments (a-f), pausing on
#      ENTER between each. Per segment: dumps the FULL logcat (raw, unfiltered —
#      kept in case the detection code logs under a tag/message we didn't
#      anticipate) AND a filtered copy matching known Nudge detection call
#      sites, plus one screencap. Logcat is cleared after each segment so every
#      dump is scoped to exactly that case, not cumulative across the walk.
#   4. After segment a, records which of the known patterns actually showed up
#      (informational discovery log) — see DETECTION_PATTERN below for why this
#      does NOT narrow what later segments filter on.
#
# USAGE:
#   tools/qa/ig-walkthrough-capture.sh
#   ADB_SERIAL=<other-serial> tools/qa/ig-walkthrough-capture.sh
#
# OUTPUT: /home/astraedus/Pictures/screenshots/nudge-qa-ig-<case>-*
#
# Companion doc: tools/qa/walkthrough.md (read that FIRST — it's the human's
# script; this file is just the recorder).

set -euo pipefail

DEVICE="${ADB_SERIAL:-192.168.1.68:5555}"
PKG="dev.vtap.hikarufocus"
OUT_DIR="/home/astraedus/Pictures/screenshots"
mkdir -p "$OUT_DIR"

adbs() { adb -s "$DEVICE" "$@"; }

echo "== HikaruFocus IG-detection walkthrough capture =="
echo "Device: $DEVICE"
echo

# --- device + build sanity (plain adb, never touches Instagram) ---
STATE=$(adbs get-state 2>/dev/null || true)
if [ "$STATE" != "device" ]; then
  echo "ERROR: device $DEVICE not reachable (get-state='$STATE')." >&2
  echo "Try: adb devices ; or ~/bin/astra-pixel-unlock.sh if it's locked." >&2
  exit 1
fi

DUMPSYS=$(adbs shell dumpsys package "$PKG" | tr -d '\r')
VERSION_NAME=$(grep -m1 versionName <<<"$DUMPSYS" | sed 's/^[[:space:]]*//')
VERSION_CODE=$(grep -m1 versionCode <<<"$DUMPSYS" | sed 's/^[[:space:]]*//')
echo "Nudge build: $VERSION_NAME  $VERSION_CODE"
{
  echo "$VERSION_NAME"
  echo "$VERSION_CODE"
} > "$OUT_DIR/nudge-qa-ig-build-info.txt"

cat <<'EOM'

BEFORE STARTING (full detail in tools/qa/walkthrough.md):
  1. In Nudge: Settings -> tap "Version" 7 times -> "Developer Options" appears.
  2. Toggle "Debug Logging" ON.
     WITHOUT THIS the detection log lines below NEVER APPEAR on a release
     build — NudgeLogger only logs when BuildConfig.DEBUG is true OR this
     runtime preference is on (see util/NudgeLogger.kt), and the installed
     build is very likely a release build. Skipping this step means every
     segment below will capture an empty filtered file even if detection is
     working perfectly.
  3. Confirm (or create) a rule: Instagram, Reels feature-blocking ON, a real
     blocking mode (DELAY or HARD_BLOCK — not disabled), no daily-limit set.
EOM
read -rp "Press ENTER once that rule's config screen is on screen (for the setup screenshot)... "
adbs exec-out screencap -p > "$OUT_DIR/nudge-qa-ig-setup-rule-config.png"
echo "Saved: $OUT_DIR/nudge-qa-ig-setup-rule-config.png"

adbs logcat -c
echo "Logcat cleared. Starting segments."

# Content-based (not literal Log TAG) patterns for the known detection call sites, grepped from
# source 2026-08-20: InAppDetector.kt (feature detection/instagram active tab/clips viewer/
# undetected surface lines) and NudgeAccessibilityService.kt (handling block/foreground switch
# lines). Deliberately NOT matching on the literal logcat TAG string: NudgeLogger.inferTag() takes
# the calling class name and truncates to 23 chars, so "NudgeAccessibilityService" (25 chars)
# actually logs as "NudgeAccessibilityServi" — an easy detail to get wrong by hand and silently
# filter everything out. Matching the log MESSAGE text instead is robust to that truncation.
DETECTION_PATTERN='InAppDetector|NudgeAccessibilityServ|instagram active tab|instagram clips viewer|feature detection result|feature detection skipped|feature detection failed|handling block|foreground switch detected|undetected surface'

segment() {
  local case_id="$1" prompt="$2"
  echo
  echo "=== Case ${case_id} ==="
  echo "$prompt"
  read -rp "Press ENTER when this case is done (see walkthrough.md for the full steps)... "
  local raw="$OUT_DIR/nudge-qa-ig-${case_id}-logcat-raw.txt"
  local filtered="$OUT_DIR/nudge-qa-ig-${case_id}-logcat.txt"
  local shot="$OUT_DIR/nudge-qa-ig-${case_id}-screencap.png"
  adbs logcat -v threadtime -d > "$raw"
  grep -iE "$DETECTION_PATTERN" "$raw" > "$filtered" || true
  adbs exec-out screencap -p > "$shot"
  echo "  raw:      $raw"
  echo "  filtered: $filtered ($(wc -l < "$filtered") matching lines)"
  echo "  screencap: $shot"
  adbs logcat -c   # scope the next segment's dump to just that segment
}

segment "a-reels-tab" \
  "Case a (issue #18): open Instagram, tap the Reels tab, let a reel play ~10s. Expect: overlay fires."

# Informational only — every segment still filters on the FULL DETECTION_PATTERN (see comment
# above the variable), because a pattern silent in case a can be exactly the signal a later case
# needs (e.g. "instagram clips viewer detected" fires from the reel PLAYER container per
# InAppDetector.kt, a different code path than the Reels-tab selection check).
DISCOVERED="$OUT_DIR/nudge-qa-ig-discovered-tags.txt"
grep -oiE "$DETECTION_PATTERN" "$OUT_DIR/nudge-qa-ig-a-reels-tab-logcat-raw.txt" | sort -u > "$DISCOVERED" || true
echo "Discovery (case a, informational): $(paste -sd, "$DISCOVERED" 2>/dev/null || echo none)"

segment "b-home-feed" \
  "Case b (issue #18): scroll Instagram's home feed until a reel/clip plays inline or full-screen (~30s)."

segment "c-explore" \
  "Case c (issue #18): open Explore, browse the grid, then tap into a video from Explore."

segment "d-explore-grid-no-video" \
  "Case d (issue #22): browse the Explore grid for ~30s WITHOUT opening any video. Expect: no overlay."

segment "e-stories" \
  "Case e (issue #22): open and watch 2-3 Stories. Expect: no overlay."

segment "f-reels-then-away" \
  "Case f (issue #22): tap Reels tab, IMMEDIATELY go back to Home before a reel loads, then use the home feed normally ~30s. Watch for a stuck/late overlay."

echo
echo "== Capture complete =="
echo "Files: $OUT_DIR/nudge-qa-ig-*"
echo "Hand the raw + filtered logcats, screencaps, build-info, and discovered-tags files to the analyst for the PASS/FAIL writeup."
