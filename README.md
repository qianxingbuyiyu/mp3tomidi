# MP3转MIDI

本地 AI 自动扒谱 Android APP：把 MP3 / FLAC / WAV / M4A 音频直接转成 MIDI 乐谱，全离线运行。

## 功能

- 音频文件转 MIDI（Spotify Basic Pitch 模型）
- 全离线，本地 CPU 推理，不上传任何数据
- 线程数可调（1-8）
- 干净模式：去低音 + 和弦简化，谱面更清晰
- 钢琴卷帘可视化
- 导出 / 分享 MIDI 文件
- Material 3 设计，跟随系统深色模式和壁纸颜色

## 技术栈

- Kotlin + Jetpack Compose
- ONNX Runtime（CPU 推理）
- MediaCodec（原生音频解码）
- Spotify Basic Pitch（开源音转谱模型）

## 构建

GitHub Actions 自动构建：

1. 推送代码到 GitHub 仓库
2. 打 tag（如 `v1.0.0`）自动触发构建
3. 在 Actions 的 Artifacts 或 Release 页面下载 APK

手动触发：仓库 → Actions → Build and Release → Run workflow