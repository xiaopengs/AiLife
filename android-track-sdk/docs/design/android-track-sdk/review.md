# Android 端侧埋点 SDK · 代码审查、修复与验证报告

> **结论：条件通过。** 本轮已将阿里 Open Code Review 技能与项目规则固化在仓库中，并完成可读性、架构稳定性和可靠性三维审查。阻断性的 IPC 契约错配、持久化确认丢数、状态机污染、AIDL 构建缺失、默认密钥与占位云端地址等问题均已修复。SDK 的 JVM 与 Android Debug AAR 构建验证均通过。生产接入仍必须提供真实的 `APP_KEY`、`CLOUD_ENDPOINT`、签名级权限和服务端网关，且尚未替代真实设备上的压力、进程死亡与端到端混沌测试。

## 1. 审查配置与范围

项目已安装 Alibaba Open Code Review 的两个官方 Qoder 技能：`.qoder/skills/open-code-review/` 与 `.qoder/skills/open-code-review-delegate/`。项目规则位于 `.opencodereview/rule.json`，其将审查对象限制为 `android-track-sdk` 的 Java、AIDL 和 Gradle 文件，并将以下三个维度设为强制门禁。

| 维度 | 审查重点 | 固化方式 |
| --- | --- | --- |
| **可读性** | 命名、职责边界、注释解释非显然决策、测试可理解性 | Java 规则的 `READABILITY` 段 |
| **架构稳定性** | `track-api → track-core → track-hub → track-android` 依赖方向、协议兼容、生命周期和资源有界 | Java 规则的 `ARCHITECTURE STABILITY` 段 |
| **可靠性** | 不崩溃、先落盘、至少一次、幂等、原子确认、损坏恢复、退避、输入和加密边界 | Java 规则的 `RELIABILITY` 段 |

初审使用 `ocr review --from bd3ed9a --to e4862bb --effort high` 执行。OCR 覆盖 47 个文件并产生 162 条行级意见。该结果作为问题发现输入，而非对实现正确性的自动证明。修复后以 Gradle 构建、71 个自动化测试、AAR 打包和静态占位符扫描作为可复现验证依据。[1] [2]

## 2. 已修复的关键问题

