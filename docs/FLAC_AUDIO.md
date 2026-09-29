# FLAC 无损音轨

B 站返回可用 FLAC 下载地址时优先选择 FLAC，其次选择最高码率 AAC。仅有 Hi-Res 标识但无实际音轨地址时，不视为可下载。账号权限仍由 B 站决定。

FLAC 路径使用 FFmpegKit min 8.1.7 将视频和 FLAC 直接 stream copy 至 MP4；不转 AAC，不调整采样率、位深、声道，不重编码视频。完成后检查输出同时包含视频轨道及 FLAC 音轨。播放器自身需要支持 MP4 内的 FLAC。

暂停/取消时只取消当前 FFmpeg 会话，并等待原生任务退出，再交给下载任务清理临时文件。普通 AAC 继续使用原有 Media3 合并路径。

`LosslessMuxerTest` 会生成 96 kHz、24-bit 双声道 FLAC，合并后使用 FFprobe 对比每个音频包的 SHA-256，并校验采样率、位深、声道。测试不依赖会员或远程视频链接。执行需要 Android 设备/模拟器；仅编译测试包不代表设备测试通过。

原生合并依赖提供 arm64-v8a 和 x86_64，本应用相应限定为 64 位 Android。原生库需通过 16 KB ELF 对齐及 APK zipalign 检查。
