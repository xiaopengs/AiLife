# Android 端侧埋点 SDK

`android-track-sdk` 是端侧数据中台方案的 Java 实现。业务进程通过 `AilifeTrack` 采集事件，先写本进程持久化队列，再经 **ContentProvider（默认）或 AIDL** 发送至 Hub 进程。Hub 完成校验、去重、本地持久化、健康计算和云端上报。设计采用 **至少一次投递 + `dedupKey` 幂等**；传输超时、进程死亡和重试可能产生重复投递，因此“恰好一次效果”取决于 Hub 和网关两侧的去重。

> **安全默认值：** `APP_KEY` 或 Hub 的 HTTPS `CLOUD_ENDPOINT` 未配置时，数据会保留在本地队列，但 SDK 不会使用默认密钥或占位地址发送。生产接入必须显式配置两项。

完整设计、审查修复记录和验证边界见 [独立设计交付](docs/design/android-track-sdk/README.md) 与 [代码审查报告](docs/design/android-track-sdk/review.md)。

## 模块

| 模块 | 职责 | 依赖 |
| --- | --- | --- |
| `track-core` | Java 8 内核：帧式磁盘队列、Outbox、状态机、protobuf wire 编解码、gzip、HMAC、AES-GCM、退避和限流 | 无 Android 依赖 |
| `track-api` | 业务门面 `AilifeTrack`、输入隔离、全局属性快照、退出权状态 | `track-core` |
| `track-hub` | Hub 校验/去重/存储、上报调度、HTTP 结果分类、健康降级 | `track-core` |
| `track-android` | Provider/AIDL 通道、Manifest、自动初始化、Android 环境适配 | 其他三个模块 |

事件路径如下：

```text
业务代码 → AilifeTrack → Outbox / DiskQueue（先落盘）
       → ChannelCore → Provider 或 AIDL（gzip + HMAC，按配置 AES-GCM）
       → Hub 校验 / 去重 / HubEventStore（先落盘）
       → ReportScheduler → HTTPS 网关
```

## 接入配置

### 1. Hub 应用

Hub 应用承载 `AilifeTrackProvider` 和可选的 `AilifeTrackService`。其与业务应用必须由**相同签名证书**签名，因为写入权限为 signature 级。`CLOUD_ENDPOINT` 必须为 HTTPS 地址；未提供或不合法时 `CloudSink.Disabled` 保留 Hub 队列并停止发送。

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <permission
        android:name="com.ailife.permission.TRACK_WRITE"
        android:protectionLevel="signature" />

    <application>
        <meta-data
            android:name="com.ailife.track.CLOUD_ENDPOINT"
            android:value="https://track.example.com" />

        <provider
            android:name="com.ailife.track.AilifeTrackProvider"
            android:authorities="com.ailife.dataplatform.track"
            android:exported="true"
            android:readPermission="com.ailife.permission.TRACK_WRITE"
            android:writePermission="com.ailife.permission.TRACK_WRITE"
            android:process=":hub" />

        <service
            android:name="com.ailife.track.AilifeTrackService"
            android:exported="true"
            android:permission="com.ailife.permission.TRACK_WRITE"
            android:process=":hub">
            <intent-filter>
                <action android:name="com.ailife.track.aidl.ITrackService" />
            </intent-filter>
        </service>
    </application>
</manifest>
```

### 2. 业务应用

业务应用声明写权限、配置不可为空的 `APP_KEY`，并注册非导出的初始化 Provider。初始化 Provider 在 Application 代码执行前创建 SDK；因此正常 Android 接入不会出现未初始化的采集窗口。

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="com.ailife.permission.TRACK_WRITE" />

    <application>
        <provider
            android:name="com.ailife.track.AilifeTrackInit"
            android:authorities="${applicationId}.ailife-track-init"
            android:exported="false"
            android:initOrder="100" />

        <meta-data
            android:name="com.ailife.track.APP_KEY"
            android:value="replace-with-server-issued-app-key" />
        <meta-data
            android:name="com.ailife.track.CHANNEL"
            android:value="provider" />
        <meta-data
            android:name="com.ailife.track.ENCRYPT"
            android:value="true" />
    </application>
</manifest>
```

