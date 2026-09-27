# Android 应用内更新通用需求文档

## 1. 目标与边界

提供可复用的 Android 应用更新能力，覆盖自动/手动检查、强制更新、多 CDN、更新弹层、下载进度、SHA-256 校验、安装授权和 GitHub Actions 自动维护 JSON。

不纳入通用能力：iKanPro 等具体产品分支、Root 静默安装、与 APP 更新无关的远程业务配置、某项目私有包名/证书/域名。

## 2. 核心流程与状态

主状态：`Idle -> Checking -> UpToDate | OptionalAvailable | RequiredAvailable | Failed`。

下载状态：`NotStarted -> Queued -> Downloading -> Verifying -> ReadyToInstall | Failed`。

安装状态：`ReadyToInstall -> NeedsAuthorization | LaunchingInstaller`。

- 同一时刻只运行一个实际检查。自动与手动调用加入同一个会话和回调集合。
- 手动调用加入自动会话后，会话完成时必须显示“发现更新/已是最新版/检查失败”。
- 已完成的自动检查结果可在当前进程复用；手动点击默认重新请求，不能直接复用旧结果。
- 同一版本只运行一个下载计划；URL 失败或校验失败后切换下一 CDN。

## 3. 检查更新

### FR-01 自动与手动触发

- APP 主界面可交互后延迟 1.5–3 秒自动检查，每个进程一次。
- 设置页提供“检查更新”按钮；检查中显示加载态并防重复点击。
- 自动检查不打扰最新版用户；手动检查总有明确反馈。

### FR-02 共用会话

- 使用单例管理器维护 `checking`、回调集合、当前会话结果和已知更新。
- 自动检查进行时点手动检查：不重复联网，将 `manualResultRequested` 置为 true。
- 手动检查进行时触发自动检查：加入现有任务，不产生第二个弹层。
- 当前会话同一版本只自动弹一次。

### FR-03 多 CDN 检查

- 元数据配置多个 HTTPS 地址，支持 GitHub API、Raw/CDN 和自有 CDN。
- 所有请求添加时间戳 cache-buster、连接/读取超时、`Cache-Control: no-cache` 和明确 User-Agent。
- 每个响应都校验 schema、渠道、versionCode、URL 和 SHA-256 格式。
- 可采用两种可配置策略：权威源优先、失败后镜像回退；或并发/顺序读取全部有效结果后选择最高 versionCode。
- 强制策略存在时，必须有唯一权威策略或 `policyRevision` 防回滚；不能让旧 CDN 先返回而绕过强制更新。

### FR-04 stable/beta

- stable 客户端只接受 stable JSON；beta 客户端可检查 beta 和 stable，并选择合法的更高 versionCode。
- JSON 显式包含 `channel` 或 `prerelease`。渠道缺失/不匹配视为无效。
- 新旧比较只使用整数 versionCode，versionName 仅展示。

## 4. 普通与强制更新

### FR-05 普通更新

- 普通更新允许三个操作：`跳过此版本`、`忽略`、`下载安装`。
- 跳过此版本：持久化 versionCode，之后自动检查不再弹该版本；手动检查仍展示。
- 忽略：仅关闭本次弹层，不写跳过版本；设置页“检查更新”入口显示红点，表示当前会话已知仍有更新。
- 安装成功或检查确认已无更新后清除红点；进入手动检查并展示该更新时可按产品规则保留到安装/最新版。

### FR-06 强制更新

- JSON 使用持久字段 `maxForcedVersionCode`，语义为：`currentVersionCode <= maxForcedVersionCode && latestVersionCode > currentVersionCode`。
- 强制更新优先于跳过/忽略，不显示“跳过此版本”和“忽略”，弹层不可返回、外部点击关闭。
- 发布 Actions 绝不能自动重置、覆盖或推导 maxForcedVersionCode。它必须从受版本控制的策略文件或人工输入合并，并记录变更原因。
- `0 <= maxForcedVersionCode < latestVersionCode`，否则拒绝元数据。

## 5. 更新弹层

### FR-07 内容与滚动

