# 电池真容与耗电卫士 (BatteryGuardian) — 免装环境获取 APK 指南

## 方法一：免安装 Android Studio，使用 GitHub 云端一键生成 APK（推荐，耗时约 2 分钟）

工程内置了 `.github/workflows/build-apk.yml` 自动打包脚本，你**无需在电脑上安装任何开发环境**：

1. 登录 GitHub 新建一个仓库（公开或私有均可）。
2. 将本压缩包解压后的所有文件（注意包含隐藏的 `.github` 文件夹）直接拖拽上传到仓库根目录并点击 **Commit changes**。
3. 点击仓库顶部的 **Actions** 标签页，等待 `Build Android APK` 任务变成绿色勾号（约 2 分钟）。
4. 点进该任务，在底部的 **Artifacts** 区域点击 **`BatteryGuardian-debug-apk`** 即可下载编译好的 `app-debug.apk` 到手机直接安装！

## 方法二：使用 Android Studio 本地编译 APK

1. 打开 Android Studio，选择 **Open** 并选中解压后的工程根目录。
2. 等待 Gradle 自动同步完成后，点击顶部菜单 **Build -> Build Bundle(s) / APK(s) -> Build APK(s)**。
3. 编译完成后在 `app/build/outputs/apk/debug/app-debug.apk` 即可找到安装包。
