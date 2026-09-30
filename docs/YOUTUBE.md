# YouTube 接入

解析：NewPipeExtractor v0.26.5；网络使用独立 OkHttp 客户端，不发送 B 站或抖音账号 Cookie。任务在 IO 线程同步解析，ThreadLocal 将各请求绑定到对应任务的取消回调；暂停/重试继续复用现有重新解析流程。

输入：watch、Shorts、youtu.be、embed、live 路径及完整 11 位 ID，统一为 watch URL 和 yt:ID 去重键。当前仅下载普通点播，直播明确提示暂不支持。

画质：汇总网页登录页面、NewPipe 原始 player 响应和补充官方客户端的候选，合并渐进式 HTTP、DASH、HLS 点播资源后再选择。最高像素数、帧率优先，同档位优先 HDR，再比较码率；不设置分辨率上限。独立音轨优先原始语言，再比较默认语言、声道数和码率，支持 AAC、Opus、AC-3、E-AC-3。无语言标记的单音轨不会仅因缺少默认标记而输给低码率音轨。先检查地址可用性，失败后继续比较其他候选。使用 FFmpeg 原样封装为 MP4，不转码；保存后复核实际尺寸。

R8：保留 Rhino 动态执行与 Protobuf 反射字段；排除 Android 没有的 JSR-223 引擎。NewPipe 使用 Rhino 解释模式，不执行桌面 JIT。配置参考 https://github.com/TeamNewPipe/NewPipe/blob/dev/app/proguard-rules.pro 。

## 验证

2026-09-28：
- qIzGvexMjpA：1920×1080，视频/音频 HTTP 206。
- -9OM3w3TWUs：1080×1920，视频/音频 HTTP 206。
- BLKegH19KGI：1920×1080，视频/音频 HTTP 206。
- 手机 instrumentation 完整下载 Shorts，合并后包含音频和视频轨道，尺寸为 1080×1920。
- R8 正式构建通过系统分享入口再次下载 Shorts，完成大小 23.0 MB，尺寸 1080×1920。

离线测试覆盖 URL 去重、非法输入、画质选择、缺少音轨和非渐进式资源。在线测试以 DBDOWN_YOUTUBE_ONLINE=1 显式启用；网络和上游内容变化可能影响结果。支持 YouTube 网页登录。Google 凭据只在官方网页填写，应用仅使用 WebView 保存的 YouTube 域 Cookie。Google 可能在后续登录步骤拒绝内嵌浏览器；打开邮箱输入页不代表已完成登录。

## 登录态

设置中跳转 YouTube signin，再由官网转到 accounts.google.com。导航限定 HTTPS 官方域名；Google 账号域的 Cookie 不交给解析器，YouTube Cookie 也不发送到 googlevideo.com、googleapis.com 或其他平台。

解析 watch 网页中的 ytInitialPlayerResponse，有会话时同时更新登录状态；页面格式参与全体候选比较，不因页面返回 1080P 而提前停止。复用 NewPipe 的签名和 n 参数处理，额外支持清单 URL 路径中的 n 参数。补充客户端 API 和媒体请求不携带网页登录 Cookie。登录不承诺解除平台挑战。

只有 ytcfg.set 配置中明确的 LOGGED_IN 布尔值才用于有效/失效判断，验证页、异常响应和网络错误不会误判过期。启动、设置页、网页登录完成及资源获取均会检查或更新状态；忽略提醒、重新登录、清除登录复用现有逻辑。

验证：登录状态、域名隔离、已登录网页数据解析单元测试通过；手机已显示 Google 官方“继续使用 YouTube”的登录表单。实际账号登录及账号专属资源需要用户完成认证后验证，未宣称已通过。

2026-09-30：b-Ag7meqZoU 实际解析得到 3840×2160，视频与独立音频均返回 HTTP 206。8T 使用该视频实际 4K 片段完成 MP4 合并及 Android 音视频轨道校验，FLAC 回归测试通过；完整视频在手机上的联网下载未完成验证。

## HDR 与更高分辨率

解析期间保留同一任务内 YouTube 官方 player 响应，按目标视频 ID 与可播放状态校验后，直接读取格式字段，避免 NewPipe 固定 itag 白名单漏掉 HDR 或未来格式。最高像素数、帧率优先，同档位优先 HDR，再比较码率；不设置 4K / 8K / 12K 上限。此策略不保证平台一定提供某个分辨率。

音频正确识别 NewPipe 的 WEBMA_OPUS 枚举。原始播放器数据可识别 AAC、Opus、AC-3、E-AC-3，保存编码标签供合并校验；不把普通环绕声或标题中的 Dolby 字样当成 Atmos。HDR 与 Dolby Vision 也不等同。

2026-09-30，样例 t2dhvFTktk4：官方原始响应本次返回最高 3840×2160 / 60fps，包含 VP9 Profile 2 HDR 与 SDR 两种流，音轨为 AAC / Opus 双声道，未返回 12K、Dolby Vision 或杜比音轨。修复后选中 2160p60 HDR 与 Opus；8T 实际片段合并测试确认输出保留 yuv420p10le、BT.2020、SMPTE ST 2084（PQ）及 Opus 双声道。验证针对实际片段，不代表完整长视频联网下载、12K 解码播放或 Atmos 元数据已验证。

## 多客户端与分段下载（2026-09-30）

