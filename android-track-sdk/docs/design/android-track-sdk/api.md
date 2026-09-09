# 001-android-track-sdk · 契约与接口设计

> 本文件是 SDK 接口、DataProvider 契约、端侧查询接口与云侧接口约定的权威定义；实现须与本文件一致

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

    /** 立即触发一次通道批量出队（不影响中台合并窗口语义，仅提示调度） */
    public static void flush();

    /** 用户属性：差量合并上报 */
    public static void setUserProfile(@NonNull Map<String, Object> profile);

    /** 退出采集：清空待报数据、停止上报、状态持久化（立即生效） */
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
    final String  appKey;           // 必填；为空时仅本地缓存，绝不使用默认密钥发送
    final boolean sendEnabled;      // appKey 有效时为 true
    final String  providerAuthority;// 数据平台进程 Provider authority，默认 com.ailife.dataplatform.track
    final int     flushIntervalMs;  // 合并窗口，默认 5000，范围 [1000, 10000]
    final int     batchCount;       // 批量阈值，默认 50，范围 [10, 200]
    final int     batchSizeBytes;   // 批量字节阈值，默认 262144（256KB）
    final int     maxQueueBytes;    // 磁盘队列容量，默认 20MB，范围 [5MB, 50MB]
    final int     eventTtlDays;     // 默认 3，范围 [1, 7]
    final int     dedupWindowMs;    // 默认 90000
    final boolean enableAutoTrack;  // 自动采集总开关，默认 true
    final boolean encryptPayload;   // AES-256-GCM 载荷加密，默认 true
    final Map<String, String> globalProps; // 全局属性
    // builder 校验：非法值回退默认并计数（E8），不抛异常
}
```

```java
public final class TrackStatus {
    State   state;        // IDLE / CONNECTING / CONNECTED / DEGRADED / SUSPENDED
    long    pendingCount; // 本进程待报事件数
    long    pendingBytes;
    float   health;       // 通道健康度 [0,1]
    int     degradeLevel; // 0 正常 / 1 仅缓存 / 2 恢复放量中
}
```

## 2. DataProvider 契约（跨进程 IPC，端侧数据中台接入点）

- **authority**：`com.ailife.dataplatform.track`（数据平台进程内注册，`android:multiprocess=false`）
- **权限模型**：
  - 写：自定义权限 `com.ailife.permission.TRACK_WRITE`（signature 级），业务 App 声明并签名校验
  - 读：`queryTrack()` 仅数据平台进程本身与 signature 同证书的授权消费方
- **协议**：`EventBatchProto`（protobuf v1）+ gzip；单批压缩后 ≤ 256KB

```java
public class AilifeTrackProvider extends ContentProvider {
    /** insert：返回四值结果码（写入 Uri 返回值 data 部分），同步、8s 超时语义 */
    // RESULT_SUCCEEDED   成功：中台已单事务落库，调用方可删除本地
    // RESULT_THROTTLED   限流：中台待报队列满/令牌不足，调用方退避后重试
    // RESULT_RETRY_LATER 暂不可用：中台升级中/存储异常，数据保留在调用方
    // RESULT_INVALID     数据/版本/鉴权不通过：调用方落损坏区，不重传并计数
    @Override
    public @Nullable Uri insert(@NonNull Uri uri, @Nullable ContentValues values);
    // uri: content://com.ailife.dataplatform.track/events?ver=1
    // values: { "blob" : byte[]  /* gzip(proto 或 AES-GCM(proto)) */,
    //           "sig"  : String  /* HMAC(appKey + ts) */,
    //           "ts"   : long    /* 毫秒时间戳，防重放 ±5min */,
    //           "app_key" : String /* 已认证来源应用 */,
    //           "encrypted" : boolean }
    // 返回: content://com.ailife.dataplatform.track/events/<RESULT_CODE>

