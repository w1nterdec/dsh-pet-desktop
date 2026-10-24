# 桌宠之家 Android 预览版

Android 9/API 28 起可用。原生悬浮窗、透明 GIF/动态 WebP、PNG/JPEG、PNG 序列、ZIP 动作包、拖动定位、点击切换动作、长按打开控制面板、通知栏停止、屏幕关闭暂停动画。

首次启动自带「鲸鲸」待机动画，素材复用上游 dsh-pet，沿用仓库 MIT 许可。可以导入一只自定义伙伴，或切回内置伙伴；本版本不会自动同步桌面素材、AI 设置或对话记忆。

## 使用

1. 安装开发 APK，打开应用。
2. 点击「允许显示在其他应用上层」，在系统设置中授权后返回。
3. 使用内置伙伴或导入 GIF/WebP/图片/ZIP。PNG 序列和动作清单格式见 [素材包说明](../docs/pet-packs.md)。
4. 点击「显示桌宠」。Android 13 起建议允许通知，便于从通知栏停止。
5. 拖动调整位置，点击切换动作，长按打开面板。调整大小后立即更新正在显示的伙伴。

需要用户在可见 Activity 中手动开启前台服务；不自动开机启动，不使用无障碍服务，不申请网络或全盘存储权限。权限撤销、系统杀进程或用户停止后不会自动重启。不同手机厂商可能要求在系统设置中允许后台运行。悬浮窗不能显示在系统安全界面、锁屏或主动禁止悬浮窗的应用上层。

## 构建

需要 JDK 17、Android SDK Platform 36，Gradle Wrapper 固定为 8.13，Android Gradle Plugin 固定为 8.13.0。

```sh
cd android
# 先在环境变量 ANDROID_HOME 指定 SDK，或用本机 local.properties 的 sdk.dir
bash ./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
# Windows PowerShell 使用 .\gradlew.bat
```

APK 输出到 `app/build/outputs/apk/debug/app-debug.apk`。CI 的 Android 工作流上传调试 APK。调试签名仅用于开发验证；正式发布需要独立维护的发行 keystore、签名配置及商店对 `specialUse` 前台服务与悬浮窗用途的审核。本仓库不存储签名密钥。

Android Lint、单元测试和 APK 编译不能替代真机验证。发行前需覆盖 Android 9/13/14/15/16、旋转/字体缩放、通知拒绝、悬浮权限撤销、屏幕休眠恢复、后台限制及长时间运行耗电。