- 展示版本名、发布时间和更新日志。
- 信息区域设置最大高度（建议屏幕高度 50%–65%），版本、时间和正文作为一个整体纵向滚动。
- 底部三个按钮固定，不随正文滚动；小屏、横屏、超长日志均可看到操作按钮。
- `publishedAt` 只能作为传输字段，不得把 `2026-08-12T08:00:00Z` 等原始字符串直接展示给用户。
- 客户端将 publishedAt 解析为绝对时间点，再按设备当前时区和 Locale 格式化。例如中国标准时间显示为 `发布时间：2026-08-12 16:00`；其他地区应显示对应当地时间和本地日期顺序。
- 展示格式默认不包含 ISO 8601 的 `T`、`Z`、毫秒和秒；如产品需要显示时区，应使用用户可理解的本地化名称或 UTC 偏移，不直接暴露传输符号。
- publishedAt 缺失时隐藏发布时间行；格式非法或解析失败时不得崩溃，也不得回退显示原始字符串，可隐藏并记录非敏感诊断信息。

### FR-08 简单 Markdown

- 支持标题、无序/有序列表、粗体、斜体、行内代码、代码块、引用、分割线和 HTTP(S) 链接。
- 不执行 HTML、JavaScript、自定义 URI 或远程图片；解析失败降级为纯文本。
- 链接交给系统浏览器；更新内容必须可选择/可访问或具备等效语义。

### FR-09 三按钮布局

- 普通更新固定提供：跳过此版本、忽略、下载安装。
- 建议次要按钮在左侧/辅助区，主按钮在右侧；窄屏允许合理换行但不能遮挡。
- 点击下载安装后，主按钮位置保持不变并切换为下载状态，避免弹层尺寸和正文换行跳动。

## 6. 下载与进度样式

### FR-10 多 CDN 下载

- JSON 下发去重后的有序 `urls`。首个地址失败、HTTP 非 2xx、重定向到 HTTP、文件写入失败或 SHA-256 不符时，清理临时文件并尝试下一地址。
- 所有 CDN 必须提供同一 APK；不能在客户端拼接未经服务端声明的任意下载内容。
- 可用 Android DownloadManager，或应用内下载到 `.download` 临时文件；项目必须选择一套主状态机。

### FR-11 进度视觉

- 检查更新：手动入口尾部使用固定 `64 x 48dp` 槽位；按钮与进度环共用该槽位，切换时卡片宽高不变。
- 弹层下载：下载安装主按钮使用固定最小宽高。已知 Content-Length 时显示 20dp 左右的环形确定进度及百分比；未知长度显示同尺寸不确定进度环。
- 进度环建议 2dp stroke；环与“下载中/xx%”文字间距 8dp。按钮禁用重复点击，但跳过/忽略在下载开始后应按产品策略禁用，避免丢失下载状态。
- 进度更新节流到约 250–500ms，完成前最高显示 99%，SHA-256 验证通过后显示 100% 并切换安装。
- 失败后主按钮恢复“重试下载”；错误文本显示在滚动内容区底部，不能仅靠颜色表达。

### FR-12 文件与恢复

- 下载写临时文件，流关闭并同步落盘，SHA-256 通过后原子重命名为已验证 APK。
- 可持久化活动下载 ID、URL 列表和当前源索引，以便进程恢复和 CDN 重试；不得只凭 `verifiedApk != null`、下载成功标志或历史“已校验”状态直接安装。
- 每次点击“安装”，以及从“安装未知应用”授权页返回准备继续安装时，都必须重新计算本地 APK 的 SHA-256，并与**当前弹层所使用的当前 JSON** 中 `sha256` 比较。
- SHA-256 不一致说明本地是旧版本、损坏文件或非当前制品：立即删除该 APK，并按当前 JSON 的 URL 列表重新下载。
- SHA-256 一致才进入安装身份检查；继续验证 APK packageName、APK versionCode 和签名证书，全部一致后才启动系统安装器。
- `versionCode + sha256` 绑定可作为隔离异步回调和优化 UI 状态的实现手段，但不是决定能否安装的必要复杂状态机；安装前以当前 JSON 重新计算 SHA-256 是最终准则。

