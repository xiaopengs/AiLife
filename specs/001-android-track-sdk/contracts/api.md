# 001-android-track-sdk · 契约与接口设计

> 本文件是 SDK 接口、DataProvider 契约与服务端 API 的权威定义；实现须与本文件一致

## 1. SDK 业务 API（track-api，纯 Java 门面）

```java
// 入口门面：线程安全；除 flush() 外所有方法同步入队、立即返回
public final class AilifeTrack {

    /** 初始化：50ms 内返回（P95），通道连接异步进行；重复调用幂等（以首次配置为准） */
    public static void init(@NonNull Context context, @NonNull TrackConfig config);

    /** 单点埋点：2ms 内返回（P95）；eventId 空或超长（>128 字符）时丢弃并计数，不抛异常 */
    public static void track(@NonNull String eventId,
                             @Nullable Map<String, Object> properties);

    /** 批量埋点：单次至多 200 条，超出自动分批；任一元素非法不影响其余事件 */
    public static void trackList(@NonNull List<TrackEvent> events);

    /** 立即触发一次通道批量出队（不影响合并窗口语义，仅提示调度） */
    public static void flush();

    /** 用户属性：差量合并上报 */
    public static void setUserProfile(@NonNull Map<String, Object> profile);

    /** 退出采集：清空内存与磁盘队列、停止上报、状态持久化（立即生效） */
    public static void optOut();

    /** 恢复采集：重新采集，历史已清理数据不回补 */
    public static void optIn();

    /** 通道状态查询：连接状态、待报条数、健康度、当前降级级别 */
    @NonNull
    public static TrackStatus getStatus();

    private AilifeTrack() {}
}
```

```java
public final class TrackConfig {
    final String   appKey;            // 必填，服务端鉴权
    final String   channelEndpoint;   // 数据平台进程 Provider authority 派生地址
    final int      flushIntervalMs;   // 合并窗口，默认 5000，范围 [1000, 10000]
    final int      batchCount;        // 批量阈值，默认 50，范围 [10, 200]
    final int      batchSizeBytes;    // 批量字节阈值，默认 262144（256KB）
    final int      maxQueueBytes;     // 磁盘队列容量，默认 20MB，范围 [5MB, 50MB]
    final int      eventTtlDays;      // 默认 3，范围 [1, 7]
    final int      dedupWindowMs;     // 默认 90000
    final boolean  enableAutoTrack;   // 自动采集总开关，默认 true
    final boolean  encryptPayload;    // AES-256-GCM 载荷加密，默认 true
    final Map<String, String> globalProps; // 全局属性
    // builder 校验：非法值回退默认并计数（E8），不抛异常
}
```

```java
public final class TrackStatus {
    State   state;        // IDLE / CONNECTING / CONNECTED / DEGRADED / SUSPENDED
    long    pendingCount; // 各进程待报事件数
    long    pendingBytes;
    float   health;       // 通道健康度 [0,1]
    int     degradeLevel; // 0 正常 / 1 缓存模式 / 2 恢复放量中
}
```

## 2. DataProvider 契约（跨进程 IPC）

- **authority**：`com.ailife.dataplatform.track`（平台进程内注册，`android:multiprocess=false`）
- **权限模型**：
  - 写：自定义权限 `com.ailife.permission.TRACK_WRITE`（signature 级），业务 App 声明并签名校验
  - 读：`queryTrack()` 仅平台自身与 signature 同证书调用方
- **协议**：`EventBatchProto`（protobuf v1）+ gzip；单批压缩后 ≤ 256KB

```java
public class AilifeTrackProvider extends ContentProvider {
    /** insert：返回四值结果码（写入 Uri 返回值 data 部分），同步、8s 超时语义 */
    // RESULT_SUCCEEDED   成功：平台已单事务落库，调用方可删除本地
    // RESULT_THROTTLED   限流：平台待报队列满/令牌不足，调用方退避后重试
    // RESULT_RETRY_LATER 暂不可用：平台升级中/存储异常，数据保留在调用方
    // RESULT_INVALID     数据/版本/鉴权不通过：调用方落损坏区，不重传并计数
    @Override
    public @Nullable Uri insert(@NonNull Uri uri, @Nullable ContentValues values);
    // uri: content://com.ailife.dataplatform.track/events?ver=1
    // values: { "blob" : byte[]  /* gzip(proto Batch) */,
    //           "sig" : String  /* HMAC(appKey + ts) */,
    //           "ts"  : long    /* 毫秒时间戳，防重放 ±5min */ }
    // 返回: content://com.ailife.dataplatform.track/events/<RESULT_CODE>

    /** query：平台状态（仅授权调用方），用于 SDK 探活与状态展示 */
    // query(events?status) → MatrixCursor{state, pending, health, degrade_level}
}
```