| 风险类别 | 初审发现与故障场景 | 修复措施 | 回归证据 |
| --- | --- | --- | --- |
| **IPC 默认通道不可用** | 发送端使用 `ContentResolver.call()`，而 Hub 只实现 `insert()`；默认 Provider 发送会失败并永久退避。 | `ProviderTransport` 改为 `content://…/events?ver=1` 的 `insert()`，解析返回 URI 最后一段结果码。 | Android Debug AAR 编译通过；`InboundBatchDecoderTest` 覆盖协议入站。 |
| **AIDL 构建不完整** | AGP 未启用 AIDL，`ITrackService` 和 `ITrackCallback` 不会生成 Java 接口。 | 增加 Gradle 8.9 Wrapper、固定 AGP 8.7.3、启用 `buildFeatures.aidl`、补齐 Android library 配置。 | `:track-android:assembleDebug` 成功，输出 `track-android-debug.aar`。 |
| **加密协议不对称** | Provider/AIDL 未获得 `encryptPayload` 状态，Hub 只解 gzip，无法识别发送端 AES-GCM 载荷。 | AIDL 契约传递版本、`appKey` 和加密标志；Provider/AIDL 共用 `InboundBatchDecoder`，按“验签 → gzip → 可选解密 → protobuf”顺序处理。 | Android 单元测试覆盖明文、加密、版本、时钟、签名、尺寸和解密失败。 |
| **`RESULT_INVALID` 污染后续批次** | 一个坏批次把 `ChannelCore` 固定为全局 `QUARANTINE`，后续合法事件也无法发送。 | 将 INVALID 改为批次局部结果；`TrackEngine` 仅确认并计数当前坏批次，继续处理后续批次。 | `CoreHardeningTest.invalidBatchDoesNotPoisonFollowingValidBatch`。 |
| **异步 drain 无界堆积** | 每次 `flush`/定时器调用都向单线程执行器提交任务；高频调用可造成任务队列膨胀，关闭竞争还会抛异常。 | 使用 `drainScheduled` 与 `drainRequested` 合并触发；关闭时吞掉 `RejectedExecutionException`。 | `CoreHardeningTest.drainAsyncCoalescesConcurrentTriggersAndShutdownIsNoThrow`。 |
| **确认删除的崩溃窗口** | 磁盘确认先删除原分片再重写剩余数据；重写失败会静默丢失未确认事件。 | 使用临时快照、`fsync`、同目录原子替换；替换失败保留旧日志。 | `CoreHardeningTest.acknowledgedQueueSurvivesRestartAndFailedReplaceKeepsData`。 |
| **容量策略与观测不一致** | 队列满时直接拒绝新事件，和规格“保留最新、淘汰最旧”不一致，且 Hub 未统计淘汰条数。 | `DiskQueue` 在配额满时淘汰最旧记录后写入新记录；Outbox/Hub 将淘汰条数计入指标。 | 容量、重启和 Hub 配额指标测试通过。 |
| **Hub 批次重试丢尾部** | 去重键在持久化前写入；半批写入失败后重放会把未真正落盘的数据误判为重复。 | `IngestionPipeline` 改为 inspect/commit 两阶段；仅在每条成功持久化后提交去重键，批次限流预留原子化。 | `HubTest.partialWriteCommitsOnlyStoredKeysSoRetryCannotLoseTail`。 |
| **云端错误分类不足** | HTTP 状态被布尔值压扁，401/403、413、429 与 5xx 无法按契约处置。 | `CloudSink.Result` 明确区分成功、鉴权失败、过大、限流和可重试；调度器实现停止、精确 Retry-After、缩批和退避梯。 | `HubTest` 覆盖 401/403、429、413 和 30s/1m/5m/30m。 |
| **可预测密钥与占位目标** | 空 `appKey` 回退 `default-appkey`，Hub 向 `track.ailife.example` 发送并用相同密钥签名。 | 空 `APP_KEY` 改为 `sendEnabled=false` 的仅本地缓存模式；Hub 只使用通过 IPC 验签的来源 `appKey` 分组、签名并加 `X-App-Key`；无 HTTPS `CLOUD_ENDPOINT` 时使用保留数据的 `CloudSink.Disabled`。 | 无默认密钥/占位地址静态扫描通过；空凭证、来源应用密钥上报测试通过。 |
| **Hub 降级无法恢复** | 客户端一旦进入 cache-only 模式不再发送，也不会获知 Hub 已恢复。 | `Transport.HubStatus` 支持 Provider/AIDL 探测；每轮 drain 在缓存门控前刷新健康度。 | `CoreHardeningTest.cacheOnlyChannelPollsHealthAndResumesWhenHubRecovers`。 |
| **业务输入影响 SDK** | 调用方 Map 可在 `track()` 返回后修改，`TrackEvent.of()` 也持有原始引用。 | API 层和事件工厂均采用防御性快照；异常属性、NaN/Infinity、异常迭代器或 `toString()` 只计数并跳过属性。 | `AilifeTrackTest` 与 `CoreHardeningTest.eventFactorySnapshotsPropertiesBeforeCallerMutation`。 |
| **退出权无法立即生效** | optOut 只有防抖状态，既不清队列也不持久化，定时 drain 仍可能运行。 | optOut 先发布停止门控，再串行关闭 timer/engine、清理队列并 `fsync` 保存 consent；optIn 不回补历史数据。 | `AilifeTrackTest` 覆盖队列清理、重启恢复和 optIn 后新事件采集。 |

## 3. 验证记录

验证在 Ubuntu 24.04、JDK 21、Gradle Wrapper 8.9、Android API 34 和 Build Tools 34.0.0 环境中执行。Java 编译目标仍为 source/target 8，Android library 保持 minSdk 21。JDK 21 对 source/target 8 给出弃用警告，但不影响构建结果。