## 7. SHA-256 与安装安全
### FR-13 必须校验

- JSON 的 SHA-256 为必填 64 位小写十六进制；缺失/非法时整个更新响应无效。
- 下载过程中流式计算 SHA-256，使用安全比较；不匹配必须删除/隔离文件并切换 CDN。
- 自更新 APP 安装前还应验证 APK 包名等于当前包、APK versionCode 等于 JSON、签名证书与已安装应用一致。SHA-256 不能替代这三项身份校验。

### FR-14 系统安装

- 使用 FileProvider 或 DownloadManager content URI，设置 APK MIME 和临时读取权限。
- Android 8+ 无“安装未知应用”权限时进入当前包授权页；返回后复用已验证 APK，不重复下载。
- 不实现 Root 静默安装。

## 8. 更新 JSON

```json
{
  "schemaVersion": 1,
  "channel": "stable",
  "versionCode": 125,
  "versionName": "1.2.5",
  "publishedAt": "2026-08-12T08:00:00Z",
  "changelog": "## 更新内容\n\n- 修复问题",
  "maxForcedVersionCode": 0,
  "policyRevision": 16,
  "url": "https://example.com/app-1.2.5.apk",
  "urls": [
    "https://cdn-a.example.com/app-1.2.5.apk",
    "https://cdn-b.example.com/app-1.2.5.apk"
  ],
  "sha256": "64-lowercase-hex-characters",
  "size": 12345678
}
```

- 必填：schemaVersion、channel、versionCode、versionName、url/urls、sha256、maxForcedVersionCode。
- 推荐：publishedAt、changelog、size、policyRevision、releaseUrl。
- `publishedAt` 使用 ISO 8601/RFC 3339 且必须携带时区；推荐由 Actions 统一输出 UTC，例如 `2026-08-12T08:00:00Z`。也可接受带偏移的 `2026-08-12T16:00:00+08:00`，但不得接受无时区的本地时间。
- Android 解析层将 publishedAt 归一化为 `Instant`/等价绝对时间类型；展示层再转换为系统 `ZoneId` 并使用当前 Locale 的日期时间格式。低版本 Android 使用 core library desugaring 或等价兼容实现。
- 元数据响应不超过 1 MiB；所有 URL 为 HTTPS；列表非空、去重且有数量上限。
- 客户端解析层可兼容 `notes/changelog`、`apkUrl/url`、`apkUrls/urls`、`apkSha256/sha256`，业务层只使用一个归一化模型。

## 9. GitHub Actions 自动更新 JSON

### FR-15 触发和来源

- 在 GitHub Release `published` 后运行；根据 Release 的 prerelease 属性选择 stable 或 beta JSON。
- 下载 Release APK，要求目标资产唯一，不能随意取第一个 APK。
- 用 aapt/aapt2 从 APK 本体读取 packageName、versionCode、versionName，不只读取 Gradle 文本。
- 校验 Release tag 与 APK versionName、渠道一致。

### FR-16 生成与发布

1. 计算 APK SHA-256 和 size。
2. 从 Release body/publishedAt 取得 changelog 和发布时间。
3. 生成官方地址和配置的多个 CDN 地址。
4. 从独立策略文件合并 maxForcedVersionCode、policyRevision；Actions 不得自行归零。
5. 用 jq/schema 校验数字类型、渠道、HTTPS、URL 数量、SHA-256、强制边界。
6. 可选实际请求各 URL，确认返回 2xx、Content-Length 合理且下载哈希一致。
7. 仅在 APK 已可下载且 JSON 完全通过后提交 `update.json` 或 `update-beta.json`。
8. `git pull --rebase` 后推送，避免并发覆盖；清理可变 CDN 缓存。

### FR-17 发布安全

- workflow 使用最小 `contents: write` 权限，凭据只来自 GitHub secrets/token。
- 使用 concurrency group，禁止同一元数据目标并发写入。
- APK URL 必须版本化且内容不可变；禁止用同 URL 覆盖不同 APK。
- JSON 生成文件不手工编辑；强制策略文件可以人工审核编辑，并保留审计历史。