    /** query：中台状态（仅授权调用方），用于 SDK 探活与状态展示 */
    // query(status) → MatrixCursor{state, pending, health, degrade_level}
}
```

**调用方状态机（ChannelCore）**
```
CONNECTED ── DEAD_OBJECT/timeout(8s) ──→ BACKOFF(1s×2 上限30s ±20%抖动) ──探活成功──→ CONNECTED
CONNECTED ── RESULT_THROTTLED ──→ WAIT(按中台建议退避, 数据保留本地)
CONNECTED ── RESULT_RETRY_LATER ──→ WAIT(数据保留本地, 下次窗口重试)
CONNECTED ── RESULT_INVALID ──→ 当前批次 QUARANTINE(落损坏区、计数、不重传；通道保持可用)
任意状态 ── 健康度<0.6 ──→ DEGRADED(仅缓存) ──恢复≥0.8持续10min──→ RAMPING(1/4→1/2→全量, 每级5min) ──→ CONNECTED
```

## 3. 端侧本地查询接口（queryTrack，只读）

```java
// 仅限授权消费方（端侧看板 / 智能体 / 数据平台进程自身）；非授权调用返回 SecurityException
public interface TrackQuery {
    /** 分页查询事件明细（按天分区） */
    Cursor queryEvents(long fromTs, long toTs, String eventIdLike, int limit, int offset);
    /** 会话明细 */
    Cursor querySessions(long fromTs, long toTs, int limit, int offset);
    /** 自监控指标快照（丢弃率/成功率/延迟/协议拒绝率/存储水位/健康度） */
    Bundle queryMetrics();
}
```

## 4. 云侧接口约定（非本期实现范围）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/v1/track/batch` | 批量上报（中台上报客户端 → 云端接收网关） |
| GET | `/v1/track/config?appKey=&sdkVer=` | 配置下发（令牌桶/采样/TTL/降级开关） |

**POST /v1/track/batch**
```
Headers: X-App-Key / X-Timestamp / X-Nonce / X-Signature(HMAC-SHA256) / Content-Encoding: gzip
Body:    protobuf TrackBatchProto（AES-256-GCM 加密时 Content-Encoding: gcm-aes）
Response 200: {"code":"OK","accepted":123,"duplicate":4}
Response 401: 鉴权失败 → 中台停止上报并告警（端侧记录）
Response 413: 单批超限 → 中台拆批
Response 429: {"retryAfterMs":30000} → 按 Retry-After 退避
Response 5xx: 云端异常 → 指数退避 30s/1m/5m/30m
幂等:    Hub 按已验签的 appKey 分组批次，并以该 appKey 写入 X-App-Key 与计算 HMAC；云端按 appKey + dedupKey 幂等去重
```

**GET /v1/track/config 响应示例**
```json
{ "configVer": 42, "rateLimitPerSec": 1000, "sampleRate": 1.0,
  "ttlDays": 3, "degradeSwitch": false, "configSign": "..." }
```
配置签名校验失败 → 沿用上次有效配置（E8b）；拉取失败 → 5min 重试（E2）。

## 5. 数据契约（端侧统一存储 schema）

```sql
-- 数据平台进程 Room（按天分区示意，实际以 Room Entity + 分区表后缀实现）
CREATE TABLE event_detail_(   -- 每日一张：event_detail_20260908
  dedup_key    TEXT PRIMARY KEY,
  event_id     TEXT NOT NULL,
  session_id   TEXT NOT NULL,
  client_ts    INTEGER NOT NULL,   -- SDK 时间戳
  received_ts  INTEGER NOT NULL,   -- 中台接收时间戳（E5）
  props        TEXT NOT NULL,      -- JSON
  common       TEXT NOT NULL,      -- JSON 公共属性快照
  pkg          TEXT NOT NULL,
  sdk_ver      TEXT NOT NULL,
  proto_ver    INTEGER NOT NULL
);
CREATE INDEX idx_event_detail_ts ON event_detail_(client_ts);

-- 上报光标（先落盘后传输：确认后才可清除）
CREATE TABLE report_batch (
  batch_id     TEXT PRIMARY KEY,
  event_count  INTEGER NOT NULL,
  bytes        INTEGER NOT NULL,
  state        INTEGER NOT NULL,  -- 0构建/1在途/2确认/3失败重试
  next_retry_at INTEGER,
  created_at   INTEGER NOT NULL
);
```

## 6. 错误码汇总

| 层 | 码 | 含义 | 处置 |
| --- | --- | --- | --- |
| IPC | RESULT_SUCCEEDED | 中台已落库 | 删除本地 |
| IPC | RESULT_THROTTLED | 中台限流 | 退避重试 |
| IPC | RESULT_RETRY_LATER | 中台暂不可用 | 保留待窗口 |
| IPC | RESULT_INVALID | 数据/版本/鉴权不通过 | 落损坏区不重传 |
| HTTP | 401/403 | 鉴权失败 | 停止上报并告警（端侧记录） |
| HTTP | 413 | 批量超限 | 拆批不重报 |
| HTTP | 429 | 限流 | 按 Retry-After 退避 |
| HTTP | 5xx | 云端异常 | 指数退避 30s/1m/5m/30m |
| 内部 | E1–E12 | 见 spec.md 边界情况表 | 各自处置 + 计数 |
