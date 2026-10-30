# AI Passport 动态玩法库

[English](README.md) | [详细安装、升级和兼容性](APP_README.md)

## 开发测试版 3.0

[下载完整固件和配套 Android APK](https://github.com/yhh-l/ai-passport-notification-portal/releases/tag/dynamic-library-v3.0.0)（预发布，尚未实机验收）。

取消固定三个槽位，按固件实际大小自动分配空间。在配套 Android 应用中导入本地 BIN，或搜索、下载官方与社区玩法后，点击「自动分配并安装」。不会自动覆盖已有玩法。

玩法可用总空间 6.75 MiB，每次分配按 64 KiB 对齐；单个镜像最大 3.875 MiB，且需要连续空间。实际数量由固件大小决定。删除释放空间，但本版不会自动整理碎片或移动已有玩法。

设备上短按上/下选玩法、长按 OK 启动；长按下后短按 OK 删除。普通重启返回安装器。设备无需配网，手机从玩法库下载时需要互联网。

**尚未完成实机验收。** 新版启动器和旧玩法迁移需要按[详细说明](APP_README.md)操作；保留旧玩法应分段升级，不能直接刷合并包。公开的 [2.1.0 版本](https://github.com/yhh-l/ai-passport-notification-portal/releases/tag/bluetooth-installer-v2.1.0) 仍为固定槽位，不包含本地新改动。

## 构建

ESP-IDF 5.5.3 环境执行 `tools/test_host.sh`、`tools/build_community_release.sh`。新合并固件位于 `build-dynamic/AI-Passport-Dynamic-Library-3.0.0-merged-0x0.bin`。

Android 需要 JDK 17、Android SDK 36，在 `android-app` 执行 `./gradlew testDebugUnitTest assembleDebug lintDebug`。正式签名见 [RELEASING](android-app/RELEASING.md)。

仅兼容应用镜像，不保证第三方自升级、固定分区地址和独立资源文件可用。校验通过不代表玩法一定运行正常，需要逐个实测。

本社区项目基于 [FoloToy AI Passport](https://gitee.com/FoloToy/ai-passport) BSP，不是官方固件。项目代码采用 [MIT License](LICENSE)，第三方玩法保持各自许可证。