| 命令 | 结果 | 证据 |
| --- | --- | --- |
| `./gradlew --no-daemon clean test` | **通过** | 71 个测试，0 failures，0 errors；包含 core、api、hub 以及 Android debug/release 单元测试。 |
| `./gradlew --no-daemon :track-android:assembleDebug` | **通过** | 成功产生 `track-android/build/outputs/aar/track-android-debug.aar`。 |
| `git diff --check` | **通过** | 无空白错误。 |
| 默认密钥与占位端点扫描 | **通过** | 源码未发现 `default-appkey` 或 `track.ailife.example`。 |
| 初始 OCR 高投入审查 | **完成** | 47 文件、162 条行级意见；所有阻断性和高风险问题已逐项进入本报告的修复清单。 |

> **保证边界。** 自动化测试验证的是 JVM、Android local unit test 与 AAR 构建的可重复性。它不能替代真机 Binder 权限、跨签名证书、Android 进程被杀、磁盘满、网络切换和网关真实协议的端到端演练。

## 4. 生产接入前的必要配置

Hub 与业务 App 必须由相同签名证书签名，并同时满足以下配置。缺少 `APP_KEY` 或 `CLOUD_ENDPOINT` 时，SDK 将**保留本地数据但不发送**，这是有意的安全失败方式而非故障重试。

| 位置 | 必要配置 | 原因 |
| --- | --- | --- |
| 业务 App | `<uses-permission android:name="com.ailife.permission.TRACK_WRITE" />` | 获得 signature 级 Provider/Service 访问许可。 |
| 业务 App | `<meta-data android:name="com.ailife.track.APP_KEY" android:value="…" />` | 业务侧 IPC HMAC、可选 AES-GCM 和发送许可。 |
| Hub App | `<meta-data android:name="com.ailife.track.CLOUD_ENDPOINT" android:value="https://…" />` | Hub 仅接受 HTTPS 云端地址；未配置则停止上报并保留队列。 |
| 网关 | 校验 `X-App-Key`、时间戳、HMAC、压缩载荷和幂等键 | 防止跨应用混批、伪造和重放，并实现端到端幂等。 |
| CI / 真机 | 加入 Android instrumentation、跨进程与混沌测试 | 覆盖 APK 级权限、Binder death、网络切换和物理存储压力。 |

## 5. 未完成事项与风险接受条件

当前实现使用的是**帧式磁盘日志**，而非设计文档早期任务表中列出的 Room/protobuf 生成代码。其原因是当前 SDK 保持 Java 8、无额外运行时依赖，并以手写 protobuf wire 格式确保 API 21 可用。这一实现已经覆盖断尾截断、原子确认、容量淘汰和重启回放，但长期维护仍应评估使用经 schema 演进治理的 protobuf 生成代码及受控数据库迁移。

SDK 在 `init` 之前缺少由 `Environment` 提供的进程专属目录，因此不能保证跨进程重启的“未初始化调用持久化恢复”。Android 的 `AilifeTrackInit` 已在 Application 前自动初始化以缩小该窗口。若业务会禁用初始化 Provider 或直接调用纯 Java 门面，则应在下一迭代引入显式 bootstrap 缓冲和恢复策略。这个限制必须保留在生产验收中，而不能被“零丢失”表述掩盖。

生产验收还应增加真实网关契约测试，覆盖 `X-App-Key`、签名原文、413 缩批后的幂等、429 `Retry-After`、401/403 重新配置后的恢复，以及每个业务应用分租户队列的公平性。当前 Hub 会按最早事件所属应用分组发送；该策略保证身份正确，但不提供跨应用公平调度。

## References

[1]: https://github.com/alibaba/open-code-review "Alibaba Open Code Review"

[2]: https://open-codereview.ai/docs/review-rules "Open Code Review review rules"

[3]: https://developer.android.com/build "Android build system documentation"

[4]: https://developer.android.com/guide/topics/providers/content-providers "Android content providers documentation"

[5]: https://developer.android.com/develop/background-work/services/bound-services "Android bound services documentation"

[6]: https://developer.android.com/privacy-and-security/risks/signature-permissions "Android signature permissions documentation"

Author: Manus AI