## 10. 验收矩阵

| 场景 | 预期 |
| --- | --- |
| 自动检查中点击手动检查 | 只发起一个会话，结束后给手动可见结果 |
| 手动检查已有旧会话缓存 | 重新请求 CDN，不瞬间误报最新版 |
| 已是最新版 | 自动静默；手动明确提示 |
| 普通更新点“跳过此版本” | 自动不再弹；手动仍显示 |
| 普通更新点“忽略” | 关闭弹层；手动检查入口出现红点 |
| 命中强制边界 | 不能跳过/忽略/关闭 |
| 更新日志非常长 | 内容可完整滚动，三个按钮固定可见 |
| publishedAt 为 `2026-08-12T08:00:00Z`，设备时区为 Asia/Shanghai | 显示本地时间 `2026-08-12 16:00`，不显示原始 T/Z |
| 切换设备时区或语言 | 同一绝对时间按新时区和 Locale 重新格式化 |
| publishedAt 缺失或非法 | 隐藏发布时间，不崩溃、不显示原始字符串 |
| Markdown 日志 | 支持限定语法，危险内容不执行 |
| 检查按钮变进度环 | 固定槽位，卡片和后续布局不跳动 |
| 下载长度已知/未知 | 分别显示确定/不确定环形进度，尺寸一致 |
| 第一元数据 CDN 失败 | 按策略使用下一合法 CDN |
| 第一 APK CDN 或 SHA 失败 | 删除临时文件并切换下一源 |
| SHA-256 缺失或错误 | 拒绝响应/安装 |
| 包名、versionCode、签名错误 | 拒绝安装 |
| 未知来源权限缺失 | 授权返回后重新用当前 JSON 的 SHA-256 校验本地 APK，再决定安装或重下 |
| 弹层从 1.1.3 切换为 1.1.4，但本地仍是 1.1.3 APK | 安装前 SHA-256 与当前 1.1.4 JSON 不符，删除旧 APK 并重下 |
| `verifiedApk` 有值但文件被替换、损坏或不是当前版本 | 不信任历史状态；重新计算 SHA-256，失败则拒绝安装并重下 |
| Release 有多个 APK | Actions 失败，不更新 JSON |
| Actions 生成 stable/beta | 目标与 prerelease 标记一致 |
| Actions 发布新版本 | 保留人工强制更新策略，不自动归零 |

## 11. 多 CDN 数量与缓存一致性策略

### 11.1 硬性数量要求

- 元数据检查配置至少 5 个 HTTPS CDN/来源地址，并明确其中唯一权威源。
- APK JSON 下发至少 5 个去重的 HTTPS CDN 下载地址，且至少来自 5 个不同主机。
- 官方 GitHub Release 原始地址可以作为额外最终兜底，但不替代 5 个 CDN 地址。
- 不允许通过不同查询参数、大小写或重定向别名重复同一主机来凑数。

### 11.2 为什么不能取第一个或多数结果

CDN 缓存刷新时间不同，“第一个成功”可能是旧版本；多数 CDN 也可能同时缓存旧内容。因此不得以响应速度或简单多数投票决定强制更新策略。

### 11.3 元数据选择算法

1. 并发请求至少 5 个来源，添加 cache-buster，并对每份响应做 HTTPS、schema、channel、versionCode、SHA-256、强制边界和大小限制校验。
2. 权威源返回合法内容时，以权威响应为准；继续收集短暂收敛窗口（建议 300–1000ms）内的镜像结果用于发现冲突，然后取消剩余请求。
3. 每份元数据包含单调递增的 `policyRevision`、发布时间/有效期，以及应用层签名；若暂不做签名，则由权威 `latest` 指针提供不可变清单 URL 和清单 SHA-256。
4. 客户端持久化已接受的最高 `policyRevision`。任何更低 revision 都视为旧缓存并拒绝，绝不降低强制更新边界。
5. 同一 revision 的规范化 JSON 必须具有相同内容摘要。同一 revision 出现不同 versionCode、maxForcedVersionCode、APK SHA-256 或内容摘要时，视为发布冲突并拒绝该 revision 的所有镜像结果。
6. 权威源不可用时：有应用层签名则选择签名有效且 revision 最高的响应；无应用层签名时，至少要求两个独立主机返回完全一致且 revision 不低于本机最高值的内容。
7. 无法形成可信结果时，普通更新暂不提示并稍后重试；涉及强制更新时 fail-closed，不使用可能过期的 CDN 内容绕过策略。
8. 上报冲突主机、revision、内容摘要和时间，将异常 CDN 临时降权或隔离；不记录设备唯一标识。

