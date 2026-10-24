# Android 0.2 与跨平台素材导入验证

功能提交：`67fb6e4`。后续 `465b303` 修复 CI 截图采集时机，`4bc513a` 为桌面原生交互测试增加 30 秒页面等待、180 秒总超时及阶段日志；这些提交不改变安装包中的功能。

## 已验证范围

- 桌面完整回归：1,075 项，1,074 通过、1 项跳过、0 失败。类型检查、Lint、Prettier、跨平台打包参数检查通过。
- 两项真实 FFmpeg 测试通过：GIF 透明通道、PNG 序列、精灵图红/蓝帧顺序与帧数、越界网格拒绝和失败回滚。Linux CI 同样运行真实媒体转换。
- 原生 Electron 测试覆盖窗口、AI 设置入口、配置保存、多宠物、视频解码、素材导入/显示/隐藏/移除、主题保存与托盘恢复。测试只使用临时用户目录。
- Android 13 项 JVM 测试覆盖物理、路径、协议和实际本机模拟 HTTP/SSE 传输；不使用服务商真实密钥或付费请求。
- Android 9/API 28 和 Android 15/API 35 各 5 项设备测试通过，0 失败、0 跳过；覆盖实际双悬浮窗、完整动作界面、独立配置和记忆、精灵图 ZIP 往返、旧包保留、路径穿越/模型拒绝、Keystore 加密篡改保护、深色聊天与 AI 默认关闭。
- APK 内含 106 个内置 GIF 及 MIT 许可；版本 `0.2.0-beta.1`、versionCode 2、minSdk 28、targetSdk 36，APK v2 签名验证通过。

## 下载与证据

- [桌面构建及交互截图](https://github.com/w1nterdec/dsh-pet-desktop/actions/runs/40108562238)：Windows x64 安装包、macOS Intel/ARM DMG、Linux AppImage/Debian，以及各平台 `Desktop-QA-*` 截图。
- [Android 构建、APK、报告和截图](https://github.com/w1nterdec/dsh-pet-desktop/actions/runs/40108238030)：`DSH-Pet-Android-preview` 安装包、`Android-unit-and-lint-reports`、两个 `Android-device-API-*` 产物。

本机交付的开发 APK 为 74,387,752 字节（约 70.94 MiB），SHA-256：

```text
ab4f0a8b71174eb778962f06c96441b98d083564cbcacb6edaa73dc8039f9e52
```

该摘要只对应本机交付文件。CI 使用其调试签名重新构建，摘要及签名可能不同。

## 仍需完成的发行验收

真实服务商模型的付费请求、Android 各厂商手机的后台保活和长期耗电、Android 16 真机、字体缩放/旋转及权限撤销矩阵、不同 Linux 桌面和真实多屏仍需设备验证。开发包使用调试签名；正式 Android 签名、macOS 签名公证及商店审核单独管理。

当前不运行 Live2D/VRM 或第三方 Java/XML 行为。Android 不直接播放桌面 WebM/MP4/MOV，不自动同步桌面密钥、设置或记忆；这些边界见 [Android 说明](../android/README.md) 和 [素材包说明](pet-packs.md)。
