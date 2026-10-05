#!/usr/bin/env bash
#
# test-cert-guard.sh, device-free tests for the bench signing-key guard.
#
# The defect (2026-09-29): `APK=debug scripts/device-qa.sh` silently swapped the bench Pixel's
# RELEASE-signed Nudge for a DEBUG-signed one, and every later CI install died with
# INSTALL_FAILED_UPDATE_INCOMPATIBLE. So what is worth pinning is the whole CLASS "an install
# that would change the bench's signing key", not one mode: every direction (release->debug,
# debug->release, debug-key-A -> debug-key-B), the opt-in, the backstop when a signer cannot be
# read, and that a refusal touches NOTHING on the device.
#
# `adb` and `apksigner` are PATH shims that simulate a device: a fake APK is a text file naming
# its key, the shim adb keeps "what is installed" in a state file and refuses an `install -r`
# across keys exactly as Android does. device-qa.sh is SOURCED (it only runs main when executed)
# so the real do_install / install_apk / exit trap are what run here.
#
# If the real APKs are on disk (laptop), apk_signer_* is also checked against them with the real
# apksigner; on CI those cases are skipped, not failed.
#
# Run: scripts/test-cert-guard.sh
#
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

REAL_APKSIGNER="$(command -v apksigner || true)"

pass=0
fail=0
ok()  { echo "  ok   - $1"; pass=$((pass + 1)); }
bad() { echo "  FAIL - $1" >&2; fail=$((fail + 1)); }
check() { # <name> <command...>, passes when the command succeeds
  local name="$1"; shift
  if "$@"; then ok "$name"; else bad "$name"; fi
}

# ─── Shims ────────────────────────────────────────────────────────────────────
SHIMS="$TMP/shims"; STATE="$TMP/state"; mkdir -p "$SHIMS" "$STATE"
ADB_LOG="$STATE/adb.log"

# A fake APK is a file whose first line is `KEY=<release|debug|debug-other|unsigned>`.
fake_apk() { printf 'KEY=%s\n' "$2" >"$1"; }

cat >"$SHIMS/apksigner" <<'EOF'
#!/usr/bin/env bash
# apksigner verify --print-certs <apk>, canned output per fake key.
apk="${!#}"
key="$(sed -n 's/^KEY=//p' "$apk" 2>/dev/null | head -1)"
case "$key" in
  release)
    echo "Signer #1 certificate DN: CN=Raedus Labs, OU=Nudge, O=Raedus Labs, L=Brisbane, ST=QLD, C=AU"
    echo "Signer #1 certificate SHA-256 digest: 164298109C7B195A0DEA96D017694AC5B136B52F6E165BA417D3F3F1CD92C066" ;;
  debug)
    echo "Signer #1 certificate DN: C=US, O=Android, CN=Android Debug"
    echo "Signer #1 certificate SHA-256 digest: 5e84be56a18b5fc934bfa865d71728d708adff0e78b4592512fff747aafce9bb" ;;
  debug-other) # another machine's debug keystore: same DN, different key
    echo "Signer #1 certificate DN: C=US, O=Android, CN=Android Debug"
    echo "Signer #1 certificate SHA-256 digest: 1111111111111111111111111111111111111111111111111111111111111111" ;;
  *) echo "DOES NOT VERIFY"; exit 1 ;;
esac
EOF

cat >"$SHIMS/adb" <<'EOF'
#!/usr/bin/env bash
# A one-app device. $STATE/installed holds the fake APK currently "installed" (absent = none).
echo "$*" >>"$ADB_LOG"
[[ "${1:-}" == "-s" ]] && shift 2
key_of() { sed -n 's/^KEY=//p' "$1" | head -1; }
installed="$STATE/installed"
cmd="${1:-}"; shift || true
case "$cmd" in
  shell)
    case "$1 $2" in
      "pm path")
        [[ -f "$installed" ]] &&
          printf 'package:/data/app/~~abc==/dev.vtap.hikarufocus-xyz==/base.apk\r\npackage:/data/app/~~abc==/dev.vtap.hikarufocus-xyz==/split_config.arm64_v8a.apk\r\n'
        exit 0 ;;
      "pm uninstall") rm -f "$installed"; echo Success ;;
      "pm list") [[ -f "$installed" ]] && printf 'package:dev.vtap.hikarufocus\r\n'; exit 0 ;;
      *) : ;;
    esac ;;
  pull)
    [[ -n "${SHIM_PULL_FAIL:-}" || ! -f "$installed" ]] && { echo "adb: error: failed to stat remote object" >&2; exit 1; }
    cp "$installed" "$2" ;;
  install)
    apk="${!#}"
    if [[ -f "$installed" && "$(key_of "$installed")" != "$(key_of "$apk")" ]]; then
      echo "Performing Streamed Install"
      echo "adb: failed to install $apk: Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: Package dev.vtap.hikarufocus signatures do not match previously installed version; ignoring!]"
      exit 1
    fi
    cp "$apk" "$installed"; echo "Performing Streamed Install"; echo Success ;;
