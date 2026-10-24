# 跨平台构建与验证

本次扩展增加原生平台构建入口。Windows 延续 Electron；macOS、Linux 使用同一应用与配置；Android 使用独立原生应用。构建支持不代表已通过所有硬件、桌面环境与手机厂商的兼容认证。

2026-10-24 验证记录：[桌面 CI](https://github.com/w1nterdec/dsh-pet-desktop/actions/runs/40084311239) 的 Windows x64、macOS Intel / Apple Silicon、Linux X11 构建与原生交互检查全部通过；[Android CI](https://github.com/w1nterdec/dsh-pet-desktop/actions/runs/40084449744) 的编译、单元测试、Lint 与 APK 产物上传通过。预览包可从对应运行的 Artifacts 下载；这不表示已完成代码签名、公证或商店发布。

## 桌面平台

在 `dsh-pet/` 中执行 `npm run build`，再在对应系统执行：

```sh
# Windows：安装向导 / 绿色目录
node scripts/dist.mjs nsis
node scripts/dist.mjs dir

# macOS：DMG / ZIP
node scripts/dist.mjs dmg
node scripts/dist.mjs zip

# Linux：AppImage / Debian / tar.gz
node scripts/dist.mjs AppImage
node scripts/dist.mjs deb
node scripts/dist.mjs tar.gz
```

默认架构为当前 Node.js 架构；`DSH_PET_TARGET_ARCH=x64` 或 `arm64` 可显式选择。跨架构构建不会误用当前架构的缓存 Electron。`DSH_PET_ELECTRON_DIST` 可以提供对应目标架构的解压运行时；没有缓存时，显式设置 `DSH_PET_ALLOW_ELECTRON_DOWNLOAD=1` 允许下载。

macOS 应在 macOS 上打包 DMG；Linux 应在 Linux 上构建 Debian/AppImage。CI 增加 macOS Intel、Apple Silicon 和 Linux x64 的构建与原生窗口测试；这些工作流需要推送后实际运行，不能把本机配置校验当作对应平台的测试结果。

### macOS

- 控制中心保留 Dock 入口，并提供系统编辑菜单、Command+C/V、Command+Q 与再次激活恢复。
- 独立应用的托盘失败会唤回控制中心；关闭窗口可正常退出。
- 正式对外分发需要 Apple Developer 证书、代码签名与 notarization；仓库不包含任何证书。没有签名和公证的开发包不能宣称通过 Gatekeeper。
- 桌面 Electron 继续使用 Chromium 支持的 VP9 WebM 透明视频，不需要另行生成 Safari 专用 HEVC 素材。

### Linux

- 优先使用 X11；Wayland 会话如果提供 `DISPLAY`，应用默认选择 XWayland。用户显式传入的 `--ozone-platform` 优先。
- 纯 Wayland 限制应用主动定位窗口、全局光标和鼠标穿透；控制中心运行信息会提示能力限制。不同 compositor 的置顶、任务栏和托盘实现仍需实测。
- GNOME 可能需要 AppIndicator 扩展才能展示托盘。没有可见托盘时，可以从桌宠右键菜单打开控制中心；不要在没有恢复入口的环境启用隐藏启动。
- AppImage 可能需要发行版提供 FUSE；也可使用 Debian 安装包或解压目录。
- 自启动遵循 XDG Autostart，使用 `$XDG_CONFIG_HOME/autostart/com.dshpet.desktop.desktop`（默认 `~/.config/autostart/`）。AppImage 使用原始 `APPIMAGE` 路径，而不是临时挂载路径；移动应用后需要重新开启自启动。

## Android

见 [Android 说明](../android/README.md)。0.2 版内置 106 个动作，支持 Android 9/API 28 起的 GIF、动态 WebP、静态图片、PNG 序列、精灵图与动作包；最多保存 12 只伙伴，同时显示 4 只，保留多份导入素材并支持导出。视频和 Live2D/VRM 不在当前 Android 播放器的格式范围内。

原生版本采用用户手动启动的前台服务、常驻通知和停止入口，提供拖拽抛掷、重力反弹、可选碰撞及省电模式；不监听开机广播，不请求无障碍或全盘存储。可选 AI 支持 Chat Completions、Responses 和 Anthropic 文本流，密钥和独立聊天记忆由 Android Keystore 加密；网络权限用于 AI。AI 和自动问候默认关闭，素材、设置和记忆不与桌面版自动同步。

本机 Android 15 专用模拟器已覆盖双悬浮窗、完整动作界面、独立配置与历史、精灵图导入导出和 Keystore 保护。CI 增加 Android 9/15 模拟器任务，结果以具体运行报告为准；模拟器通过仍不能替代厂商手机的后台和耗电验收。

## 验证入口

```sh
cd dsh-pet
npm run typecheck
npm run lint
npm test
npm run test:platform
npm run test:app
# 设置 DSH_PET_FFMPEG_PATH 后可额外运行真实媒体转换测试
npm run test:media
```

`test:app` 使用临时用户目录，覆盖原生窗口、配置保存、多开、视频解码、素材导入与显示/隐藏/移除、收起与托盘恢复。可设置 `DSH_PET_APP_EXECUTABLE` 指向打包后的可执行文件。Linux CI 在 Xvfb 中运行；`DSH_PET_TEST_NO_SANDBOX=1` 仅用于隔离测试环境，正式应用保留 Chromium 沙箱。

发布验收还应覆盖真实 macOS/Linux/Android 设备、不同 DPI/多屏、休眠恢复、Android 权限撤销与厂商后台限制；签名密钥和外部商店发布单独管理。
