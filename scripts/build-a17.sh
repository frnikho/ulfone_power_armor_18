#!/usr/bin/env bash
# Build the LineageOS 24 (Android 17) TrebleDroid GSI for the Power Armor 18, then customize it.
# Prerequisites: scripts/sync-a17.sh, then scripts/apply-patches-a17.sh.
# Usage: [JOBS=12] [NINJA_ARGS="-k 0"] build-a17.sh [userdebug|user]
# Output: out/lineage-24.0-<date>-nico-arm64_bvN-<variant>.img (raw build), then customize-gsi.py makes
#         out/system-lineage-a17.img (stock identity, scoped keymaster props, AVB signature).
set -euo pipefail

VARIANT="${1:-userdebug}"
SRC=/lab/ulphone/build/src17
OUT_DIR=/lab/ulphone/out
TARGET="${TARGET:-treble_arm64_bvN}"

export USE_CCACHE=1 CCACHE_EXEC=/usr/bin/ccache CCACHE_DIR=/lab/ulphone/build/ccache CCACHE_COMPRESS=1
mkdir -p "$CCACHE_DIR" "$OUT_DIR"

cd "$SRC"
# Release config: the newest one shipped in build/release (e.g. "cp2a" for Android 17)
RELEASE="${RELEASE:-$(ls build/release/release_configs/*.textproto 2>/dev/null \
    | xargs -n1 basename | sed 's/\.textproto$//' | grep -E '^[a-z]{2}[0-9][a-z]$' | sort | tail -1)}"
[ -n "$RELEASE" ] || { echo "No release config found" >&2; exit 1; }

# TrebleDroid products for LineageOS (generate.sh writes treble_*.mk + AndroidProducts.mk)
(cd device/phh/treble && bash generate.sh lineage)

# TrebleApp is a prebuilt the build expects; it is built separately with Gradle (Java 17)
if [ ! -f treble_app/TrebleApp.apk ]; then
    (cd treble_app && bash build.sh release)
fi

set +u
source build/envsetup.sh >/dev/null
lunch "${TARGET}-${RELEASE}-${VARIANT}"
# lunch only sets LINEAGE_BUILD for lineage_* products; without it vendor/lineage BoardConfig is skipped
export LINEAGE_BUILD="$TARGET"
set -u
ccache -M 60G >/dev/null
# 12 jobs by default: with all 16 cores, soong/metalava/javac peaks ran the 62 GB machine out of memory
make -j"${JOBS:-12}" systemimage

dest="$OUT_DIR/lineage-24.0-$(date -u +%Y%m%d)-nico-${TARGET#treble_}-${VARIANT}.img"
simg2img "$OUT/system.img" "$dest" 2>/dev/null || cp "$OUT/system.img" "$dest"
echo "Raw image: $dest"
