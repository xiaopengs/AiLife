# 001-android-track-sdk · 技术方案（Implementation Plan)

> 流程：Constitution → Specify（本目录 spec.md）→ Clarify → Plan（本文件）→ Tasks（tasks.md）→ Analyze → Implement
> 架构图：diagrams/architecture.svg（交付视图）与 architecture.drawio（1:1 可编辑源文件，同一生成脚本产出，坐标一致）

## 0. 技术栈与约束
| 项 | 选型 | 说明 |
| --- | --- | --- |
| SDK 语言 | Java 8（minSdk 21） | 业务 API 与通道内核均为纯 Java，无 Kotlin 依赖 |
| 本地存储 | Room 2.6（业务进程侧 + 平台进程侧各一库） | 磁盘队列、平台缓冲、元数据 |
| IPC | ContentProvider（Binder） | `AilifeTrackProvider`，protobuf + gzip 批量传输 |
| 序列化 | protobuf-javalite 3.x | 协议 v1，`EventProto`；未知字段保留 |
| 加密 | TLS + AES-256-GCM 载荷加密 | 国密 SM4 可插拔（服务端配置开关） |
| 服务端 | Java 17 + Spring Boot 3 接入网关 | 鉴权/限流/解压校验，转发 Kafka |
| 流处理 | Kafka + Flink 1.18 | 实时清洗、去重、补维度后入仓 |
| 存储 | ClickHouse（明细）+ MySQL（点位/元数据）+ ES（检索） | 下游 Hive T+1 |
| CI | GitHub Actions | mvn 打包 AAR + 服务端 jar，单测 + lint 门禁 |

## 1. 架构分层（与架构图四层对应）
1. **接口层**：`AilifeTrack.init / track / trackList / flush / setUserProfile / optIn / optOut`，门面模式，同步入队、异步出队
2. **适配层**：自动采集（Activity/PV、点击/曝光采样、Crash/ANR 捕获、性能/网络）与业务手动埋点封装
3. **队列/存储层**：有界内存队列 + Room 磁盘队列（分片文件），公共属性补齐、去重、过期/容量淘汰、丢弃计数
4. **跨进程通道层**：`ChannelCore`（路由/重试退避/流控/压缩加密/去重/分片）→ `AilifeTrackProvider`（ContentProvider，权限校验、合并窗口、协议 v1）→ 平台本地库 → 上报客户端 → 数据中台

## 2. 模块划分
```
ailife-track-sdk/
├── track-api/          # 纯 Java 门面 + 注解（无 Android 依赖，供业务编译期依赖）
├── track-core/         # 接口层/适配层/队列存储/ChannelCore（Android Library）
├── track-provider/     # AilifeTrackProvider + 平台本地库 + 上报客户端（Android Library）
├── track-proto/        # protobuf 协议定义与生成代码（Java lite）
└── track-test/         # Robolectric + 混沌工程用例
server/
├── track-gateway/      # Spring Boot 接入网关（鉴权/限流/解压/校验 → Kafka）
├── track-flink/        # Flink 清洗作业
└── track-metadata/     # 点位注册/元数据服务（MySQL）
```

## 3. 关键设计决策（含备选与理由）
| # | 决策 | 备选 | 理由 |
| --- | --- | --- | --- |
| D1 | ContentProvider 而非独立进程 Socket/广播 | AIDL+Service；Broadcast | 与需求一致：系统数据服务模块，Binder 稳定、生命周期由系统管理、免启动 Service；AIDL 需自管服务存活 |
| D2 | 同步 insert + 四值结果码 | oneway 异步 + 背压 | 需要明确回执驱动「先落盘后传输」的删除确认；oneway 无法感知结果 |
| D3 | protobuf + gzip，单批 ≤ 256KB | JSON | 体积小、proto 未知字段前向兼容 |
| D4 | 「至少一次 + dedupKey 幂等去重」 | 精确一次（事务+锁） | 移动端无法做分布式事务；幂等成本最低且可审计 |
| D5 | 两侧 Room 先落盘后传输 | 仅内存队列 | 双进程任一死亡不丢数据（US4） |
| D6 | 令牌桶流控 + 健康度分级降级 | 无脑丢弃/无限重试 | 高峰保护服务端；健康度 < 0.6 仅缓存，恢复后渐进放量 |
| D7 | SDK 侧 AES-GCM + TLS，SM4 可插拔 | 仅 TLS | 防中间人读埋点载荷；国密合规可切换 |
| D8 | Room 磁盘队列分片 + TTL 3 天 + 20MB 容量 | 单文件 | 顺序读写、按片删除 O(1)；TTL/容量双闸门 |

