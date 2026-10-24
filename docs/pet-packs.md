# 导入其他桌宠

桌面控制中心「我的桌宠」新增「导入素材包 / 媒体」和「导入素材目录」。导入后在同一区域显示伙伴列表，可以改名、调整大小、显示、隐藏或移除；移除只归档配置，保留素材方便恢复。原有的「导入配置」仍用于本应用导出的 JSON 设置备份。

## 支持的素材

- 透明 WebM：直接导入，沿用原来的透明视频播放器。
- GIF、PNG/APNG、WebP、JPEG、MOV、MP4：桌面版调用本机 FFmpeg 转为 VP9 Alpha WebM。不透明输入不会自动抠图。
- PNG 序列：用 `pet.json` 指定帧顺序与帧率。
- Shimeji：识别 `shime1.png`、`shime2.png` 等，按数字排序转换成外观序列。原 Java/XML 行为不会运行；需要还原动作时，在 `pet.json` 中明确分组。
- ZIP：可包含以上素材与一个 `pet.json`，支持外层单个目录。也可以直接选择解压后的素材目录。

未提供清单时，按文件名排序，把第一个素材作为待机，其余素材放入随机动作池。Live2D `.model.json` / `.model3.json` / `.moc3`、VRM 和可执行桌宠运行时需要专门的渲染器；当前不能直接运行这些模型，请先导出为透明动画。

### FFmpeg

WebM 原样导入无需额外依赖。其他格式需要安装包含 `libvpx-vp9` 编码器的 FFmpeg，并将 `ffmpeg` 加入 PATH，或通过 `DSH_PET_FFMPEG_PATH` 指定可信的可执行文件路径。桌面 UI 会在缺少转换器时显示具体提示。

单个转换任务有 120 秒超时，转换输出最长 30 秒、24 fps，最长边 640 像素。输入中的音频不会导入。FFmpeg 只接收固定参数数组，不经过 shell，限制为本地文件协议并指定媒体解复用器。

## 可移植动作包 v1

目录示例：

```text
fox/
  pet.json
  idle.gif
  wave/001.png
  wave/002.png
```

```json
{
  "format": "dsh-pet/pack-v1",
  "name": "小狐狸",
  "animations": [
    { "name": "idle", "file": "idle.gif" },
    { "name": "wave", "frames": ["wave/001.png", "wave/002.png"], "fps": 12 }
  ]
}
```

动作名称必须唯一，使用字母、数字、中文、下划线或连字符，最长 60 字符。第一个动作是待机；后续动作进入桌面随机动作池，第二个动作也用于点击回应。Android 点击伙伴时依次切换动作。

一个动作使用 `file` 或 `frames` 之一；序列帧必须是 PNG，最多 300 帧，帧率为 1～30 的整数。最多 64 个动作，名称最长 80 字符，`pet.json` 最大 64 KB。参考 [JSON Schema](pet-pack.schema.json)。Android 可直接读取 GIF/WebP/PNG/JPEG 与 PNG 序列，桌面平台转换后的 WebM 不能直接交给当前 Android 播放器。

## 数据与失败处理

- ZIP/目录最多 512 个条目，单文件最大 32 MB，解压总计最大 128 MB；ZIP 本身体积也不能超过 128 MB。
- ZIP 检查 CRC 和文件头，拒绝加密、ZIP64、分卷、链接、路径穿越、跨系统重名和 Windows 保留名。Android ZIP 解码器同样限制路径、数量和读取体积，输出均为新建普通文件。
- 导入内容只作为数据使用；不会安装或执行包内 JavaScript、Java、XML 行为、可执行文件。
- 桌面端在临时目录完成全部转换后，最后发布配置文件；失败清除暂存素材。每次导入使用新 ID，不覆盖原有伙伴。最多同时管理 12 只导入的伙伴。
- 导入后默认关闭 AI 碎碎念、余额与工作状态联动。
- 桌面端素材位于 `~/.dsh/dsh-pet/pet/import-<UUID>-animation/`，配置为同级 `import-<UUID>-config.json`。移除后配置改名为 `import-<UUID>.removed.json`，手动改回原名即可恢复；可通过「打开数据目录」备份这些文件。
- Android 图片最长边不得超过 2048 像素，显示解码限制到 512 像素，序列按需解码；只保留当前导入伙伴与内置伙伴，不读取桌面端密钥或配置。

素材的版权与使用许可由素材作者规定；请使用有权使用的桌宠资源。