**调用方状态机（ChannelCore）**
```
CONNECTED ── DEAD_OBJECT/timeout(8s) ──→ BACKOFF(1s×2 上限30s ±20%抖动) ──探活成功──→ CONNECTED
CONNECTED ── RESULT_THROTTLED ──→ WAIT(按平台建议退避, 数据保留本地)
CONNECTED ── RESULT_RETRY_LATER ──→ WAIT(数据保留本地, 下次窗口重试)
CONNECTED ── RESULT_INVALID ──→ QUARANTINE(落损坏区, 计数, 不重传)
任意状态 ── 健康度<0.6 ──→ DEGRADED(仅缓存) ──恢复≥0.8持续10min──→ RAMPING(1/4→1/2→全量, 每级5min) ──→ CONNECTED
```

## 3. 服务端 API（track-gateway，Spring Boot 3）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/v1/track/batch` | 批量上报（平台进程 → 网关） |
| GET  | `/v1/track/config?appKey=&sdkVer=` | 配置下发（令牌桶/采样/TTL/降级开关） |
| POST | `/v1/track/meta/points` | 点位注册/更新（运营后台调用） |

**POST /v1/track/batch**
```
Headers: X-App-Key / X-Timestamp / X-Nonce / X-Signature(HMAC-SHA256) / Content-Encoding: gzip
Body:    protobuf TrackBatchProto（AES-256-GCM 加密时 Content-Encoding: gcm-aes）
Response 200: {"code":"OK","accepted":123,"duplicate":4}
Response 401: 鉴权失败 → 客户端停止上报并告警
Response 413: 单批超限 → 客户端拆批
Response 429: {"retryAfterMs":30000} → 按 Retry-After 退避
Response 5xx: 服务端异常 → 指数退避 30s/1m/5m/30m
幂等:    网关按 dedupKey 布隆过滤器初筛 + Kafka key=dedupKey 分区有序 + CH ReplacingMergeTree 终局去重
```

**GET /v1/track/config 响应示例**
```json
{ "configVer": 42, "rateLimitPerSec": 1000, "sampleRate": 1.0,
  "ttlDays": 3, "degradeSwitch": false, "configSign": "..." }
```
配置签名校验失败 → 沿用上次有效配置（E8b）；下发失败 → 5min 重试（E2）。

## 4. 数据契约（存储 schema）

```sql
-- ClickHouse 明细仓
CREATE TABLE track_event (
  dedup_key   String,
  app_id      LowCardinality(String),
  event_id    LowCardinality(String),
  session_id  String,
  client_ts   DateTime64(3),
  server_ts   DateTime64(3),
  props       String,             -- JSON
  common      String,             -- JSON 公共属性快照
  sdk_ver     LowCardinality(String),
  proto_ver   UInt16
) ENGINE = ReplacingMergeTree(server_ts)
ORDER BY (app_id, event_id, session_id, dedup_key);

-- MySQL 点位元数据
CREATE TABLE track_point_meta (
  point_id    VARCHAR(128) PRIMARY KEY,
  app_key     VARCHAR(64)  NOT NULL,
  name        VARCHAR(256),
  sample_rate DECIMAL(4,3) DEFAULT 1.000,
  status      TINYINT DEFAULT 1,   -- 1 收录 / 0 停用
  created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
```

## 5. 错误码汇总

| 层 | 码 | 含义 | 处置 |
| --- | --- | --- | --- |
| IPC | RESULT_SUCCEEDED | 平台已落库 | 删除本地 |
| IPC | RESULT_THROTTLED | 平台限流 | 退避重试 |
| IPC | RESULT_RETRY_LATER | 平台暂不可用 | 保留待窗口 |
| IPC | RESULT_INVALID | 数据/版本/鉴权不通过 | 落损坏区不重传 |
| HTTP | 401/403 | 鉴权失败 | 停止上报并告警 |
| HTTP | 413 | 批量超限 | 拆批重报 |
| HTTP | 429 | 限流 | 按 Retry-After 退避 |
| HTTP | 5xx | 服务端异常 | 指数退避 30s/1m/5m/30m |
| 内部 | E1–E11 | 见 spec.md 边界情况表 | 各自处置 + 计数 |