esac
EOF
chmod +x "$SHIMS/apksigner" "$SHIMS/adb"

APKS="$TMP/apks"; mkdir -p "$APKS"
for k in release debug debug-other unsigned; do fake_apk "$APKS/$k.apk" "$k"; done

reset_device() { # <installed key or "none">
  rm -f "$STATE/installed" "$ADB_LOG"; : >"$ADB_LOG"
  [[ "$1" == none ]] || cp "$APKS/$1.apk" "$STATE/installed"
}
installed_key() { sed -n 's/^KEY=//p' "$STATE/installed" 2>/dev/null | head -1; }

# run_qa <out-file> <env...> -- <bash snippet>: source device-qa.sh in a subshell against the
# shims, with a throwaway HOME (its WORK dir lands under $HOME/.cache), then run the snippet.
run_qa() {
  local out="$1"; shift
  local envs=()
  while [[ "$1" != "--" ]]; do envs+=("$1"); shift; done; shift
  env -i PATH="$SHIMS:/usr/bin:/bin" HOME="$TMP/home" STATE="$STATE" ADB_LOG="$ADB_LOG" \
    "${envs[@]}" bash -c ". '$ROOT/scripts/device-qa.sh'; $1" >"$out" 2>&1
}

# ─── 1. cert_decision: the whole table ─────────────────────────────────────────
echo "cert_decision:"
# shellcheck source=scripts/cert-guard.sh
. "$ROOT/scripts/cert-guard.sh"
decide() { [[ "$(cert_decision "$1" "$2" "$3")" == "$4" ]]; }
check "nothing installed -> fresh"                decide none aa 0 fresh
check "nothing installed, opted in -> fresh"      decide none aa 1 fresh
check "same key -> same"                          decide aa aa 0 same
check "different key, no opt-in -> refuse"        decide aa bb 0 refuse
check "different key, ALLOW_CERT_SWITCH=1 -> switch" decide aa bb 1 switch
check "opt-in is exactly 1 (\"true\" refuses)"    decide aa bb true refuse
check "installed unreadable -> unknown"           decide "" bb 0 unknown
check "incoming unreadable -> unknown"            decide aa "" 0 unknown

# ─── 2. Signer readers (shimmed apksigner) ────────────────────────────────────
echo "signer readers:"
PATH="$SHIMS:$PATH"
check "digest is lowercased" \
  test "$(apk_signer_sha256 "$APKS/release.apk")" = 164298109c7b195a0dea96d017694ac5b136b52f6e165ba417d3f3f1cd92c066
check "unsigned APK -> empty digest, nonzero" bash -c "! . '$ROOT/scripts/cert-guard.sh'; PATH='$SHIMS':\$PATH; ! apk_signer_sha256 '$APKS/unsigned.apk'"
check "debug DN is recognised"   signer_is_debug "$(apk_signer_dn "$APKS/debug.apk")"
check "release DN is not debug"  bash -c ". '$ROOT/scripts/cert-guard.sh'; ! signer_is_debug 'CN=Raedus Labs, OU=Nudge'"

# ─── 3. pull_installed_apk (shimmed adb) ──────────────────────────────────────
echo "pull_installed_apk:"
export STATE ADB_LOG
pull_rc() { local rc=0; pull_installed_apk SERIAL dev.vtap.hikarufocus "$TMP/pulled.apk" || rc=$?; echo "$rc"; }
reset_device release
check "installed -> 0, base.apk pulled (split + CRLF ignored)" test "$(pull_rc)" = 0
check "pulled the base.apk path" grep -q 'pull /data/app/~~abc==/dev.vtap.hikarufocus-xyz==/base.apk ' "$ADB_LOG"
reset_device none
check "not installed -> 1" test "$(pull_rc)" = 1
reset_device release
check "pull fails -> 2" test "$(SHIM_PULL_FAIL=1 pull_rc)" = 2

