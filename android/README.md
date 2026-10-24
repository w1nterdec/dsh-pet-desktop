# 桌宠之家 Android 0.2 预览版

Android 9/API 28 起可用，使用原生界面和悬浮窗。内置「蓝毛小女仆」的 **106 个动作**，复用仓库已有 GIF 素材并保留 MIT 许可。首次安装不需要额外下载动作资源。

## 已实现

- 伙伴 / 动作 / 设置三个页面，浅色、深色和跟随系统主题。
- 保存最多 12 只伙伴，同时显示最多 4 只；每只可独立改名、调整大小、人设、位置、固定和自动问候。
- GIF、动态 WebP、PNG/JPEG、PNG 序列、网格精灵图和 ZIP 动作包；新导入保留旧包，可以从已有素材创建伙伴、导出可移植 ZIP。导出不包含密钥或聊天记录。
- 点击回应、长按菜单、动作搜索与播放、拖动和抛掷、重力反弹、可选碰撞、随机动作与移动。固定模式不自动移动，仍可拖动定位。
- 原生 AI 聊天：OpenAI Chat Completions 兼容接口、OpenAI Responses 和 Anthropic Messages，流式文本、取消、连接测试、上下文轮数、输出上限及超时设置。
- 每只伙伴独立的本机聊天记忆；Android Keystore AES-GCM 加密密钥、设置和聊天，私密页面禁止截图及最近任务预览。
- 默认关闭 AI 和自动问候。开启 AI 后可手动聊天；每只伙伴另行开启自动问候后，显示期间至多每十分钟发起一次请求。全应用同时只允许一个 AI 请求，屏幕关闭停止动画、物理和自动请求。

## 使用

1. 安装 APK，打开「伙伴」，允许「显示在其他应用上层」。Android 13 起建议允许通知，方便从通知栏停止。
2. 选择伙伴并点击显示；可添加内置伙伴或通过系统文件选择器导入。素材格式见 [动作包说明](../docs/pet-packs.md)。
3. 在「动作」选择伙伴、搜索动作、点击播放。长按悬浮宠物可进入聊天、动作或伙伴设置。
4. 需要 AI 时，在「设置 → AI 连接设置」填写 HTTPS 基础地址、协议、准确模型 ID 和自己的服务商密钥，保存并显式启用。测试连接和问候均会调用模型，费用由服务商计收。
5. 在伙伴配置中取消固定后，可使用重力或移动；「设置」提供碰撞、省电与召回入口。通知栏「停止全部」关闭所有悬浮窗。

AI 仅向配置的服务商端点发送该伙伴的人设和所选上下文，不自动同步桌面版配置、密钥或记忆。更换服务商源地址不会沿用原密钥；不跟随 HTTP 重定向，不自动重试付费请求。

## 权限和当前边界

前台服务只能由可见页面中的用户操作启动；不自动开机、不请求无障碍或全盘存储。网络权限用于可选 AI。权限撤销、进程被系统结束或停止后不自动重启。手机厂商可能另行限制后台运行；锁屏、系统安全页面及禁止悬浮窗的应用可能不显示宠物。

Android 当前不直接播放 WebM/MP4/MOV，也不运行 Live2D、VRM 或 Shimeji 的 Java/XML 行为；请导出成受支持图片动画。保留原导入包便于再利用，删除伙伴不删除其素材；素材占用可在 Android 应用存储中查看。卸载会删除本机数据，加密内容不参与系统备份。

## 构建

需要 JDK 17、Android SDK Platform 36，Gradle Wrapper 固定为 8.13，Android Gradle Plugin 固定为 8.13.0。

```sh
cd android
# 先在环境变量 ANDROID_HOME 指定 SDK，或用本机 local.properties 的 sdk.dir
bash ./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
# Windows PowerShell 使用 .\gradlew.bat
# 专用模拟器设备测试（会修改模拟器中的应用数据）
bash ./gradlew --no-daemon connectedDebugAndroidTest
# 从桌面仓库素材重新生成动作清单（Node.js；Gradle 自动复制动画资源）
node scripts/sync-assets.mjs
```

APK 输出到 `app/build/outputs/apk/debug/app-debug.apk`。GitHub [Android 工作流](https://github.com/w1nterdec/dsh-pet-desktop/actions/workflows/android-build.yml) 上传 APK、单元/Lint 报告和 Android 9/15 设备测试结果。设备用例只在模拟器运行，覆盖多悬浮窗、完整动作页、配置隔离、素材往返及 Keystore 篡改保护；AI 网络测试使用本机 MockWebServer，不消耗真实密钥或额度。

调试签名仅用于开发验证；正式发布需要独立维护的发行 keystore、签名配置及商店对 `specialUse` 前台服务与悬浮窗用途的审核。本仓库不存储签名密钥。

Android Lint、单元测试和 APK 编译不能替代真机验证。发行前需覆盖 Android 9/13/14/15/16、旋转/字体缩放、通知拒绝、悬浮权限撤销、屏幕休眠恢复、后台限制及长时间运行耗电。

协议参考：[Chat Completions](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create)、[Responses](https://developers.openai.com/api/reference/resources/responses/methods/create)、[Anthropic Messages](https://docs.anthropic.com/en/api/messages)。仅实现文本对话，不支持工具调用、语音或图片输入。
