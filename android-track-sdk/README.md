# Android 端侧埋点 SDK（ailife-track-sdk）实现

本目录是 [设计方案](../docs/design/android-track-sdk/README.md) 的完整 Java 实现：业务 App 集成 SDK 采集埋点，
经 **ContentProvider 或 AIDL 两种可选通道** 跨进程送达端侧数据中台（hub 进程），中台本地存储后按策略
上报云侧。全链路 **先落盘再发送 + dedupKey 幂等**，在进程被杀、Android 冻结、重启、hub 不在线等场景下
保证 **数据不丢、不重（恰好一次效果）**。

## 模块结构

| 模块 | 内容 | 依赖 |
|---|---|---|
| `track-core` | 纯 Java 可测试内核：DiskQueue 落盘队列、Outbox 发件箱、ChannelCore 通道状态机、BatchCodec(Proto+Gzip)、Signature(HMAC/AES-GCM)、Deduplicator、RateLimiter、BackoffPolicy、TrackEngine | 无 |
| `track-api` | 业务侧门面 `AilifeTrack`（track/trackList/flush/setUserProfile/optOut/optIn/getStatus）+ `Environment` SPI | track-core |
| `track-hub` | 端侧数据中台引擎：IngestionPipeline（校验/去重/限流）、HubEventStore（TTL/配额）、ReportScheduler（批量上报+重试阶梯）、HealthManager（健康分与降级）、CloudSink | track-core |
| `track-android` | Android 绑定层：`AilifeTrackProvider`（ContentProvider 通道）、`AilifeTrackService`+AIDL（AIDL 通道）、`ProviderTransport`/`AidlTransport`、`AilifeTrackInit`（androidx.startup 式无依赖引导）、`AndroidEnvironment` | 其余三个模块 |

数据流：`业务代码 → AilifeTrack.track() → TrackEngine（内存队列，P95 7µs）→ 单线程异步 drain →
DiskQueue 落盘 → Outbox 组批（Proto + Gzip + HMAC 签名，可选 AES-GCM 加密）→ Provider/AIDL 通道 →
hub IngestionPipeline（校验+去重+限流）→ HubEventStore 落盘 → ReportScheduler 批量上报云侧（失败重试阶梯 30s/1m/5m/30m）。

## 快速接入

### 1. 数据中台 App（hub 侧，包名约定 `com.ailife.dataplatform`）

```xml
<manifest ...>
    <application ...>
        <!-- 端侧数据中台：独立进程运行，Provider 通道入口 -->
        <provider
            android:name="com.ailife.track.AilifeTrackProvider"
            android:authorities="com.ailife.dataplatform.track"
            android:exported="true"
            android:process=":hub" />

        <!-- 可选：AIDL 通道入口（业务 App 配置 CHANNEL=aidl 时使用） -->
        <service
            android:name="com.ailife.track.AilifeTrackService"
            android:exported="true"
            android:process=":hub">
            <intent-filter>
                <action android:name="com.ailife.track.aidl.ITrackService" />
            </intent-filter>
        </service>
    </application>
</manifest>
```

hub 侧无需初始化代码：`AilifeTrackProvider.onCreate()` 内部构建 `HubController` 单例；
`AilifeTrackInit` 检测到 hub 包名时跳过采集初始化，避免自采自报。

### 2. 业务 App（集成方）

```xml
<manifest ...>
    <application ...>
        <!-- 自动初始化（androidx.startup 模式，无 androidx 依赖），在 Application 之前就绪 -->
        <provider
            android:name="com.ailife.track.AilifeTrackInit"
            android:authorities="${applicationId}.ailife-track-init"
            android:exported="false" />

        <!-- 通道选择：provider（默认）或 aidl -->
        <meta-data android:name="com.ailife.track.APP_KEY"  android:value="your-appkey" />
        <meta-data android:name="com.ailife.track.CHANNEL"  android:value="provider" />
        <!-- 可选配置（均有校验，越界回落默认值并记录 validationNotes）：
        <meta-data android:name="com.ailife.track.FLUSH_INTERVAL_MS" android:value="5000" />
        <meta-data android:name="com.ailife.track.BATCH_COUNT"       android:value="50" />
        <meta-data android:name="com.ailife.track.TTL_DAYS"          android:value="3" />
        <meta-data android:name="com.ailife.track.QUEUE_MB"          android:value="20" />
        <meta-data android:name="com.ailife.track.ENCRYPT"           android:value="true" />
        -->
    </application>
