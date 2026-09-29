# 开源许可与致谢

DBDown 采用 GNU GPL-3.0-or-later，完整条款见 LICENSE。

YouTube 解析使用 [NewPipeExtractor v0.26.5](https://github.com/TeamNewPipe/NewPipeExtractor/tree/v0.26.5)，版权属于其贡献者，许可为 GPL-3.0-or-later。签名解析使用 Rhino（MPL-2.0），解析依赖包括 jsoup（MIT）、nanojson（MIT）和 Protocol Buffers（BSD-3-Clause）。这些依赖的源代码及许可由各自上游提供，构建版本固定于 Gradle 依赖。

平台图标仅用于标识视频来源；相关商标属于各平台。项目源码与构建脚本：https://github.com/daxiaamu/DBdown 。


FLAC 无损合并使用 [FFmpegKit maintained](https://github.com/ffmpegkit-maintained/ffmpeg)，固定 Maven 依赖 `dev.ffmpegkit-maintained:ffmpeg-kit-min:8.1.7`（LGPL-3.0），版权属于 FFmpeg、FFmpegKit 及维护分支贡献者。此最小版不添加 GPL 编码器；DBDown 只对本地下载文件进行音视频流复制，不重新编码。依赖以动态原生库随 APK 分发。

上游源代码、构建脚本及发布源材料：https://github.com/ffmpegkit-maintained/ffmpeg/releases 。LGPL 条款见 [LGPL-3.0](docs/licenses/ffmpeg-kit-LGPL-3.0.txt)，其补充的 GPL-3.0 条款见本仓库 LICENSE。DBDown 完整源码和 Gradle 构建脚本允许替换该依赖重新构建。

FFmpegKit 的异常处理依赖 `com.arthenica:smart-exception-java:0.2.1` 使用 BSD-3-Clause，源代码及许可证：https://github.com/tanersener/smart-exception 。
