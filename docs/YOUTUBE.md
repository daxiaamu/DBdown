# YouTube 接入

解析：NewPipeExtractor v0.26.5；网络使用独立 OkHttp 客户端，不发送 B 站或抖音账号 Cookie。任务在 IO 线程同步解析，ThreadLocal 将各请求绑定到对应任务的取消回调；暂停/重试继续复用现有重新解析流程。

输入：watch、Shorts、youtu.be、embed、live 路径及完整 11 位 ID，统一为 watch URL 和 yt:ID 去重键。当前仅下载普通点播，直播明确提示暂不支持。

画质：从渐进式 HTTP 资源选择最高像素数、帧率的视频，支持 AVC / HEVC / VP9 / AV1 和 MP4 / WebM。优先原始语言，再比较码率选择 AAC 或 Opus 独立音轨，使用 FFmpeg 原样封装为 MP4，不转码。登录网页解析采用相同候选范围。分段 DASH/HLS 清单仍不作为下载候选；实际最高画质取决于上游返回的可用资源。分辨率来自所选流，保存后使用文件实际尺寸复核。

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

有 YouTube 会话 Cookie 时先解析已登录 watch 网页中的 ytInitialPlayerResponse，复用 NewPipe 签名和 n 参数处理；媒体地址先验证可用性，再进入现有下载/合并流程。网页资源不可用时尝试公开解析，登录不承诺解除平台挑战。

只有 ytcfg.set 配置中明确的 LOGGED_IN 布尔值才用于有效/失效判断，验证页、异常响应和网络错误不会误判过期。启动、设置页、网页登录完成及资源获取均会检查或更新状态；忽略提醒、重新登录、清除登录复用现有逻辑。

验证：登录状态、域名隔离、已登录网页数据解析单元测试通过；手机已显示 Google 官方“继续使用 YouTube”的登录表单。实际账号登录及账号专属资源需要用户完成认证后验证，未宣称已通过。

2026-09-30：b-Ag7meqZoU 实际解析得到 3840×2160，视频与独立音频均返回 HTTP 206。8T 使用该视频实际 4K 片段完成 MP4 合并及 Android 音视频轨道校验，FLAC 回归测试通过；完整视频在手机上的联网下载未完成验证。