</manifest>
```

业务代码只需一行：

```java
AilifeTrack.track("page_view", Collections.singletonMap("page", "home"));
AilifeTrack.trackList(events);          // 批量
AilifeTrack.flush();                    // 立即触发一次上报
AilifeTrack.optOut();                   // 10s 防抖退出采集（合规）
AilifeTrack.getStatus();                // pending/健康分/降级等级
```

### 3. Gradle

```bash
./gradlew :track-android:assembleRelease   # AAR（含 AIDL）
./gradlew test                             # 全部单元测试
```

`settings.gradle` 已包含四个模块；`track-android` 为 `com.android.library`（namespace `com.ailife.track`，
minSdk 21，compileSdk 34），其余为纯 Java 库（source/target 8，JDK 21 编译验证通过）。

## 数据不丢设计（稳定性/边界/异常矩阵）

核心机制：**发送端先落盘（DiskQueue + Outbox），hub 侧先落盘（HubEventStore）再上报，两端以
dedupKey 幂等去重 ⇒ at-least-once 投递 + 恰好一次效果**。

| 场景 | 机制 | 验证测试 |
|---|---|---|
| 业务进程被杀 | 事件先写 DiskQueue（帧校验 + 断尾截断），重启后从磁盘回放重发 | `OutboxEngineTest.processKillThenRestartZeroLoss` |
| hub 进程被杀 / binder 死亡 | `DEAD_OBJECT` → 保留数据退避重试（1s×2 封顶 30s ±20% 抖动） | `ChannelCoreTest`、`OutboxEngineTest.deadObjectKeepsDataForRetry` |
| 通道限流（RESULT_THROTTLED） | 不丢不删，退避后重发 | `throttledRetryLaterKeepsData` |
| 数据非法（RESULT_INVALID） | 隔离区（quarantine）+ 计数，不阻塞后续批次 | `invalidBatchQuarantinedNotLost` |
| Android 冻结（时钟大幅跳跃） | 基于 TimeSource 抽象，解冻后窗口/退避立即恢复正常 | `freezeResumeSendsPending` |
| 设备离线 | hub ReportScheduler hold 住数据，网络恢复后按阶梯上报 | `HubTest.offlineHoldsThenDrains` |
| 云端上报失败 | 重试阶梯 30s/1m/5m/30m，期间数据留在 HubEventStore | `retryLadderBacksOffThenSucceeds` |
| hub 容量超限 / TTL 过期 | 配额淘汰与 TTL 清扫均**计数上报**，可审计不静默丢失 | `quotaEvictionCounted`、`ttlSweepEvictsExpired` |
| 重复投递（超时重发） | hub 24h 去重窗口（20 万 LRU），重复批次计 duplicate | `dedupIsIdempotent` |
| 单条超大 / 属性超长 | MAX_PROP_LEN 截断入库，单事件 1MB 上限，超限计数 | `oversizePropIsTruncatedNotStored` |
| 磁盘队列损坏（写一半掉电） | 帧长度校验 + 断尾截断，坏帧之后数据仍可读 | `DiskQueueTest.tornTailTruncatedOnRead` |
| 队列配额打满 | 拒绝新事件并计数（保老数据），不覆盖 | `DiskQueueTest.quotaRefusedWhenFull` |
| 发送预算超时 | 每批次 8s 预算，超时判 TIMEOUT 走退避 | `ChannelCoreTest.timeoutGoesToBackoff` |
| 健康度持续劣化 | 健康分 < 0.6 降级 cache-only；≥0.8 持续 10 分钟按 1/4→1/2→1 爬坡恢复 | `healthDegradesToCacheOnly`、`healthRecoversByRamp` |
| 配置非法 / 时钟偏移 | 越界回落默认值 + validationNotes；ingest 拒绝超 ±5min 时钟偏移 | `TrackConfigTest`、`HubTest.clockSkewBeyondFiveMinutesRejected` |
| 合规退出 | optOut 10s 防抖，退出后不采集不入队 | `AilifeTrack`（E12） |

## 验证结果（JDK 21，JUnit 4）

```
track-core : OK (23 tests)   DiskQueue 9 + Outbox/Engine 9 + ChannelCore 5
track-hub  : OK (8 tests)    去重/时钟/配额/TTL/重试阶梯/离线/健康降级恢复/端到端零丢失
track-api  : OK (7 tests)    配置校验/通道可选/状态/Proto 边界/限流
合计 38 个测试全部通过
```

性能门禁（实测）：入队 P50 2µs / P95 7µs（预算 2ms）；编码 1µs/事件；hub ingest P95 1µs（预算 10ms）。

说明：沙箱无 android.jar，`track-android` 通过自建 stub jar 完成类型检查（保证 API/调用正确），
在真实 Android 构建环境（AGP + compileSdk 34）下可直接编译；`build.gradle` 已就位。

## 关键类索引

- 发送端内核：`track-core/.../TrackEngine.java`（单 daemon 线程）、`Outbox.java`、`DiskQueue.java`、`ChannelCore.java`
- 通道实现：`track-android/.../ProviderTransport.java`（ContentResolver.call）、`AidlTransport.java`（bindService + 8s latch）
- 中台引擎：`track-hub/.../HubController.java`（5s tick）、`IngestionPipeline.java`、`ReportScheduler.java`、`HealthManager.java`
- AIDL 契约：`track-android/src/main/aidl/com/ailife/track/aidl/ITrackService.aidl`、`ITrackCallback.aidl`
