# 001-android-track-sdk · 技术方案（Implementation Plan）

> 流程：Constitution → Specify（spec.md）→ Clarify → Plan（本文件）→ Tasks（tasks.md）→ Analyze → Implement
> 枞架图：diagrams/architecture.svg（交付视图）与 architecture.drawio（1:1 可编辑源文件，同一生成脚本产出，坐标一致）

## 0. 技术栈与约束（全端侧）
| 项 | 选型 | 说明 |
| --- | --- | --- |
| 语言 | Java 8（minSdk 21） | SDK 与端侧数据中台均为纯 Java，无 Kotlin 依赖 |
| 本地存储 | Room 2.6 | 业务进程侧通道兜底库 + 中台统一存储（按天分区） |
| IPC | ContentProvider（Binder） | `AilifeTrackProvider`，protobuf + gzip 批量传输 |
| 序列化 | protobuf-javalite 3.x | 协议 v1，未知字段保留；跨版本兼容 |
| 轮询/调度 | WorkManager + HandlerThread | 上报调度、TTL/配额治理、配置拉取 |
| 加密 | TLS + AES-256-GCM 载荷加密 | 国密 SM4 可插拔（云端配置开关） |
| 云侧 | 仅接口约定 | 接收网关/配置中心/数据服务不在本期实现范围 |
| CI | GitHub Actions | 单测 + lint + proto 兼容检查门禁 |

## 1. 枞架分层（与架构图对应）
**业务进程（宿主 App，可多进程接入）**
1. **接口层**：`AilifeTrack.init / track / trackList / flush / setUserProfile / optIn / optOut / getStatus`，门面模式，同步入队、异步出队
2. **适配层**：自动采集（Activity/PV、点击/曝光采样、Crash/ANR 捕获、性能/网络）与业务手动埋点封装
3. **队列/存储层**：有界内存队列 + Room 磁盘兜底队列（分片），公共属性补齐、去重、过期/容量淨汰、丢弃计数
4. **跨进程通道层**：`ChannelCore` 通道内核（路由/重试退避/流控/压缩加密）→ Binder insert → 四值结果码

**数据平台进程（端侧数据中台 · 系统数据服务 · 常驻）**
- **DataProvider 系统数据服务模块**：ContentProvider 接入、权限/签名校验、协议 v1、四值结果码
- **端侧处理引擎**：校验 → 去重 → 合并 → 采样 → 公共属性补齐 → 会话生成；隔离区机制
- **端侧统一存储**：Room 按天分区（明细/会话/用户属性/指标），TTL/配额/容量淨汰
- **上报调度器 + 云侧上报客户端**：实时批量、网络策略（Wi-Fi/蜂窝）、退避重试、断点续传
- **端侧自监控 · 降级控制**：指标统计、健康度、降级/放量状态机
- **本地查询 queryTrack()**：只读，授权消费方（端侧看板/智能体）

**云侧（仅接口约定，非本期范围）**：接收网关（批量鉴权/幂等去重/存储）、配置中心（限流/采样/降级参数下发）、云端数据服务（看板/检索/分析）

## 2. 模块划分
```
ailife-track-sdk/
├── track-api/          # 纯 Java 门面 + TrackConfig/TrackStatus（无 Android 依赖）
├── track-core/         # 接口层/适配层/队列存储/ChannelCore（Android Library）
├── track-proto/        # protobuf 协议定义与生成代码（Java lite）
├── track-hub/         # 端侧数据中台：AilifeTrackProvider + 处理引擎 + 统一存储
│                       #   + 上报调度/上报客户端 + 自监控降级 + queryTrack
└── track-test/         # Robolectric + instrumentation + 混沌工程用例
```

## 3. 关键设计决策（含备选与理由）
| # | 决策 | 备选 | 理由 |
| --- | --- | --- | --- |
| D1 | 数据中台以 ContentProvider 形式提供系统数据服务 | AIDL+Service；Broadcast | 旨在 Binder 稳定、生命周期由系统管理、免自管 Service 存活；多业务 App 接入标准化 |
| D2 | 同步 insert + 四值结果码 | oneway 异步 | 需要明确回执驱动「先落盘后传输」的删除确认；oneway 无法感知结果 |
| D3 | protobuf + gzip，单批 ≤ 256KB | JSON | 体积小、proto 未知字段前向兼容 |
| D4 | 「至少一次 + dedupKey 幂等去重」 | 精确一次（事务+锁） | 移动端无法做分布式事务；幂等成本最低且可审计 |
| D5 | 双进程均 Room 先落盘后传输 | 仅内存队列 | 双进程任一死亡不丢数据（US6） |
| D6 | 令牌桶流控 + 健康度分级降级 | 无脑丢弃/无限重试 | 保护端侧资源与云端；降级有界可恢复 |
| D7 | 端侧统一存储按天分区 + TTL/配额双闸门 | 单表 | 按分区删除 O(1)，治理策略清晰 |
| D8 | 上报网络策略（Wi-Fi 实时、蜂窝批量） | 不分网络 | 体现端侧资源意识，兼顾实时性与耗电耗流量 |
| D9 | queryTrack 只读 + 授权体系 | 直接开放读 | 端侧数据资产安全边界清晰 |

