#!/usr/bin/env bash
# Apply the TrebleDroid (cawilliamson android-17.0) patch tiers to the LineageOS 24 tree, then our own patches.
# Each patch is tried with `git am --3way`; a failing patch is aborted, logged and skipped so the run goes on.
# Usage: apply-patches-a17.sh [tier...]   (default: trebledroid personal nico)
# Report: build/patches-a17.log (OK/SKIP/FAIL per patch). Re-running skips patches whose subject is already applied.
set -uo pipefail
shopt -s nullglob

SRC=/lab/ulphone/build/src17
UP=/lab/ulphone/patches/upstream-cawilliamson-a17
OWN=/lab/ulphone/patches/a17
LOG=/lab/ulphone/build/patches-a17.log
EXCLUDE=/lab/ulphone/patches/exclude-a17.txt   # one "project/patch-file-name" per line (GrapheneOS-only, ...)
TIERS=("${@:-trebledroid personal nico}")
read -r -a TIERS <<<"${TIERS[*]}"

project_path() {   # patch dir name -> tree path (same rules as upstream apply.sh)
    local p
    p="$(tr _ / <<<"$1" | sed -e 's;platform/;;g')"
    case "$p" in
        build) p=build/make ;;
        system/fs/fs/mgr) p=system/fs/fs_mgr ;;
        testing) p=platform_testing ;;
        treble/app) p=treble_app ;;
        vendor/hardware/overlay) p=vendor/hardware_overlay ;;
        packages/modules/DnsResolver) p=packages/modules/DnsResolver ;;
    esac
    echo "$p"
}

tier_dir() {
    case "$1" in
        nico) echo "$OWN" ;;
        *) echo "$UP/$1" ;;
    esac
}

cd "$SRC"
git config --global user.email >/dev/null || git config --global user.email "31937059+frnikho@users.noreply.github.com"
git config --global user.name >/dev/null || git config --global user.name "nico"

for tier in "${TIERS[@]}"; do
    dir="$(tier_dir "$tier")"
    [ -d "$dir" ] || { echo "== $tier: no patch dir, skipped" | tee -a "$LOG"; continue; }
    echo "== tier $tier ($(date -Is))" | tee -a "$LOG"
    for pdir in "$dir"/*/; do
        name="$(basename "$pdir")"
        path="$(project_path "$name")"
        # The path may be a subdirectory of a git project (e.g. test/vts-testcase/hal/treble/vintf)
        if ! git -C "$SRC/$path" rev-parse --git-dir >/dev/null 2>&1; then
            for patch in "$pdir"*.patch; do echo "NOPROJ $tier $name/$(basename "$patch")" >>"$LOG"; done
            continue
        fi
        for patch in "$pdir"*.patch; do
            key="$name/$(basename "$patch")"
            if [ -f "$EXCLUDE" ] && grep -qxF "$key" "$EXCLUDE"; then
                echo "EXCL $tier $key" >>"$LOG"; continue
            fi
            subject="$(grep -m1 '^Subject:' "$patch" | sed 's/^Subject: \(\[PATCH[^]]*\] \)\?//')"
            if git -C "$path" log --format=%s -n 400 | grep -qxF "$subject"; then
                echo "DONE $tier $key" >>"$LOG"; continue
            fi
            if git -C "$path" am --3way --keep-cr -q "$patch" >/dev/null 2>&1; then
                echo "OK   $tier $key" >>"$LOG"
            else
                git -C "$path" am --abort >/dev/null 2>&1
                echo "FAIL $tier $key" >>"$LOG"
            fi
        done
    done
done
echo "== summary: $(grep -c '^OK' "$LOG") OK, $(grep -c '^FAIL' "$LOG") FAIL, $(grep -c '^NOPROJ' "$LOG") NOPROJ" | tee -a "$LOG"