可选的 `CHANNEL` 值为 `provider`（默认）和 `aidl`。可选配置还包括 `FLUSH_INTERVAL_MS`、`BATCH_COUNT`、`TTL_DAYS` 和 `QUEUE_MB`。非法边界值会回退安全默认值，并记录在 `TrackConfig.validationNotes` 中。空 `APP_KEY` 不会被替换为可预测字符串，而是进入本地缓存模式。

### 3. 业务调用

```java
AilifeTrack.track("page_view", Collections.singletonMap("page", "home"));
AilifeTrack.trackList(events);
AilifeTrack.flush();
AilifeTrack.optOut(); // 立即停止采集、清队列并持久化退出状态
AilifeTrack.optIn();  // 只采集之后的新事件
TrackStatus status = AilifeTrack.getStatus();
```

公共入口隔离业务输入异常。属性会在入队前进行防御性快照；异常迭代器、非字符串 key、NaN/Infinity 和抛异常的 `toString()` 仅导致该属性被跳过和计数，不会令事件主体抛出异常。`eventId` 为空、空白或超过 128 字符时会被拒绝并计数。

## 传输与可靠性语义

| 场景 | 行为 |
| --- | --- |
| Provider/AIDL 成功 | Hub 已接受并持久化后返回 `RESULT_SUCCEEDED`，发送端才确认删除本地记录。 |
| `RESULT_THROTTLED` / `RESULT_RETRY_LATER` | 发送端保留原批次并退避。 |
| `RESULT_INVALID` | 仅隔离并计数当前坏批次；后续合法批次仍可发送。 |
| Binder 死亡、异常或超时 | 发送端保留数据，按 1s 指数退避至 30s。 |
| 业务或 Hub 队列满 | 淘汰最旧记录以保留最新记录，并累加可查询的淘汰计数。 |
| 磁盘写入中断 | 下次读取截断断尾帧；确认压缩使用临时文件、`fsync` 和同目录替换。 |
| Hub 健康度低于 0.6 | 发送端通过 Provider/AIDL 状态探测进入仅缓存；定时 drain 仍探测健康度以恢复发送。 |
| 云端 401/403 | Hub 停止上报并保留队列。 |
| 云端 429 / 413 / 5xx | 分别按 `Retry-After`、缩批、30s/1m/5m/30m 阶梯处理；仅 2xx 删除 Hub 记录。 |

IPC 入口在验签后将已认证的 `appKey` 写入事件的 Hub 内部持久化元数据。Hub 按来源应用分组，以该密钥生成上游 HMAC 并设置 `X-App-Key`。业务 payload 中同名字段不被信任。

## 构建与验证

项目已提交 Gradle 8.9 Wrapper，`track-android` 固定 Android Gradle Plugin 8.7.3、compileSdk 34 和 minSdk 21；所有模块保持 Java 8 字节码目标。

```bash
cd android-track-sdk
./gradlew --no-daemon clean test
./gradlew --no-daemon :track-android:assembleDebug
```

本次验证在 JDK 21、Android API 34 和 Build Tools 34.0.0 下完成：**71 个自动化测试通过，0 failures，0 errors**，并成功产出 `track-android-debug.aar`。JDK 21 会对 Java 8 source/target 打出弃用警告；该警告不影响当前构建结果。

## 已知边界

SDK 的纯 Java 门面在 `Environment` 提供队列目录之前无法创建跨进程重启后仍可恢复的持久化队列。Android 正常接入通过 `AilifeTrackInit` 在 Application 之前初始化来避免该窗口；若业务显式禁用初始化 Provider，则必须接受未初始化调用不具备跨重启持久化保证。

当前验证覆盖 JVM、Android local unit test 和 Debug AAR 打包。生产发布前仍应在真机或设备农场执行跨签名权限、Binder death、杀业务/Hub 进程、磁盘满、网络切换、网关状态矩阵和端到端幂等演练。

## 代码审查

阿里 Open Code Review 的项目级技能在 `.qoder/skills/`，三维门禁规则在 `.opencodereview/rule.json`。使用以下命令复审当前改动：

```bash
ocr review --audience agent --background-file android-track-sdk/docs/design/android-track-sdk/review.md
```

详细问题、修复和验证记录见 [review.md](docs/design/android-track-sdk/review.md)。
