# Bluetooth Multi-Firmware Installer: Setup and Verification

## First installation

This release changes the full flash partition map. Do not write only the installer application at the old factory offset. The first installation must use USB or a compatible web flasher that writes the merged image at `0x0`:

```text
build-installer/AI-Passport-Bluetooth-Installer-merged-0x0.bin
```

With ESP-IDF, an offset-aware installation is:

```sh
idf.py -B build-installer flash
```

That command writes the bootloader, partition table, factory installer and initial OTA data at their generated offsets. Never write a standalone application image at `0x0`.

## Partition map

| Name | Offset | Size | Purpose |
|---|---:|---:|---|
| `factory` | `0x10000` | `0x120000` | Resident installer |
| `user_a` | `0x130000` | `0x170000` | Play slot 1 |
| `user_b` | `0x2a0000` | `0x170000` | Play slot 2 |
| `cardid` | `0x410000` | `0x4000` | Preserved device data |
| `otadata` | `0x414000` | `0x2000` | Boot selection and rollback state |
| `user_c` | `0x420000` | `0x3e0000` | Play slot 3 |

The small slots are 1.4375 MiB each and the large slot is 3.875 MiB. `cardid` remains at its established offset and must not be overwritten by custom flash commands.

## Android APK

1. Install the APK on Android 8.0 or newer.
2. Allow Nearby devices, notifications and Bluetooth system prompts. Android 8–11 also requires location permission and the system Location switch for BLE scanning.
3. Power on the device. The APK scans for `PassportNotify`; on the first connection, compare and approve the six-digit code shown by the phone and device.
4. After connection, the APK reads all three slots, total free capacity and the largest empty slot.

The file picker accepts several BIN files at once. A local file may be a raw application image or a merged image whose application begins at `0x10000`. Official downloads are checked against catalog size, SHA-256 and the embedded application descriptor.

## Slots and controls

| Input | Result |
|---|---|
| Press Up/Down | Cycle through slots; landing on an installed slot also records it as the current selection |
| Hold OK | Set the selected slot as the boot partition and restart |
| Hold Down | Open the clear confirmation |
| Press OK in confirmation | Clear the selected slot record; any other key cancels |

The APK can also select and clear slots. Replacing an occupied slot requires confirmation. Total free capacity is informational; installation eligibility is always checked against the capacity of the chosen single slot.

## Returning to the installer

OTA rollback is enabled. A newly started play that does not confirm itself as a permanent OTA version normally returns to the factory installer after the next reboot. While a third-party play is running, the factory installer cannot intercept its buttons or force a switch. USB recovery may be required when a play confirms OTA success, rewrites OTA state, assumes another partition layout, or is incompatible with the device despite passing header validation.

## Verification checklist

- The build reports that the factory application is smaller than `0x120000`.
- The partition table contains two `0x170000` slots and one `0x3e0000` slot.
- The merged image ends below `0x130000`, before every play slot and `cardid`.
- The APK completes `assembleDebug` and `lintDebug`.
- Hardware testing still covers pairing, three-slot reporting, size rejection, replacement, clearing, booting and reboot rollback.

A clean build without a connected device validates code and layout only; it is not a substitute for hardware acceptance.
