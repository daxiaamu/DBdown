<p align="center">
  <img src="design/dbdown-icon.svg" width="112" height="112" alt="DBDown 应用图标" />
</p>

<h1 align="center">逗逼下载器 · DBDown</h1>

<p align="center">Kotlin / Jetpack Compose 原生 Android 下载器，支持 B 站、抖音和 YouTube。</p>

<p align="center">
  <a href="https://github.com/daxiaamu/DBdown/releases/latest"><img src="https://img.shields.io/github/v/release/daxiaamu/DBdown?style=flat-square&amp;label=Release&amp;color=315CDE" alt="最新版本" /></a>
  <a href="https://github.com/daxiaamu/DBdown/releases"><img src="https://img.shields.io/github/downloads/daxiaamu/DBdown/total?style=flat-square&amp;label=Downloads&amp;color=315CDE" alt="Release 累计下载量" /></a>
  <img src="https://img.shields.io/badge/Android-13%2B-3DDC84?style=flat-square&amp;logo=android&amp;logoColor=white" alt="支持 Android 13 及以上" />
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-GPL--3.0--or--later-315CDE?style=flat-square" alt="GPL-3.0-or-later 许可证" /></a>
  <a href="https://github.com/daxiaamu/DBdown/actions/workflows/update-metadata.yml"><img src="https://github.com/daxiaamu/DBdown/actions/workflows/update-metadata.yml/badge.svg" alt="更新清单生成状态" /></a>
</p>