## 4. 数据模型（核心表）
**业务进程侧 Room（通道本地兜底库）**
- `event`：id(UUID) · dedupKey · eventId · eventTime · params(JSON) · commonSnapshot · status(0待传/1已传/2损坏区) · retryCount · createdAt
- `send_meta`：分片游标、退避状态、通道健康度、配置版本

**数据平台进程侧 Room（端侧统一存储，按天分区）**
- `event_detail` / `session` / `user_profile` / `metric`：明细、会话、用户属性、指标
- `inbox`：id · dedupKey · pkg · protoBlob · receivedAt · processed(0/1)
- `report_batch`：batchId · eventCount · bytes · state(0构建/1在途/2确认/3失败重试) · nextRetryAt
- `meta`：配置版本、令牌桶参数、健康度、灰度开关、安全事件计数

## 5. 通道时序（Happy Path + 失败路径）
```
业务线程        SDK(业务进程)              DataProvider(数据平台进程/端侧数据中台)
   │ track() ──┐  │                              │
   │<── 2ms ───┘ 校验/补公共属性/入内存队列+磁盘兜底   │
   │               │ 批量(≤256KB) encode(proto+gzip) │              │
   │               │ ── insert(uri, blob) ────────── → │ 鉴权/签名/版本校验 │
   │               │        RESULT_SUCCEEDED ←──────  │ 单事务写 inbox   │
   │               │ 删本地分片 │                      │ ↓ 端侧处理引擎（校验/去重/合并/采样）
   │               │ ↓(DEAD_OBJECT/超时8s)            │ ↓ 端侧统一存储（按天分区）
   │               │ 保留本地,退避1s×2→30s重连        │ ↓ 上报调度（5s/50条/256KB 触发）
   │               │                                │ ↓ 云侧上报客户端 → 云端网关（接口约定）
```
失败路径：insert 异常/超时 → 本地保留 + 指数退避重连；THROTTLED → 保留 + 退避；RETRY_LATER → 保留待窗口；INVALID → 落损坏区不重传；上报失败 → report_batch 置失败重试，nextRetryAt 按 30s/1m/5m/30m。

## 6. 风险与对策
| 风险 | 对策 |
| --- | --- |
| Provider 被高频调用打爆端侧中台 | 令牌桶 + 单写线程串行化 + 8s 超时熄断 |
| 双进程时钟偏移 | 双时间戳 client_ts/received_ts，双轨计算（E5） |
| 大量业务进程协议升级不一致 | major 不兼容拒绝 + 兼容性监控 + minor 前向兼容（E7） |
| 磁盘满 / 低端机 | E3 降级内存缓冲 + 配额淨汰 + 丢弃计数可观测 |
| SDK/中台崩溃污染业务进程 | 拦截器隔离 + SDK 崩溃率 0 门禁（宪法 IV/V） |

## 7. 测试与验证
- 单测：Robolectric 覆盖接口层/队列/通道状态机/处理流水线（重试/退避/熄断/降级）
- 协议：proto 兼容性测试（minor 新增字段、major 拒绝）
- 集成：双进程 instrumentation（进程杀死/恢复、磁盘满、高并发 insert、乱序回执）
- 混沌：杀数据平台进程、断网、磁盘写满、云端 5xx/429 演练矩阵
- 性能：track() P95 ≤ 2ms、init P95 ≤ 50ms、插入处理 P95 ≤ 10ms、入库 ≤ 10s、Wi-Fi 上报 ≤ 30s
- 门禁：单测 + lint + proto 兼容检查全绿后方可合并

## 8. 发布
- SDK/中台：私服 AAR，语义化版本，协议 major 独立演进
- 配置：云端配置中心下发令牌桶/采样/降级参数（接口约定），端侧校验签名与黑白名单
- 回滚：版本可回滚；协议不兼容旧版走 RESULT_INVALID + 监控告警
