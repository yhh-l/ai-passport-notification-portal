# Android release packaging / Android 发布打包

The Android app is a single universal APK because it contains no native ABI-specific libraries. It supports BLE-capable phones and tablets running Android 8.0 (API 26) or newer.

Android 应用不包含按 CPU 架构区分的原生库，因此发布的是一个通用 APK；支持具备 BLE 的 Android 8.0（API 26）及以上手机和平板。

## Private signing material / 私有签名材料

1. Keep one long-lived release keystore. Losing it prevents future APK versions from updating the installed release.
2. Copy `release-signing.properties.example` to `release-signing.properties` and fill in the real values.
3. Keep both the keystore and real properties file outside Git and back them up securely.

1. 必须长期保留同一份发布密钥；密钥丢失后，新 APK 将无法覆盖升级已经安装的版本。
2. 将 `release-signing.properties.example` 复制为 `release-signing.properties`，填写真实值。
3. 密钥库和真实配置不得提交到 Git，并应离线安全备份。

## Build and verify / 构建与验证

```sh
cd android-app
JAVA_HOME=/path/to/jdk17 \
ANDROID_HOME=/path/to/android-sdk \
./tools/package_release.sh
```

The script runs `assembleRelease` and `lintRelease`, verifies APK signing and ZIP alignment with `apksigner` / `zipalign`, then writes the universal APK and `SHA256SUMS.txt` to `android-app/dist/`.

脚本会执行 `assembleRelease`、`lintRelease` 和 `apksigner` 签名与 `zipalign` 对齐验证，并把通用 APK 与 `SHA256SUMS.txt` 输出到 `android-app/dist/`。

## First-run boundary / 首次使用边界

The in-app **Quick setup** button guides users through every required step. Android still requires the user to approve runtime permissions, Notification access and the first secure Bluetooth pairing. These security confirmations cannot be silently granted by an APK.

应用内的“快速启用”会按顺序引导所有必要步骤；但 Android 仍要求用户本人确认运行时权限、通知使用权和首次安全蓝牙配对，APK 无法静默绕过这些系统安全确认。

## Official firmware downloads / 官方固件下载

APK 1.5.0 obtains published firmware metadata and binaries only from `https://ai-passport.folotoy.cn`. It hides packages over 4 MiB or with unsupported metadata, verifies the official byte count and SHA-256, extracts the user application from the official `esp-merged-0x0` package at offset `0x10000`, and saves the normalized image under `Download/AI-Passport` before BLE installation.

APK 1.5.0 只从 `https://ai-passport.folotoy.cn` 获取已发布玩法目录和二进制。超过 4 MiB 或元数据不支持的包不会展示；应用会核对官网大小与 SHA-256，从官方 `esp-merged-0x0` 包的 `0x10000` 偏移提取用户应用，并在 BLE 安装前保存标准化镜像到 `Download/AI-Passport`。

Android 10 and newer use MediaStore and need no broad storage permission. Android 8/9 request `WRITE_EXTERNAL_STORAGE` only when the user starts an official download; the permission is capped at API 28 in the manifest.

Android 10 及以上通过 MediaStore 保存，不需要宽泛存储权限。Android 8/9 仅在用户开始官网下载时请求 `WRITE_EXTERNAL_STORAGE`，Manifest 已将该权限限制到 API 28。
