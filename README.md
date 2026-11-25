# EhViewer（自用增强公开版）

本项目基于 [Codeberg 原彩 E 主线 (EhViewer/EhViewer)](https://codeberg.org/EhViewer/EhViewer) 二次开发，保留原工程纯净架构，并在其上移植了日常阅读与本地管理相关的自用增强功能。

## 核心说明

- **独立共存包名**：应用包名为 `com.ys.ehviewer`，可与原版及其他分支版本同时安装在同一台设备上，应用数据相互独立、互不覆盖。
- **开源协议与上游致谢**：严格遵循 **GPL-3.0** 开源协议分发源码。感谢原主线及上游社区所有贡献者的长期维护与基础架构工作。
- **维护声明**：本仓库功能为个人自用需求驱动，包含 AI 辅助开发与重构；不承诺固定更新节奏、长期技术支持或全机型适配，随缘更新，欢迎按 GPL-3.0 协议自行 Fork 定制。

## 新增与增强功能

1. **阅读器边缘对齐、始终居中与自动翻页**
   - 新增页边缘对齐方式与单页/长条浏览始终居中选项。
   - 新增可调间隔的自动翻页模式与控制面板联动。
2. **独立动图播放模式与倍速控制**
   - 新增专用的动图播放浏览模式与加载进度提示。
   - 支持 GIF 与动态 WebP 播放倍速调节、单次播放/循环播放控制，以及阅读器内存释放配合。
3. **本地目录与 ZIP/CBZ 漫画导入**
   - 支持直接导入设备本地图片目录或 `.zip` / `.cbz` 压缩包进入下载列表阅读，无需额外解压。
   - 外部绝对路径条目的移动分类与删除列表项操作仅清理索引映射，**不会误删磁盘上的原始文件夹或压缩包**。
4. **后台下载异常恢复与服务保活加固**
   - 引入序列化指令队列（`DownloadCommands`）与退避恢复状态机（`DownloadRecovery`），在前台受限、系统 `dataSync` 超时或进程回收后可按重启次数预算自动接续中断下载。
   - 下载期间配合 `WakeLock` / `WifiLock` 与 30 秒流式停滞兜底看门狗，减少锁屏断流或单张卡死导致的整体阻塞。
5. **GitHub Release 自动构建与更新通道**
   - 内置应用更新检查与关于页链接指向 GitHub 仓库，自动按设备 ABI 匹配 `arm64-v8a` / `x86_64` / `armeabi-v7a` / `universal` 发行包。

## 构建说明

### 1. GitHub Actions 自动发版

仓库包含 [`.github/workflows/build-release.yml`](.github/workflows/build-release.yml)：

- 推送 `v*` 标签会自动触发 Release 构建，或通过 **Actions -> Build Release APK -> Run workflow** 手动触发（默认仅构建 `arm64-v8a`，可选 `all`）。
- 如需正式密钥签名，请在仓库 Secrets 中配置以下 4 项（未配置时默认回退仓库调试签名密钥）：
  - `SIGNING_KEYSTORE_BASE64`（`.jks` 文件的 Base64 编码）
  - `SIGNING_STORE_PASSWORD`
  - `SIGNING_KEY_ALIAS`
  - `SIGNING_KEY_PASSWORD`

### 2. 本地构建

要求安装 JDK 25、Android SDK / NDK 以及 `rust-toolchain.toml` 指定的 Rust nightly 工具链：

```bash
./gradlew :app:testDefaultDebugUnitTest
./gradlew :app:assembleDefaultRelease -PreleaseAbi=arm64-v8a
```

若使用本地私有签名，可将密钥放于 `.private-signing/release.jks` 并配置 `.private-signing/signing.properties`（该目录已被 `.gitignore` 忽略，切勿提交入库）。

## 开源许可

本项目依据 [GNU General Public License v3.0](LICENSE) 发布。