- 补充 VISIONOS、IOS 和 WEB 客户端上下文；按视频 ID 和可播放状态过滤结果，实际能否得到资源由 YouTube 决定。配置来源为 yt-dlp 的官方源码： https://github.com/yt-dlp/yt-dlp/blob/master/yt_dlp/extractor/youtube/_base.py 。
- 使用 Media3 解析静态、单 Period DASH（SegmentBase、SegmentList、SegmentTemplate/Timeline），以及带 ENDLIST 的连续 HLS 点播。支持初始化分片、字节范围、独立音轨、AES-128 明文密钥；不支持 DRM、直播、时间戳不连续的 HLS、初始化段切换或多 Period DASH。这些候选会跳过并尝试其他资源。
- 媒体分片和密钥只从 HTTPS googlevideo.com 域获取，并验证重定向；清单先在 Java 层解析，FFmpeg 只处理本地文件。
- 已完整写入的分片使用独立原子缓存；暂停后复用已完成分片，正在传输的分片重新下载。清单或签名地址变化时使用新缓存，避免把不同资源拼到一起。所有片段完整后才合并和发布。
- 此实现不是 SABR 或 PO Token 生成器。客户端可能返回 LOGIN_REQUIRED、UNPLAYABLE，或只返回无法下载的格式；不会把这些格式当成下载成功，也不保证“获取所有格式”。

本次 8T 实测：

- b-Ag7meqZoU：选择 3840×2160 HLS 视频（75 个分片，含初始化段）和独立 Opus 音轨；实际前几个分片合并后保持 4K、BT.709 和 Opus。
- t2dhvFTktk4：选择 3840×2160 / 60fps HDR HLS 视频（862 个分片，含初始化段）和独立 Opus；真实片段合并后保留 yuv420p10le、SMPTE ST 2084、BT.2020，未转码。
- 上述验证是实际片段下载与合并，不是两个长视频的全量下载。合成短片另行覆盖从完整清单下载所有分片、完整时长和音视频轨道校验。
- VISIONOS 在本次测试返回 LOGIN_REQUIRED，WEB 返回 UNPLAYABLE；IOS 状态 OK，但本次没有新增可用候选。实际选中资源来自 NewPipe 请求得到的原始数据和清单。本次没有得到更高分辨率或 Dolby Atmos，不能根据标题推断支持。

## 网页 8K 对照（2026-09-30）

QHBruxEyow0 的桌面 Chrome 画质菜单确实列出 4320p60（8K），播放器 adaptiveFormats 中存在 itag 571：7680×4320、60fps、AV1、BT.709。该响应只提供 serverAbrStreamingUrl，格式没有 url 或 signatureCipher；因此当前只支持 HTTP / DASH / HLS 的下载链路无法使用这条 SABR 8K 资源。这是当前 APP 的协议支持缺口，不能把已支持客户端返回的 4K 当作视频本身的最高分辨率。

NPBiLXkhzFo 的此次桌面播放器响应最高为 3840×2160 / 60fps，包含 VP9 Profile 2 和 AV1 HDR；视频时长元数据为 610 秒。8T 选择 4K60 HDR HLS 与 Opus，完整下载验证在音频传输阶段发生网络读取超时，不能记为全片合并成功。

QHBruxEyow0 的同一应用解析器复核实际选择 3840×2160 / 60fps（本次 SDR）和 Opus，视频、音频均返回 HTTP 206。网页的 8K60 SABR 资源尚未实现下载；本轮不宣称 8K 或该视频真机全片下载通过。

## 屏幕与设备能力对照（2026-09-30）

研究资料：

- [YouTube 官方画质说明](https://support.google.com/youtube/answer/91449?hl=en)：屏幕/播放器大小、浏览器与编解码支持会影响播放画质。
- [Chromium Media Capabilities 发布讨论](https://groups.google.com/a/chromium.org/g/blink-dev/c/aXYvQ01tMhw/m/SqA09gD7AgAJ)：YouTube 曾用解码能力预测限制自动码率选择的分辨率上限。这是自动播放决策的证据，不等于服务器不返回更高清资源。
- [yt-dlp 客户端配置](https://github.com/yt-dlp/yt-dlp/blob/master/yt_dlp/extractor/youtube/_base.py)：iOS 设备型号会影响 60fps 格式；项目已有相同 iPhone16,2 和 RealityDevice17,1 请求信息。

对 QHBruxEyow0 使用独立 Chrome 153 测试会话：

| 屏幕/视口 | AV1 能力 | 播放器可选最高画质 | 原始响应 |
| --- | --- | --- | --- |
| 1280×720 | 原生支持 | 8K60（highres） | itag 571，7680×4320 / 60fps |
| 3840×2160 | 原生支持 | 8K60（highres） | 同上 |
| 3840×2160 | 测试脚本让 MediaSource、canPlayType 和 MediaCapabilities 报告不支持 AV1 | 4K60 | 仍包含相同 8K60 条目 |

三组响应均为 SABR，8K 条目均无 url 或 signatureCipher。这个样例的可见 8K 门槛来自客户端编解码能力，不是必须具备 8K 屏幕；不推广为所有视频或设备的结论。另行直接修改 screenWidthPoints/screenHeightPoints 的 API 请求均得到 UNPLAYABLE，不能据此证明这些字段能或不能提高可下载档位。

当前下载器直接比较原始格式，不调用手机屏幕尺寸或本机解码能力来限制下载清晰度；保存与无损合并也不要求手机能实时播放该分辨率。因此没有加入未经验证的虚拟屏幕、GPU 型号或性能数值。新增实际 itag 571 AV1 格式的回归：存在可用普通地址时优先选择 8K；只有 SABR 格式描述时不虚报已经取得 8K 下载地址。测试使用合成地址，不代表本次已下载真实 8K。
