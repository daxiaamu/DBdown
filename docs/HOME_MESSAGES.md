# 首页轮播消息

配置文件：[config/home-messages.json](../config/home-messages.json)。修改后推送到仓库 main 分支即可生效，无需重新发布 APK（需要已安装支持此功能的版本）。

```json
{
  "schemaVersion": 1,
  "enabled": true,
  "intervalSeconds": 5,
  "messages": [
    { "id": "notice", "text": "这里填写消息" },
    { "id": "project", "text": "查看项目", "url": "https://github.com/daxiaamu/DBdown" }
  ]
}
```

- `enabled`：总开关。设为 `false` 隐藏整个消息区，可省略其他字段（保留 schemaVersion）。
- `intervalSeconds`：切换秒数，范围 2–120。只有一条时不自动切换。
- `messages`：最多 30 条；空数组隐藏。`id` 必须唯一，最长 80 字符；`text` 为 1–300 字符。
- `url`：可选，支持 HTTP/HTTPS 超链接，由系统打开；没有链接的消息不响应点击。
- 支持手动左右滑动；回到首页/前台重新开始计时，后台不轮播。

拉取复用 `UpdateSource.sources()`、网络客户端及读取函数：同一仓库/分支、GitHub API/Raw 与相同 CDN 列表、HTTPS、超时、响应大小限制、取消请求、缓存规避参数。并行请求，按权威源优先顺序选用合法配置；失败时回退 CDN。消息配置不参与版本更新清单的哈希和强制更新策略。

应用回到前台刷新，前台每 5 分钟刷新；请求失败保留本次运行最后一次有效配置，首次失败不展示。总开关在成功拉取新配置后生效；离线时不能保证立即获知远程关闭。配置不持久缓存。
