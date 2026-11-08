#!/usr/bin/env bash
# Sync the LineageOS 24 + TrebleDroid (cawilliamson android-17.0) tree into /lab/ulphone/build/src17
# (a separate btrfs subvolume, so it stays out of snapper snapshots). Safe to re-run: it resumes the sync.
set -euo pipefail

SRC=/lab/ulphone/build/src17
mkdir -p "$SRC" && cd "$SRC"
if [ ! -d .repo ]; then
    repo init -u https://github.com/LineageOS/android.git -b lineage-24.0 --git-lfs --depth=1 --no-clone-bundle
fi
mkdir -p .repo/local_manifests
cp /lab/ulphone/custom/manifests/treble-a17.xml .repo/local_manifests/
repo sync -c --force-sync --optimized-fetch --no-tags --no-clone-bundle -j8 --retry-fetches=5
echo "Sync done: $(du -sh "$SRC" | cut -f1)"