# ─── 4. do_install end to end, every direction ────────────────────────────────
echo "do_install (device-qa.sh sourced, shimmed device):"
OUT="$TMP/out.txt"
no_uninstall() { ! grep -q 'pm uninstall' "$ADB_LOG"; }
no_install()   { ! grep -qE '(^| )install ' "$ADB_LOG"; }

# The 2026-09-29 defect: release on the bench, debug incoming, nobody opted in.
reset_device release
rc=0; run_qa "$OUT" -- "do_install '$APKS/debug.apk'" || rc=$?
check "release -> debug, no opt-in: exits 2"                test "$rc" = 2
check "  ...and uninstalls NOTHING"                         no_uninstall
check "  ...and installs NOTHING"                           no_install
check "  ...bench still carries the release key"            test "$(installed_key)" = release
check "  ...names the opt-in"                               grep -q 'ALLOW_CERT_SWITCH=1 APK=main scripts/device-qa.sh' "$OUT"
check "  ...names the way back"                             grep -qF "$CERT_RESTORE_CMD" "$OUT"

# The reverse direction: the next lane's APK=main over a debug bench.
reset_device debug
rc=0; run_qa "$OUT" -- "do_install '$APKS/release.apk'" || rc=$?
check "debug -> release, no opt-in: exits 2, device untouched" \
  bash -c "[[ $rc == 2 ]] && ! grep -q 'pm uninstall' '$ADB_LOG' && [[ \$(sed -n 's/^KEY=//p' '$STATE/installed') == debug ]]"

# Two debug keystores: both DEBUGGABLE, still incompatible. Why the guard compares digests.
reset_device debug-other
rc=0; run_qa "$OUT" -- "do_install '$APKS/debug.apk'" || rc=$?
check "debug key A -> debug key B, no opt-in: exits 2" test "$rc" = 2

# Opted in: uninstall, install, say so loudly, and the exit trap prints the way back.
reset_device release
rc=0; run_qa "$OUT" ALLOW_CERT_SWITCH=1 -- "do_install '$APKS/debug.apk'" || rc=$?
check "release -> debug, ALLOW_CERT_SWITCH=1: succeeds"     test "$rc" = 0
check "  ...uninstalls before installing" \
  bash -c "grep -n 'pm uninstall' '$ADB_LOG' | head -1 | cut -d: -f1 | { read -r u; i=\$(grep -nE '(^| )install ' '$ADB_LOG' | head -1 | cut -d: -f1); [[ -n \$u && -n \$i && \$u -lt \$i ]]; }"
check "  ...bench now carries the debug key"                test "$(installed_key)" = debug
check "  ...banner says state was WIPED"                     grep -q 'WIPED' "$OUT"
check "  ...exit trap prints the restore command"           grep -q 'now carrying a DEBUG-signed Nudge' "$OUT"

# Opted in, switching BACK: no debug reminder afterwards.
reset_device debug
rc=0; run_qa "$OUT" ALLOW_CERT_SWITCH=1 -- "do_install '$APKS/release.apk'" || rc=$?
check "debug -> release, opted in: succeeds, no debug reminder" \
  bash -c "[[ $rc == 0 ]] && ! grep -q 'DEBUG-signed' '$OUT' && [[ \$(sed -n 's/^KEY=//p' '$STATE/installed') == release ]]"

# Same key: an in-place update that keeps the bench state.
reset_device release
rc=0; run_qa "$OUT" -- "do_install '$APKS/release.apk'" || rc=$?
check "release -> release: succeeds without uninstalling" bash -c "[[ $rc == 0 ]] && ! grep -q 'pm uninstall' '$ADB_LOG'"

# Nothing installed: a plain install, no opt-in needed.
reset_device none
rc=0; run_qa "$OUT" -- "do_install '$APKS/debug.apk'" || rc=$?
check "fresh bench -> debug: succeeds, and still reminds" bash -c "[[ $rc == 0 ]] && grep -q 'DEBUG-signed' '$OUT'"

# Backstop: the installed signer is unreadable, so only adb's refusal reveals the mismatch.
reset_device release
rc=0; run_qa "$OUT" SHIM_PULL_FAIL=1 -- "do_install '$APKS/debug.apk'" || rc=$?
check "unreadable signer + UPDATE_INCOMPATIBLE: exits 2"   test "$rc" = 2
check "  ...in plain English, not the raw adb line"        grep -q 'differently-signed' "$OUT"
check "  ...and uninstalls NOTHING"                         no_uninstall