### 11.4 推荐发布结构

- 使用可变 `latest.json` 指针，字段只包含 `policyRevision`、不可变 manifest URL、manifest SHA-256、有效期和签名。
- 实际 manifest 使用 `/manifests/{policyRevision}-{sha256}.json` 等不可变路径，并设置长期/永久缓存。
- 发布顺序：上传 APK -> 验证至少 5 个 CDN 的 APK SHA-256 -> 上传不可变 manifest -> 验证 manifest -> 原子切换权威 latest -> 清理各 CDN 的 latest 缓存。
- 同一 policyRevision 不得覆盖内容。任何内容或策略变更都必须递增 revision。

### 11.5 APK CDN 不一致

- JSON 的 APK SHA-256 是唯一字节身份。每个 CDN 下载完成后都必须计算 SHA-256。
- 某 CDN 返回旧 APK、错误页面或被替换文件时，删除临时文件、记录该源失败并切换下一源。
- SHA-256 通过后仍校验包名、versionCode 和签名证书，才进入可安装状态。
- Actions 必须验证下载 URL 数量 `>= 5`、URL 去重、主机去重且主机数 `>= 5`；发布前可抽样或完整下载验证所有 CDN 哈希。

### 11.6 验收场景

| 场景 | 预期 |
| --- | --- |
| 元数据来源少于 5 个 | 构建或配置校验失败 |
| APK CDN 地址/不同主机少于 5 个 | Actions 失败，不更新 JSON |
| 一个 CDN 返回低 revision | 拒绝旧缓存，继续使用可信高 revision |
| 同一 revision 内容不同 | 拒绝冲突 revision，不更新并告警 |
| 权威不可用、签名有效 | 选择最高合法 revision |
| 权威不可用、无签名且只有一个镜像结果 | 不信任；普通更新重试，强制策略 fail-closed |
| CDN APK 与 JSON SHA-256 不同 | 删除临时文件并切换下一源 |

### 11.7 推荐 CDN/来源列表与 URL 模板

以下列表整理自 iKanAPP、Guise Reborn、知了现有实现，核对日期为 2026-08-12。域名可用性、限流和服务条款会变化；除 GitHub 官方地址外均不得视为永久可信，必须经过发布时健康检查和 SHA-256 校验。

占位符：`{owner}` 为 GitHub 用户/组织，`{repo}` 为仓库，`{ref}` 推荐使用提交 SHA，`{path}` 为 JSON 路径，`{tag}` 为 Release 标签，`{apk}` 为 APK 文件名，`{officialUrl}` 为完整 GitHub Release 下载地址。

#### 11.7.1 元数据检查来源

建议至少配置下列 7 个地址。GitHub Contents API 作为权威源；镜像应优先使用提交 SHA，而不是长期使用 `main/master`，以减少可变缓存冲突。

| 顺序 | 来源 | URL 模板 | 定位 |
| --- | --- | --- | --- |
| 1 | GitHub Contents API | `https://api.github.com/repos/{owner}/{repo}/contents/{path}?ref={ref}` | 唯一权威源，可返回 Base64 包装 |
| 2 | GitHub Raw | `https://raw.githubusercontent.com/{owner}/{repo}/{ref}/{path}` | 官方原始内容兜底 |
| 3 | jsDelivr | `https://cdn.jsdelivr.net/gh/{owner}/{repo}@{ref}/{path}` | CDN 镜像 |
| 4 | jsDelivr Fastly | `https://fastly.jsdelivr.net/gh/{owner}/{repo}@{ref}/{path}` | CDN 镜像 |
| 5 | jsDelivr Gcore | `https://gcore.jsdelivr.net/gh/{owner}/{repo}@{ref}/{path}` | CDN 镜像 |
| 6 | jsDelivr TestingCF | `https://testingcf.jsdelivr.net/gh/{owner}/{repo}@{ref}/{path}` | CDN 镜像，发布前实测 |
| 7 | Statically | `https://cdn.statically.io/gh/{owner}/{repo}/{ref}/{path}` | 独立镜像候选，发布前实测 |

