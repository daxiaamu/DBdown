# DBDown 更新接入说明

本次按 [android-app-update skill](https://github.com/daxiaamu/update-by-github-skill/blob/main/SKILL.md) 和 requirements.md 实现。采用的规范快照在 docs/update-skill/。GitHub 仓库：daxiaamu/DBdown，分支 main。

## 客户端

- 设置最下方“关于”卡片显示应用名和 PackageManager 读取的实际版本；右侧“检查更新”文字按钮与进度环共用 64×48dp 槽位。
- 主界面进入前台并可交互 2 秒后检查，每个进程一次。自动失败/最新版静默；手动检查始终给结果。自动、手动共用进行中的会话，已结束的手动请求重新联网。
- versionName 含 alpha/beta/rc 的客户端检查 stable 和 beta，其他只检查 stable。版本大小只比较 versionCode。
- 普通更新：跳过此版本、忽略、下载安装。跳过持久化，但手动检查仍展示；忽略后入口显示红点。下载中禁用关闭与重复点击。
- 强制边界为 currentVersionCode <= maxForcedVersionCode < latestVersionCode。已接受的强制策略保存在本地，网络故障不能清除它；强制弹窗无法关闭或跳过。
- 版本、设备本地时区发布时间、Markdown 日志和错误信息同处固定高度滚动区；动作固定。支持有限 Markdown，不执行 HTML、JS 或加载远程图片。
- APK 使用独立 OkHttp 客户端，不携带 B 站/抖音 Cookie。只访问 HTTPS，手动检查每跳重定向；临时文件限制为 512MiB，校验后原子移动。
- 每次安装及未知来源授权返回都会重新计算当前清单要求的 SHA-256，再验证包名、versionCode、签名。签名采用当前 signer 集合严格相等，暂不接受证书轮换链。
- SHA 失效会删除本地文件并依当前清单重下；CDN 下载或身份验证失败换源。普通视频队列和更新 APK 下载相互独立。
- 下载完仅在宿主仍处于前台时启动授权页/系统安装器；后台完成保留“安装”入口。进程结束后重新检查或展示已知更新，点击安装会验证缓存并继续。没有静默安装。
- FileProvider 只开放 cache/updates；安装意图附带 ClipData 与临时读取权限。

## 来源与信任

每个渠道的 latest.json 并发访问 7 个入口：GitHub Contents API（唯一权威）、GitHub Raw、jsDelivr、Fastly jsDelivr、Gcore jsDelivr、TestingCF jsDelivr、Statically。请求有 cache-buster、超时、no-cache 与 1MiB 响应上限。

采用不带应用层签名的“权威指针 + 不可变清单摘要”方案。指针包含 schemaVersion、channel、policyRevision、manifestPath、manifestSha256、expiresAt。manifestPath 严格限定为：

    updates/{channel}/manifests/{policyRevision}-{manifestSha256}.json

GitHub API 不可用时，使用同一仓库的 GitHub 官方 Raw 地址作为权威兜底；第三方镜像仍不能单独引入未认证的新版本。权威成功后收敛 600ms，拒绝同一 revision 的冲突。接受过的最高 revision、清单摘要及强制边界持久化，禁止回退。无权威时采取比规范更保守的策略：两个不同供应商的镜像只能重用本机此前由权威确认过的清单；不从未认证的新镜像清单引入新版本。多个 jsDelivr 主机只算同一供应商。首次安装且权威不可用时报告失败，不误报最新版。

不可变清单结构：

    {
      "schemaVersion": 1,
      "channel": "stable",
      "versionCode": 10,
      "versionName": "0.6.1",
      "publishedAt": "2026-09-27T08:00:00Z",
      "changelog": "## 更新内容\n- 修复问题",
      "policyRevision": 1,
      "maxForcedVersionCode": 0,
      "sha256": "<APK 的 64 位小写 SHA-256>",
      "size": 12345678,
      "urls": ["<至少 5 个不同 CDN 主机的 HTTPS 地址>", "<额外的 GitHub 原始地址>"]
    }

该示意不是发布用清单。仓库初始为空，不生成虚构更新；实际清单只由已发布 APK 生成。

## 发布流程

工作流：.github/workflows/update-metadata.yml；脚本：scripts/publish_update.py。

1. 将本项目源文件、scripts、updates、.github 工作流提交到指定仓库的 main。不要提交本机 SDK 路径、密钥、构建目录或 artifacts 中历史测试包。
2. 准备同一签名证书的正式 APK。0.6.0 发布使用关闭调试的 release 构建，为覆盖此前真机测试版，沿用本机已有测试签名证书。后续必须持续使用同一私钥；若换用新证书，不能直接覆盖安装，需安排数据迁移。不要把签名私钥或密码提交仓库。
3. 每次发布前人工递增相应 updates/policy-stable.json 或 policy-beta.json 的 policyRevision，保留 maxForcedVersionCode，并填写 reason。初始值 0 表示可选更新；工作流不自动决定强制更新。
4. APK versionCode 必须递增。Release 标签必须为 v + APK versionName；beta 标识与 Release prerelease 一致。每个 Release 恰好上传一个 APK。
5. 人工发布 Release 后自动触发；也可手动选择已发布的标签重试。流程不创建 Release，不上传 APK。
6. 脚本下载 Release APK，使用 aapt2 从 APK 读取包名和版本，apksigner 检查签名有效，再计算 SHA-256。
7. 并发验证 skill 候选池里的 CDN：完整下载并校验哈希、大小、最终 HTTPS 与最终主机去重。至少 5 个第三方主机通过才继续，GitHub 官方只作额外兜底。候选未实测前不承诺可用。
8. 生成不可变清单和 90 天有效的 latest 指针。提交前严格校验，git pull --rebase 后再次校验，再在一个提交内推送。
9. CDN 不足或任一步失败，保持远端上一版指针不变。客户端 cache-buster 避免复用可变指针缓存。指针过期后应发布新 APK/策略 revision；不会自动降低安全策略来继续使用过期指针。

第三方 CDN 可能有地区限制或服务变更，不应被视为永久可用。Actions 使用最小 contents:write 权限和全仓库串行发布锁，凭据仅来自 github.token。候选返回官方地址或相同最终主机的重定向不能凑足 5 个 CDN。

## 验证与当前部署状态

已覆盖协议字段、HTTPS、SHA、CDN 数量、versionCode 比较、revision 防回滚、强制边界保留、同 revision 冲突、时间解析、共享会话和后续手动刷新。发布脚本有不联网的输入校验测试。

真机验证关于入口、长日志动作可见、自动跳过与手动展示、忽略红点、强制策略离线恢复、真实 APK 的包/版本/签名校验路径，以及文件篡改后重新哈希拒绝。

用户已授权推送源码并发布 v0.6.0。Release 后由 Actions 实测至少五个 CDN，成功才发布更新清单；具体执行状态以仓库 Actions 为准。没有发布清单时客户端报告检查失败，不伪造“已是最新版”。受控真机测试覆盖下载失败换源和 APK 篡改拒绝；真实新版本覆盖安装仍需后续更高 versionCode 的版本验收。

构建签名从环境变量 DBDOWN_KEYSTORE、DBDOWN_STORE_PASSWORD、DBDOWN_KEY_ALIAS、DBDOWN_KEY_PASSWORD 注入，仓库不含私钥。未提供时 release 输出未签名 APK，不可直接发布。