rc=0; reset_device release
run_qa "$OUT" SHIM_PULL_FAIL=1 ALLOW_CERT_SWITCH=1 -- "do_install '$APKS/debug.apk'" || rc=$?
check "unreadable signer + UPDATE_INCOMPATIBLE, opted in: switches" \
  bash -c "[[ $rc == 0 ]] && [[ \$(sed -n 's/^KEY=//p' '$STATE/installed') == debug ]]"

# ─── 5. APK=debug refuses BEFORE building ──────────────────────────────────────
echo "install_apk APK=debug:"
FAKE_REPO="$TMP/repo"; mkdir -p "$FAKE_REPO/app/build/outputs/apk/debug"
printf '#!/usr/bin/env bash\necho gradle-ran >>"%s/gradle.log"\n' "$STATE" >"$FAKE_REPO/gradlew"
chmod +x "$FAKE_REPO/gradlew"
cp "$APKS/debug.apk" "$FAKE_REPO/app/build/outputs/apk/debug/app-debug.apk"

reset_device release; rm -f "$STATE/gradle.log"
rc=0; run_qa "$OUT" APK=debug -- "REPO_ROOT='$FAKE_REPO'; QA_TARGET=refusal-alert; install_apk" || rc=$?
check "release bench, APK=debug, no opt-in: exits 2"          test "$rc" = 2
check "  ...before gradle runs"                               test ! -e "$STATE/gradle.log"
check "  ...rerun hint is the real invocation" \
  grep -q 'ALLOW_CERT_SWITCH=1 APK=debug scripts/device-qa.sh refusal-alert' "$OUT"
check "  ...bench untouched"                                  no_uninstall

reset_device debug; rm -f "$STATE/gradle.log"
rc=0; run_qa "$OUT" APK=debug SKIP_VERSION_CHECK=1 -- "REPO_ROOT='$FAKE_REPO'; install_apk" || rc=$?
check "debug bench, APK=debug: builds + installs in place, reminds" \
  bash -c "[[ $rc == 0 ]] && [[ -e '$STATE/gradle.log' ]] && ! grep -q 'pm uninstall' '$ADB_LOG' && grep -q 'DEBUG-signed' '$OUT'"

# ─── 6. Real APKs with the real apksigner (laptop only) ───────────────────────
echo "real APKs:"
REAL_DEBUG="${REAL_DEBUG_APK:-$ROOT/app/build/outputs/apk/debug/app-debug.apk}"
REAL_RELEASE="${REAL_RELEASE_APK:-}"
real() { PATH="$(dirname "$REAL_APKSIGNER"):/usr/bin:/bin" "$@"; }
if [[ -n "$REAL_APKSIGNER" && -f "$REAL_DEBUG" ]]; then
  check "real debug APK: DEBUG key" signer_is_debug "$(real apk_signer_dn "$REAL_DEBUG")"
  check "real debug APK: 64-hex digest" bash -c "[[ \$(PATH='$(dirname "$REAL_APKSIGNER")':/usr/bin:/bin; . '$ROOT/scripts/cert-guard.sh'; apk_signer_sha256 '$REAL_DEBUG') =~ ^[0-9a-f]{64}$ ]]"
else
  echo "  skip - no apksigner or no debug APK at $REAL_DEBUG"
fi
if [[ -n "$REAL_APKSIGNER" && -n "$REAL_RELEASE" && -f "$REAL_RELEASE" ]]; then
  check "real release APK: not the DEBUG key" bash -c ". '$ROOT/scripts/cert-guard.sh'; PATH='$(dirname "$REAL_APKSIGNER")':/usr/bin:/bin; ! signer_is_debug \"\$(apk_signer_dn '$REAL_RELEASE')\""
  if [[ -f "$REAL_DEBUG" ]]; then
    check "real release vs real debug: refuse" \
      test "$(cert_decision "$(real apk_signer_sha256 "$REAL_RELEASE")" "$(real apk_signer_sha256 "$REAL_DEBUG")" 0)" = refuse
  fi
else
  echo "  skip - set REAL_RELEASE_APK=<nudge-main.apk> to check a real release APK"
fi

echo
echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
