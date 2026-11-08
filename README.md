# Ulefone Power Armor 18 — Android 17 GSI lab

Notes, scripts and patches from turning an **Ulefone Power Armor 18 (EEA)** into a daily driver running a
self-built **LineageOS 24 (Android 17) TrebleDroid GSI**, with VoLTE, 120 Hz, working FBE and a few
kernel fixes for MediaTek quirks.

> ⚠️ **Disclaimer.** Everything here was done on one device. Several steps can hard-brick or wipe your phone.
> Read the [pitfalls](#pitfalls-read-before-touching-anything) first and keep full partition backups
> (mtkclient works in BROM/preloader mode on this device). No warranty.

## Device

| | |
|---|---|
| SoC | MediaTek MT6877 (Dimensity 900), Mali-G68 |
| Kernel | 4.19.191 (no erofs, no BPF features expected by Android ≥ 15) |
| Stock | Android 13 `Power_Armor_18_AF4_EEA_V16` (Nov 2024), vendor Android 12 (VNDK 31) |
| Layout | A/B, Virtual A/B, dynamic partitions, Treble |
| Keymaster | TrustKernel KM 4.1 (TEE) |
| Display | 1080×2408, 60/90/120 Hz |
| Storage | 512 GB UFS 3.1 |

## Current state

| Feature | Status | Notes |
|---|---|---|
| Boot / FBE | ✅ | Needs the stock identity spoof (see below) |
| 4G/5G data, calls | ✅ | |
| VoLTE / VoWiFi | ✅ | ImsServiceMtk + CarrierConfig patch (tested on French carrier Free) |
| 120 Hz + 60 Hz idle | ✅ | Baked static RRO + SF idle timer |
| Camera (Camera2 FULL + RAW) | ✅ | Use any third-party camera app |
| Fingerprint (side) | ✅ | HIDL fallback patch |
| NFC | ✅ | |
| HW composition (battery) | ✅ | Kernel hexpatch, see [HRT bound fix](#kernel-hrt-bound-fix-gpu-composition--battery-drain) |
| USB HID keyboard/mouse gadget | ✅ | Kernel hexpatch, see [f_hid fix](#kernel-f_hid-fix-usb-keyboardmouse-gadget) |
| Play Integrity | BASIC only | Public keybox |

## Repository layout

```
scripts/        build, patch, flash, DSU test, stock restore, V16 upgrade
patches/a17/    our patches on top of LineageOS 24 + TrebleDroid (git format-patch, per project)
patches/upstream-cawilliamson-a17/   TrebleDroid A17 patch tiers (trebledroid / personal / rom)
patches/exclude-a17.txt              upstream patches to skip
custom/
  config-*.json      customize-gsi.py configs (props, removals, added files, AVB)
  manifests/         local manifest for the A17 tree
  overlays/          RRO sources (120 Hz refresh rate, status bar padding)
  magisk-overlay/    boot-log overlay.d (persists logs to /metadata, DSU watchdog)
  ventoy/            on-device scripts: USB mass storage, HID, charge limit, key handler
apps/toolbox/   small Gradle-less Android app (QS tiles, USB disk/HID/tether toggles, torch, charge limit)
```

Paths in scripts and configs are hard-coded to `/lab/ulphone`. Adjust them or use the same layout.

**Not included** (create or fetch them yourself):
- `custom/keys/testkey_rsa2048.pem`: the public AOSP test key (`external/avb/test/data/testkey_rsa2048.pem`).
  It is the key Ulefone used for the top-level vbmeta.
- `custom/priv-apps/ImsServiceMtk.apk`: proprietary MediaTek IMS service (from a MediaTek U-based ROM).
- `custom/CarrierConfig-free-volte.apk`: built from `packages/apps/CarrierConfig` with the patch in `patches/a17/`.
- `custom/adb_keys`: your own `~/.android/adbkey.pub`.
- Stock firmware, build tree, images.

## Build

```bash
scripts/sync-a17.sh                    # repo lineage-24.0 + custom/manifests/treble-a17.xml (~134 GB)
scripts/apply-patches-a17.sh           # upstream tiers + patches/a17 (git am --3way, log per patch)
JOBS=12 RELEASE=cp2a scripts/build-a17.sh userdebug
scripts/customize-gsi.py custom/config-a17-lineage-global13-v16.json
```

Target: `treble_arm64_bvN-cp2a-userdebug`. With 62 GB RAM, `-j16` ran out of memory. `JOBS=8–12` works.

`customize-gsi.py` takes the raw GSI and:
- removes `com.android.virt.apex`: its EROFS payload cannot mount on 4.19, and `apexd-bootstrap` fails;
- writes the stock identity into **all** `build.prop` files;
- adds the priv-apps and the patched CarrierConfig;
- re-signs the image with an AVB hashtree footer (testkey). Without that, a DSU boot hangs in first stage.

### Notable patches (`patches/a17/`)

- **Connectivity / system/core / vold**: tolerate BPF-less legacy kernels, ignore dm-verity/AVB setup failures,
  restore the session keyring workaround.
- **frameworks/base**: HIDL fingerprint when the AIDL sensor is virtual, LightsService HIDL fallback, skip fs-verity,
  drop virtualization/aiseal references.
- **device/phh/treble**: VNDK version detection guards, skip `fixSPL` on the PA18 (it breaks the TEE identity).
- **CarrierConfig**: enable VoLTE/VoWiFi for Free (carrier id 1958). Adapt it for your carrier.

## Install

**Test first with DSU** (single boot, stock untouched):
```bash
scripts/test-dsu.sh out/system-lineage-a17-global13-v16.img
```
The bootlog overlay saves logs to `/metadata/nico/<n>/` and reboots a GSI that has not finished booting after 300 s.

**Permanent flash** (wipes data): `scripts/flash-a17.sh <system.img>`. What worked:
- `boot_a`: Magisk-patched stock boot, with the `system` fstab line stripped of `avb=vbmeta_system`/`avb_keys`
  (system mounts without dm-verity).
- `vbmeta_a`, `vbmeta_system_a`, `vendor`, `product`: **stock, untouched**.
- `system_a`: the customized GSI.
- Updates: `fastboot flash system_a <img>` from fastbootd, no wipe.

**Upgrade the firmware/vendor to V16 and keep the GSI + data**: `scripts/upgrade-v16-keep-gsi.sh`.
The V16 preloader already ships on retail units, so the script never writes the preloader.

**Back to stock**: `scripts/restore-stock.sh <firmware_dir>` (LK fastboot, slot a, preloader skipped).

## Pitfalls (read before touching anything)

- **Never modify `vbmeta` or `vbmeta_system`.** A disabled top vbmeta (flags 2/3) leaves the TEE without a valid root of
  trust: /data won't mount and you get a "Can't load Android system" loop, even with stock system. `vbmeta_system` is signed
  with a private Ulefone key and cannot be re-signed. Disable verity in the boot fstab instead (see above).
- **Finish any pending Virtual A/B merge before `fastboot flashing unlock`.** Check for `*-cow` partitions in super.
  If a merge is pending, the unlock wipe leaves both slots in "dm-verity corruption".
- **TrustKernel keymaster accepts only the stock identity.** `ro.product.{brand,device,manufacturer,model,name}` =
  `Ulefone / Power_Armor_18 / Ulefone / Power Armor 18 / Power_Armor_18_EEA`, a matching `security_patch`
  and `ro.build.version.release=13` in every build.prop. Otherwise: `TEE return -38 / Invalid Root of Trust`, FBE fails, reboot loop.
- **Never enable phh "secure" mode** (`/metadata/phh/secure`). It copies `ro.vendor.product.name` (without `_EEA`)
  over the identity: TEE -38 and a bootloop. Recovery: read `/metadata` with mtkclient, `debugfs -w -R "rm /phh/secure"`, write it back.
- **Never `fastboot erase metadata`** on the flashed GSI. FBE keys are lost. Wipe only via stock recovery "Factory data reset".
- **`gsi_tool enable` without `--single-boot` persists.** If the GSI hangs: fastbootd → `fastboot gsi disable`.
- **Don't ship RROs through Magisk magic mount.** Zygote keeps an fd on the overlay APK. In the denylist mount namespace,
  `webview_zygote` can't reopen it and aborts, so every WebView breaks. Bake overlays into `/system/product/overlay` instead.
- **mtkclient**: preloader mode (plug in without buttons) works reliably. Pure BROM fails at EMI init. The fastboot key combo is unreliable.
- **Incremental OTAs break** if the preloader version differs from the rest of the firmware (source hash mismatch on `preloader_raw`).
  Don't downgrade the preloader (anti-rollback / brick risk). Use full scatter ROMs instead.

## Fixes & findings

### VoLTE

You need all of the following:
1. `ImsServiceMtk` as a priv-app in `/system/priv-app`. `adb install` is refused for a platform app on A17.
2. The patched CarrierConfig.
3. `persist.dbg.volte_avail_ovr=1` and `persist.dbg.wfc_avail_ovr=1`.
4. One extra reboot after first boot.
5. In TrebleApp, the **IMS force-enable setting turned on** (`key_ims_force_enable_setting=true`). Otherwise TrebleApp
   resets both props to 0 at boot.

Without VoLTE, calls fall back to 3G (CSFB). Returning to LTE then often leaves a "connected" but dead data session
(`Invalid DataCallResponse` in `TelephonyDebugService`).

### 120 Hz with 60 Hz idle

The vendor overlay sets `config_defaultRefreshRate=61`. `DisplayDeviceConfig` reads it once, before `/data` overlays apply, so the fix has two parts:
- a **static RRO baked into the system image** (`custom/overlays/refreshrate`, `defaultRefreshRate`/`defaultPeakRefreshRate=0`);
- `debug.sf.set_idle_timer_ms=500`, so the display drops to 60 Hz on static content.

### Kernel: HRT bound fix (GPU composition / battery drain)

Symptom: every layer is composed by the GPU (`CLIENT`), even for 2–3 simple layers. That means a high SurfaceFlinger CPU load and battery drain.
Root cause: Ulefone's `copy_hrt_bound_table` computes the real DVFS bandwidth and then **hard-codes the EMI bound table to `{100,100,100,100}`**,
which allows only one full-screen layer. Stock is affected too.
Fix (kernel Nov 7 2024 build): make it use the computed bandwidth (`w22`).

```
magiskboot hexpatch kernel \
  54c50090885e8db976b200b0d6622191890c80d240c50090890cc0f2c812088b00303691e10315aa092500a9 \
  e903162a297d60b354c50090885e8db976b200b0d662219140c50090c812088b00303691e10315aa092500a9
```
Result: layers go to `DEVICE`, no underruns, SF CPU while scrolling drops from ~50% to ~11%.

### Kernel: f_hid fix (USB keyboard/mouse gadget)

MediaTek's `f_hid.c` has a `/* HACK, replace content */` that replaces any configfs report descriptor with a 7-byte "None" one.
To skip it:
```
magiskboot hexpatch kernel 288e00b008a1249109014039 1000001408a1249109014039
```
Then `custom/ventoy/usbhid.sh` exposes a boot keyboard and a boot mouse next to adb.

Both kernel patches are lost on any boot reflash or Magisk update. Re-apply them and keep the original boot image to roll back.

### Other notes

- **Touch boost**: the phh DisplayPolicy hint does not boost the CPU on MTK. `persist.sys.phh.touch_hint=rotate` uses the APP_ROTATE
  power scenario. Settings scroll jank went from 20% to 0.9%.
- **Load average ~15 at idle is cosmetic**: MediaTek kernel threads sleep in D state (`wdtk-*`, `hang_detect`, `ccci_poll`).
- **Battery stats**: `batterystats` mAh values are wrong (the GSI thinks capacity is 2946 mAh, not 9600 mAh). Use percentages and durations.
  The real gauge (SD77561) logs to dmesg as `[bmt]`.
- **Charge limit**: `echo "0 1" > /proc/mtk_battery_cmd/current_cmd` stops charging, `"0 0"` resumes. See `custom/ventoy/toolbox-battery.sh`.
- **USB mass storage**: the configfs `mass_storage` function works alongside adb (USB 2.0, ~40 MB/s), handy as a Ventoy boot key.
  The kernel has no exFAT, so manage ISOs from the PC.
- **Left programmable key** = `KEY_F1` on `mtk-kpd`.
- **Platform-signed RROs** can be installed in `/data` on a test-keys GSI (aapt2 + zipalign + apksigner with the platform key).
  `cmd overlay fabricate` doesn't survive a reboot.

## Credits

- [TrebleDroid](https://github.com/TrebleDroid) / phh for the GSI base
- cawilliamson for the Android 17 TrebleDroid trees and patch tiers
- [LineageOS](https://lineageos.org)
- [mtkclient](https://github.com/bkerler/mtkclient) by bkerler
- Xiaomi `chopin-r-oss` kernel source (same MT6877 platform), used to reverse-engineer the display/USB drivers
