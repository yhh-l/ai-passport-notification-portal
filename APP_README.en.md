# Dynamic Play Library 3.0: Setup and Verification

Development test release 3.0; hardware acceptance is pending.

## Getting started

1. Install the matching APK on Android 8.0+. Run the new installer on the device, enable Bluetooth, and grant the requested permissions. Android 8–11 also requires Location permission and the Location switch. Tap Connect device and check the six-digit pairing code for `PassportNotify`.
2. In the phone library, choose several BIN files, or refresh/search the FoloToy catalog and download and verify a play. Catalog downloads require Internet on the phone; the device needs no Wi-Fi setup.
3. Tap Auto allocate and install, then wait for installation completion. Repeat for more plays. Installation is sequential, with no fixed destination slots and no automatic replacement. The app shows total free space and the largest contiguous region.
4. Press Up/Down on the device to select a play and hold OK to launch it. An ordinary reboot returns to the installer. Hold Down and press OK to delete; another key cancels. The phone can also delete plays.

## Storage and compatibility

- 6.75 MiB is available in two regions of 2.875 MiB and 3.875 MiB, separated by protected identity, boot and catalog data.
- Each image occupies its actual size rounded up to 64 KiB. The three-play quota is removed. The 108-entry index covers every physically possible minimum allocation; practical capacity depends on image sizes.
- A single image must fit one contiguous region and cannot exceed 3.875 MiB. Deletion frees its allocation. There is no automatic relocation or compaction across existing plays or protected data. Fragmentation can prevent installation even when total free space is sufficient; manually deleting and reinstalling plays can reclaim contiguous space.
- Supported inputs are compatible application BINs or merged images with their application at `0x10000`. Arbitrary flash backups and plays requiring their own partition layout or extra resource files are not guaranteed to run.
- The custom bootloader consumes a one-shot request before validating and launching the selected image. Ordinary reboot returns to the installer; deep-sleep wake also follows this path. Third-party self-updates, fixed partition assumptions, whole-flash writes and bootloader replacement are unsupported.
- Pools are app-type partitions so third-party firmware can locate its running region. Multiple images share each pool partition, so APIs/self-update logic assuming that the partition starts at the current image are incompatible. Image verification is not runtime compatibility certification.

## Upgrade and data preservation

This release changes the bootloader and partition table; application-only updates are insufficient. No flashing has been performed automatically.

To preserve plays from this project's 2.x three-slot installer, retain ordinary NVS, both play regions and `cardid`. After explicit device-flashing authorization, use the matching segmented bootloader, partition table and installer application (plus generated OTA initial data). Do not erase the whole chip or use the merged image for a data-preserving upgrade. Startup validates and imports up to three entries from the legacy `fw_slots` catalog without moving their bytes; errors block mutation instead of silently discarding existing plays.

`AI-Passport-Dynamic-Library-3.0.0-merged-0x0.bin` is for fresh installation at `0x0`. Its padding can erase ordinary NVS pairing and legacy catalog data. It does not clear an existing 3.0 catalog or images at higher addresses, so it is not a factory-reset image. Clean initialization requires a separately agreed data-removal procedure, not an assumed full-chip erase.

## Development verification

```sh
tools/test_host.sh
# ESP-IDF 5.5.3 environment:
BUILD_DIR=build-dynamic tools/build_community_release.sh
# Android: JDK 17, SDK 36
cd android-app
./gradlew testDebugUnitTest assembleDebug lintDebug
```

This checkout has no generic `tools/validate.sh`, archival tool or `docs/development` guide referenced by the general skills. Use the actual commands above.

Host tests cover allocation, fragmentation, boundaries, 108 minimum allocations, more than three plays, interrupted uploads/reboot, invalid images, catalog commit failure, deletion/reuse, one-shot boot and invalid requests, legacy migration and corrupt-catalog write rejection. Flash/NVS/reset are simulated, not physical power-loss verification. Android tests cover page assembly, reconnect reset and malformed messages.

Hardware acceptance remains: segmented legacy upgrade, at least four installed/runnable plays, different image sizes, BLE reconnect and long transfers, pairing, power interruption during upload/catalog commit/boot-request consumption, deletion/reuse, reboot return and Chinese names. Verify third-party partition dependencies individually.
