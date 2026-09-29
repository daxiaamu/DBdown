# 抖音 slides / Live 图

支持 `/slides/ID`、`/share/slides/ID` 和短链接跳转。使用分享页自身的 slidesinfo 接口，严格匹配作品 ID；逐张关联 `images[i]` 与 `images[i].video.play_addr`，不把封面当作动态内容。标记 Live 但缺少视频地址时任务失败，不静默丢失动态。

slides 始终逐张保存内容，整组配乐单独存入 Music/逗逼下载器，不制作长视频、不混入配乐。Live 图保存为 Pictures/逗逼下载器 下的 `_MP.jpg`，使用 [Android Motion Photo 格式](https://developer.android.com/media/platform/motion-photo-format)：JPEG 封面、XMP 目录和原始 MP4。MP4 字节原样保留；平台提供 WebP 等封面时以原分辨率转换为最高质量 JPEG，此转换并非数学无损。普通静态图片保持原格式。

相册是否显示 Live 标识、能否播放动态取决于相册对 Motion Photo 的支持，不承诺所有厂商都识别。多个输出（含独立配乐）记录到同一任务，支持一并分享和删除。失败或取消时回收本次已发布输出。

测试链接：`https://v.douyin.com/TmOW3WJATnE/`，作品 ID `7688970467424551275`。在线测试设 `DBDOWN_SLIDES_ONLINE=1`，校验 8 张图片、8 个视频及独立配乐均可读取。另一个新版实况作品已在 8T 的 ColorOS 相册验证识别及播放，见下方回归说明。

ColorOS 兼容修复：额外写入 Oplus XMP（owner/version/feature/video length）、EXIF UserComment 和单图 MPF 索引。MPF 图片长度排除尾部视频，视频字节仍原样保留。参考格式字段：[oppo-live-photo-maker](https://github.com/Young-Spark/oppo-live-photo-maker)。此修复不追溯修改已保存文件，需重新下载；后续 ColorOS 实测发现还需移除旧 MicroVideo 标记，详见下文。

新版实况回归：`7690852573347944427` 的移动接口会把两张实况图返回为 `clip_type=2`，缺少 `video`，但视频源尺寸仍存在。遇到此类数据时，通过受限 WebView 读取官方电脑页 `RENDER_DATA.app.videoDetail`，严格匹配作品 ID、图片数量与逐张 URI，补全 `clipType=5 / livePhotoType=1` 的原始片段。网页串行加载、禁止文件访问和自动播放，25 秒超时，取消后销毁；失败不会静默保存为静态图。

ColorOS 17.8.40 实测发现，同时写入旧版 `GCamera:MicroVideo*` 与新版 Motion Photo 标记会导致实况不被识别。逐项对照用户提供的可播放样本后，确认只删除旧 MicroVideo 标记即可恢复原生“实况”及播放按钮，EXIF、MPF 与原始 MP4 不变。该修复仅影响新下载的文件；已保存的静态图缺少视频，必须重新下载。