> 机场推荐：[白月光，稳定高速](https://www.sibker.com/register?invite_code=2XQR1UUz)

## 交互

- 首页仅一个链接入口，点击后弹框输入或粘贴完整分享文案，点击下载。输入框聚焦时检测有效剪贴板链接，在输入框下方原有粘贴位置提供「填入」，不弹出遮挡内容的提示；点击后才填充，X 一键清空。自动检测遵循剪贴板设置并排除敏感内容。支持抖音「复制打开抖音」整段文本，包括前后口令、表情和话题，也兼容聊天中的 Markdown 链接。
- 底部「首页 / 下载」悬浮岛；支持左右滑动页面、拖动选中胶囊、点击标签切换。页面与胶囊同步跟手，松手吸附，拖动可打断切换动画；右上角设置；跟随系统深浅色。底部标签不显示角标或任务数字；取消标签点击高亮，按住时整个悬浮岛轻微缩小，松开或取消触摸后弹性恢复。
- 下载卡片使用无背景平台图标；右上角未完成时显示状态，完成后显示文件体积，下方显示速度、进度、分辨率及已知帧率。点击视频规格可分别选择画质和音轨，自带音轨的视频保留内置音轨；无音轨视频可另选音轨，默认不添加。更换规格会停止原任务重新下载；已完成任务需二次确认后删除旧文件。已有文件会自动补全尺寸，多图标注首图尺寸。提供图标式「全部暂停 / 全部开始」，暂停期间新增任务也保持暂停；重开应用保留暂停状态。
- 设置允许选择最多同时下载 1–6 个任务，默认 3 个；降低上限不会中止已开始的任务，后续按新上限调度。
- 取消、失败重试、打开已完成内容、查看原作品；清空或删除任务时可选择保留文件或同时删除文件，删除文件需要额外确认。活动任务先停止再清理，多图任务会删除整组图片。
- 冷启动、热启动和重新获得前台焦点时检查剪贴板，也监听应用前台的剪贴板变化。
- 识别 B 站视频 / BV / AV 号、b23 短链接、抖音视频、图文图集及分享短链，以及 YouTube 普通视频、Shorts、短链接和视频 ID；支持精选页 / 首页带 `modal_id` 的作品链接，自动转为作品链接并去除追踪参数。
- 剪贴板候选链接先通过平台解析验证，再提示「下载 / 忽略」；图集按原始素材保存，图片和配乐独立下载。识别到链接时立即显示应用内 Headup 卡片，展示解析进度，完成后显示下载按钮；卡片下移避开系统流体云，无需通知权限。
- 不下载用户主页、直播、文章、番剧；同一次复制不重复提醒，重新复制相同内容仍会提醒；下载任务允许重复添加。
- 设置允许关闭自动检测。不保存普通剪贴板文本，敏感剪贴板不触发自动检测。
- 通过系统的「分享 → 逗逼下载器」接收后台添加请求，仍需用户点下载。

- 图集逐张保存原始图片，配乐独立保存到 Music/逗逼下载器，不合成长视频；Live 图保留动态内容。图片与音乐各自支持打开和分享。
- 新任务添加后自动切换到「全部」并回到列表顶部。

## 平台限制与当前范围

普通权限版本不在后台读取剪贴板；Android 的限制不能通过前台服务解除。
设置对此明确说明。下载在前台服务中继续，并提供通知。

B 站读取当前访客或登录账号可访问的 AVC / HEVC 视频和 AAC 音轨，使用系统 MediaExtractor /
AndroidX Media3 MediaMuxerCompat 合并为 MP4，不转码。抖音从公开分享页提取播放地址；自动处理首次访问的匿名 Cookie 初始化，匿名与登录 Cookie 按网站自身的有效期管理。
设置中可通过 B 站、抖音、YouTube 官方网页完成登录，登录 Cookie 由本机 WebView 保存并用于对应平台请求。
B 站请求高画质，在平台实际返回的 AVC / HEVC 视频流中优先选择更高分辨率；登录不会额外赋予会员、地区或付费权限。
抖音仍基于分享页解析，登录不保证解除所有风控或提升画质。视频下载优先使用官方网页播放入口，连接失败时切换分享页提供的备用入口；重试和暂停后继续都会重新解析任务链接。
接口和网页结构可能变化，解析失败会显示错误，不生成假下载结果。

本版只下载指定分 P（链接中 p 参数，默认 P1），不批量下载合集。
已完成视频保存到系统相册 Movies/逗逼下载器。进程被杀后的活动任务显示「下载中断」，
可手动重新下载。主动暂停保留临时文件，继续时重新解析地址；服务器支持 Range 且文件验证标识有效时续传，否则安全重新下载该音视频流。缓存被系统清理后也会重下。

## 剪贴板提示

识别到链接后立即显示应用内 Headup 卡片，先提示解析中，再显示作品标题和「下载 / 忽略」。解析失败可重试。ColorOS 等系统会预留顶部空间，避开流体云；下载进度与完成提醒仍使用系统通知。

## 网页登录

设置 → 平台账号 → 哔哩哔哩 / 抖音 → 网页登录。
B 站使用官方移动登录页；抖音优先使用手机 UA；若官网把新会话带到无登录功能的 App 介绍页，会自动切换电脑版。可双指缩放，在网站内点击「登录」。
完成短信验证或扫码后，点右上角「完成」返回。
设置页显示平台校验结果；确认失效后显示「登录已失效」，启动及获取下载资源时可提醒重新登录。
登录过期后重新登录即可。两平台互相隔离，不向媒体 CDN 或无关域名复制登录凭据。
「清除全部网页登录」移除本应用内两个平台的 Cookie 和网页存储，不影响官方 App。
应用不读取表单密码、不记录 Cookie 到日志，应用备份已关闭。
已完成的视频不会自动升级画质；需要移除该下载记录后重新添加，原相册文件保留。

参考实现核对：BiliDownload 的 LoginActivity 使用官方 H5 网页与 CookieManager，
BiliInterceptor 使用账号 Cookie 发起接口请求；DouyinDL 的 HttpClient / DouyinParser
采用公开分享页解析，没有账号登录模块。本项目在公开分享解析基础上增加了网页登录。

## 应用身份与 WebView

中文名称：逗逼下载器；英文名称：DBDown；应用包名：com.daxiaamu.dbdown。
0.3.0 改为新包名，系统会将其视为独立应用，旧测试版的下载记录和网页登录不会自动迁移。

登录页使用手机系统提供的 Android WebView，不集成第三方浏览器内核。
Cookie 状态读取与持久化移到后台线程，并取消定时轮询；登录页采用单实例管理，
网页随 Activity 暂停/恢复，在释放时销毁。网页容器高度保持稳定，减少加载过程重排。
登录专用 WebView 拦截明确的视频流 CDN，保留验证码、图片及登录脚本加载。
针对 B 站官方 H5 页协议文字绝对定位覆盖按钮的问题，仅添加范围受限的 CSS 布局修正，
不修改表单数据、事件或平台验证逻辑。

## 构建

- JDK 17 或更新版本；本机验证环境 JDK 24。
- Android SDK Platform 37、Build Tools 36.0.0；compileSdk / targetSdk 为 37。
- 最低 Android 13（API 33），不保留更早系统的兼容分支。
- Gradle Wrapper 9.4.1、Android Gradle Plugin 9.2.1、Kotlin Compose Plugin 2.3.21。
- 在 local.properties 中设置本机 sdk.dir，或设置 ANDROID_HOME。

Windows：

    .\gradlew.bat assembleDebug testDebugUnitTest lintDebug

APK：app/build/outputs/apk/debug/app-debug.apk

## 结构

- Links.kt：纯 Kotlin 的严格域名 / 视频路径识别、规范化及提示去重。
- VideoResolver.kt：各平台独立解析器、短链接展开、访客画质选择。
- MainActivity / MainViewModel：生命周期剪贴板、分享入口、设置及下载交互。
- Ui.kt：Compose 界面与主题。
- ClipboardLivePrompt.kt：系统实时提示、一次性操作、超时回收与前台避让。
- DownloadTask.kt / DownloadStore.kt：任务模型、持久化和状态变更。任务 UUID 是唯一身份，同一作品可以创建多个独立任务。
- YoutubeExtractorStreams.kt / YoutubeStreams.kt：不同来源统一转换为资源模型，复用同一套选流和下载计划生成逻辑。
- YoutubeUrls.kt：视频与清单地址的签名处理。
- DownloadService.kt：持久通知、队列、音视频下载合并、系统相册发布及中断处理。

## 参考

研究参考了以下项目的解析思路、功能和平台行为；当前代码在本项目中重新实现，
没有将两个仓库的代码或二进制依赖整包导入。

- https://github.com/KafuuNeko/BiliDownload （仓库标注 GPL-3.0）
- https://github.com/noctiro/DouyinDL （仓库标注 AGPL-3.0）

如后续直接引入上游代码，需要保留相应许可证及版权说明。

## 应用更新

设置 → 关于卡片显示当前版本，右侧“检查更新”支持主动检查；“开放源代码”可打开 GitHub 项目页。启动后也会自动检查。更新源为 daxiaamu/DBdown（实际配置见 BuildConfig）；完整协议、安全校验和 Release 发布步骤见 [更新接入说明](docs/UPDATE.md)。

## 自动画质选择

抖音分享页的素材尺寸不等于下载流尺寸。合并比较分享页和官方桌面页的多码率列表，优先选择较高分辨率和码率；桌面页超时或不可用时保留分享页资源。只有分享播放入口时优先请求 1080P，并保留原入口降级。实际可获取的画质由平台返回决定，不把请求参数当成已获取的分辨率。下载期间从本地 MP4 视频头读取尺寸，完成时再次读取文件元数据；未确认前不显示原素材尺寸。

参考核对：[DouyinDL 解析器](https://github.com/noctiro/DouyinDL/blob/main/app/src/main/java/com/noctiro/douyindl/data/DouyinParser.kt) 直接使用分享页第一条播放链接；[BiliDownload 视频仓库](https://github.com/KafuuNeko/BiliDownload/blob/master/app/src/main/java/cc/kafuu/bilidownload/common/network/repository/BiliVideoRepository.kt) 请求 DASH 资源列表。DBDown 在此基础上独立实现排序与实际文件尺寸校验。登录和会员权限仍由各平台决定，不设置跨平台通用的“无会员最高 1080P”规则。

## YouTube

支持普通视频、Shorts、youtu.be 分享链接和完整的 11 位视频 ID。自动选择可获取的高分辨率 AVC / HEVC / VP9 / AV1 资源，并原样合并独立音轨，支持 AAC / Opus 等编码及静态 DASH、连续 HLS 点播；需要能够访问 YouTube 的网络。支持在设置中通过 Google 官方网页登录 YouTube，优先使用账号可获取的网页资源；平台验证、会员及地区限制仍以网站为准。当前不支持直播。详细格式和验证记录见 [YouTube 支持说明](docs/YOUTUBE.md)。

## 许可

本项目采用 [GPL-3.0-or-later](LICENSE)，依赖说明见 [开源致谢](THIRD_PARTY_NOTICES.md)。YouTube 解析基于 [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor)。

### 首页轮播消息

通过 [JSON 配置](config/home-messages.json) 控制首页轮播的总开关、切换时长、消息文字和可选链接。配置说明见 [首页轮播消息](docs/HOME_MESSAGES.md)。

## 玻璃效果复刻

底栏半透明胶囊、单层背景模糊、顶部渐变边界和跟手提示卡片的实现说明见 [Compose 玻璃效果复刻指南](docs/COMPOSE_GLASS.md)。