## 4. 数据模型（核心表）
**业务进程侧 Room（通道本地兜底库）**
- `event`：id(UUID) · dedupKey · eventId · eventTime · params(JSON) · commonSnapshot · status(0待传/1已传/2损坏区) · retryCount · createdAt
- `send_meta`：分片游标、退避状态、通道健康度、上次配置版本

**平台进程侧 Room（平台本地库）**
- `inbox`：id · dedupKey · pkg · protoBlob · receivedAt · reported(0/1) · reportBatchId
- `report_batch`：batchId · eventCount · bytes · state(0构建/1在途/2确认/3失败重试) · nextRetryAt
- `meta`：配置版本、令牌桶参数、健康度、灰度开关

**服务端**
- ClickHouse `track_event`（ReplacingMergeTree，ORDER BY (app_id, event_id, session_id)，dedupKey 去重）
- MySQL `track_point_meta`（点位注册、版本、采样率配置）

## 5. 通道时序（Happy Path + 失败路径）
```
业务线程            SDK(业务进程)                    DataProvider(平台进程)         服务端
   │ track() ──┐     │                                   │                          │
   │<── 2ms ───┘  校验/补公共属性/入内存队列+磁盘队列      │                          │
   │                │ 批量(≤256KB) encode(protobuf+gzip) │                          │
   │                │ ── insert(uri, blob) ────────────→ │ 鉴权/签名/版本校验        │
   │                │        RESULT_SUCCEEDED ←───────  │ 单事务写 inbox            │
   │                │ 删本地分片 │                        │ 合并窗口(5s/50条/256KB)   │
   │                │ ↓(DEAD_OBJECT/超时8s)               │ ── 批量 HTTPS(签名) ───→ 网关
   │                │ 保留本地,退避1s×2→30s重连            │ ←─ 200 OK ────────────── │
   │                │                                   │ 确认后删 inbox 分片        │ ← Kafka → Flink → ClickHouse
```
失败路径：insert 异常/超时 → 本地保留 + 指数退避重连；返回 THROTTLED → 本地保留 + 退避；RETRY_LATER → 保留待窗口；INVALID → 落损坏区不重传；上报失败 → report_batch 置失败重试，nextRetryAt 按 30s/1m/5m/30m。

## 6. 风险与对策
| 风险 | 对策 |
| --- | --- |
| Provider 被业务进程高频调用打爆 | 令牌桶 + 单写线程串行化 + 8s 超时熔断 |
| 双进程 Clock 偏移 | 双时间戳 client_ts/server_ts，分析双轨 |
| 大量业务进程同时升级协议 | major 不兼容拒绝 + 兼容性监控 + minor 前向兼容（未知字段保留） |
| 磁盘满 / 低端机 | E3 降级内存缓冲 + 容量淘汰 + 丢弃计数可观测 |
| SDK 崩溃污染业务进程 | 拦截器隔离 + SDK 崩溃率 0 门禁（US4） |

## 7. 测试与验证
- 单测：Robolectric 覆盖接口层/队列/ChannelCore 状态机（重试/退避/熔断/降级）
- 协议：proto 兼容性测试（minor 新增字段、major 拒绝）
- 集成：双进程 instrumentation 测试（进程杀死/恢复、磁盘满、高并发 insert、乱序回执）
- 混沌：杀平台进程、断网、磁盘写满、服务端 5xx/429 演练矩阵
- 性能：track() P95 ≤ 2ms、init P95 ≤ 50ms、插入 P95 ≤ 10ms、端到端 ≤ 60s
- 门禁：`mvn test` + lint + proto 兼容检查全绿后方可合并

## 8. 发布
- SDK：Maven Central / 私服 AAR，语义化版本，协议 major 独立演进
- 服务端：灰度发布，配置中心下发令牌桶/采样/降级参数，兼容性监控大盘
- 回滚：SDK 版本可回滚；协议不兼容旧版走 RESULT_INVALID + 监控告警