注意：多个 `*.jsdelivr.net` 域名不一定代表完全独立的故障域。它们满足多入口容灾，但不能替代唯一权威源、policyRevision、内容摘要和回滚保护。如产品要求“5 个独立服务商”，应补充自建 CDN、对象存储或其他经过审核的供应商。

#### 11.7.2 APK 下载地址

官方原始地址：

```text
https://github.com/{owner}/{repo}/releases/download/{tag}/{apk}
```

建议由 Actions 从下面候选池实时筛选至少 5 个不同主机写入 `urls`，不要永久硬编码“前 5 个一定可用”：

| 优先级 | 主机 | URL 模板 |
| --- | --- | --- |
| 1 | `ghfast.top` | `https://ghfast.top/{officialUrl}` |
| 2 | `gh-proxy.com` | `https://gh-proxy.com/{officialUrl}` |
| 3 | `ghproxy.net` | `https://ghproxy.net/{officialUrl}` |
| 4 | `gh.llkk.cc` | `https://gh.llkk.cc/{officialUrl}` |
| 5 | `ghp.keleyaa.com` | `https://ghp.keleyaa.com/{officialUrl}` |
| 6 | `gh.monlor.com` | `https://gh.monlor.com/{officialUrl}` |
| 7 | `xget.xi-xu.me` | `https://xget.xi-xu.me/gh/{owner}/{repo}/releases/download/{tag}/{apk}` |

备用候选池：

| 主机 | URL 模板 | 备注 |
| --- | --- | --- |
| `ghproxy.vip` | `https://ghproxy.vip/{officialUrl}?sha={sha256}` | 支持时携带预期 SHA-256 |
| `gh.jasonzeng.dev` | `https://gh.jasonzeng.dev/{officialUrl}` | 发布前实测 |
| `gh.3w.pm` | `https://gh.3w.pm/{officialUrl}` | 发布前实测 |
| `gh-proxy.org` | `https://gh-proxy.org/{officialUrl}` | 发布前实测 |
| `v6.gh-proxy.com` | `https://v6.gh-proxy.com/{officialUrl}` | IPv6/备用入口，发布前实测 |
| `v6.gh-proxy.org` | `https://v6.gh-proxy.org/{officialUrl}` | IPv6/备用入口，发布前实测 |

#### 11.7.3 Actions 选源规则

1. 对全部候选并发执行 HEAD；若不支持 HEAD，再执行带超时的 GET。
2. 要求最终 URL 仍为 HTTPS、HTTP 为 2xx、响应不是 HTML 错误页，Content-Length 与 APK size 一致或可解释。
3. 下载并计算 SHA-256；只有与 Release APK 完全一致的候选才进入 JSON。不能只凭 HTTP 200 判定可用。
4. 按成功率和延迟排序，选择至少 5 个不同主机；官方 GitHub 原始地址放在列表末尾作为额外兜底。
5. 若通过完整 SHA-256 校验的第三方主机不足 5 个，则 Actions 失败并保持上一版 JSON，不能发布一个不满足数量要求的新清单。
6. 客户端仍需逐源重新校验 SHA-256，因为发布后的 CDN 内容可能变化。
## 12. 接入时必须确认

- stable/beta 规则和元数据权威源。
- 自动检查延迟、红点清除时机、下载后跳转安装的前后台策略。
- DownloadManager 或应用内下载器。
- CDN 列表及其健康检查/淘汰机制。
- 强制更新策略文件、修改权限和 policyRevision 规则。
- FileProvider、通知权限、未知来源授权和签名轮换策略。
