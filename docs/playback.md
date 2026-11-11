# 播放直连策略

## 设计边界

视频媒体请求由官方播放器负责连接复用、HTTP Range、缓存、重试和解码。模块不再提供「播放多 CDN 加速」本地 HTTP 代理，不进行播放前联网测速，也不增加一层分块、竞速或预读缓存。

独立的多主机下载和直播备用 CDN 功能不受此项移除影响。旧配置中的 `media_proxy_enabled` 不再被读取或导出，无需清空用户其他偏好。

## 自定义 CDN

- 未配置节点时保留官方原始地址；蜂窝配置为空时跟随常规配置。
- 配置节点按用户顺序参与规划，保留官方有效主地址及各备用地址自己的路径、签名和查询串。
- 仅对受支持媒体域名中、签名家族兼容且路径为 `/upgcxcode/` 的新鲜 UPOS 地址替换域名。
- 不跨 Akamai 签名家族，不改写第三方地址或查询串中伪装的媒体路径。
- 已过期及 PCDN 地址不作为规划源；临期但未过期地址只保留为备源，不复制到新节点。
- 无安全候选或无法写入备用列表时，保留原响应，不清空唯一回源。
- 开启「音频不跟随 CDN 设置」时，完整保留官方音频主地址和备源。

是否使用、何时切换备用地址由官方播放器决定。响应中存在备用数组不代表已验证故障恢复成功，也不保证任意节点在所有网络下最快。

## 实现入口

- [`PlaybackCdnPlan`](../app/src/main/java/io/github/bbzq/feats/hook/PlaybackCdnPlan.kt)：无网络 I/O 的地址规划。
- [`CustomCdnProcessor`](../app/src/main/java/io/github/bbzq/feats/hook/CustomCdnProcessor.kt)：按类型缓存反射访问器、按对象身份去重遍历；写入失败保留原地址。
- [`CustomCdnHook`](../app/src/main/java/io/github/bbzq/feats/hook/CustomCdnHook.kt)：独立处理同步响应和异步回调，不依赖画质试用开关。

## 验证要求

[`PlaybackCdnPlanTest`](../app/src/test/java/io/github/bbzq/feats/hook/PlaybackCdnPlanTest.kt) 和 [`CustomCdnResponseTest`](../app/src/test/java/io/github/bbzq/feats/hook/CustomCdnResponseTest.kt) 覆盖签名保留、过期边界、路径限制、幂等、音频独立、字段回退及备用列表不可写等场景。

本次变更仅做静态检查，未本地编译、执行测试或真机验证。发布前应执行构建与测试，并核验起播、拖动、连播、断线回退和网络切换；未测量首帧或重缓冲率，不提供性能提升比例。
