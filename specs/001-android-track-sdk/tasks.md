# 001-android-track-sdk · 任务拆解（Implementation Tasks）

> 依据 spec.md（US1–US4）与 plan.md 拆解；每项含验证方式；标注 [P] 可并行
> 测试先行（Constitution 原则 II）：每组任务先写失败测试再实现

## Phase 1 — 协议与工程骨架
- [ ] T001 [P] 初始化 Maven/Gradle 多模块工程（track-api/core/provider/proto/test + server 三模块），配置 Java 8/17 工具链与 lint 门禁 · 验证：`mvn validate` 全模块通过
- [ ] T002 [P] 编写 `track-proto/event.proto`（v1：Event{id, dedup_key, event_id, event_time, params, common, sdk_ver, proto_ver}，Batch{events, compress}）与 javalite 生成 · 验证：生成代码编译通过 + proto 兼容性基线测试
- [ ] T003 [P] 定义结果码与异常体系（RESULT_SUCCEEDED/THROTTLED/RETRY_LATER/INVALID + TrackException 层级）· 验证：枚举映射单测

## Phase 2 — SDK 接口层（US1）
- [ ] T010 [P] `AilifeTrack` 门面：init/track/trackList/flush/setUserProfile/optIn/optOut 签名与门禁（未 init 时事件入恢复队列）· 验证：Robolectric 单测「未 init 不丢、init 后补传」
- [ ] T011 [P] init 异步连接通道（50ms 返回），配置校验与默认值回退（E8）· 验证：主线程 init 计时单测 + 非法 config 单测
- [ ] T012 线程安全内存队列（有界 + 单消费者批量出队，E4b）· 验证：并发压测单测 1k 线程 × 1k 事件无丢失
- [ ] T013 公共属性补齐与权限降级采集 · 验证：权限矩阵单测（声明/未声明权限两类路径）

## Phase 3 — 队列/存储层（US4 前置）
- [ ] T020 [P] 业务进程 Room 磁盘队列：event/send_meta 表 + 分片读写 + 「先落盘后传输」删除确认 · 验证：单测「写→读→确认删」状态流转
- [ ] T021 TTL 3 天 + 容量 20MB 淘汰器 + 丢弃计数（E10）· 验证：构造超限数据单测淘汰顺序与计数
- [ ] T022 [P] 90s 去重窗口（dedupKey，可配）· 验证：重复事件仅计一次单测
- [ ] T023 [P] E1/E3 处置：非法值剔除、磁盘满转降级内存缓冲（512KB 有界）与回灌 · 验证：模拟磁盘满单测

## Phase 4 — 跨进程通道（US2）
- [ ] T030 [P] `AilifeTrackProvider`：insert 四值结果码、protobuf+gzip 解码、签名+permission 校验 · 验证：instrumentation 测试四码全路径
- [ ] T031 协议版本协商（major 拒绝 INVALID + 监控埋点、minor 兼容）· 验证：双版本互操作测试
- [ ] T032 ChannelCore 状态机：指数退避重连（1s×2→30s，±20% 抖动）、DEAD_OBJECT/8s 超时处置 · 验证：杀进程演练测试（退避序列断言）
- [ ] T033 [P] 平台进程 Room inbox/report_batch/meta 表 + 单事务写入（插入 P95 ≤ 10ms）· 验证：插入性能基准测试
- [ ] T034 [P] 合并窗口（5s/50 条/256KB 三维触发）· 验证：时间与阈值双触发单测

## Phase 5 — 上报与服务端（US3）
- [ ] T040 [P] 上报客户端：批量 HTTPS（AES-GCM + 签名）、30s/1m/5m/30m 退避、401/403/429/5xx 分类处置 · 验证：MockWebServer 状态码矩阵单测
- [ ] T041 [P] 令牌桶流控（1000 ev/s 可配 100–2000）+ 满时入待报队列 · 验证：限流边界单测
- [ ] T042 健康度计算与分级降级（< 0.6 仅缓存；≥ 0.8 持续 10min 渐进放量 1/4→1/2→1）· 验证：状态迁移表驱动单测
- [ ] T043 track-gateway：Spring Boot 鉴权/限流/解压校验 → Kafka producer · 验证：集成测试（签名错误 401、超限 429）
- [ ] T044 [P] Flink 清洗作业：dedupKey 去重、公共属性补维度、非法数据侧输出 · 验证：本地 Flink MiniCluster 用例
- [ ] T045 [P] ClickHouse 表（ReplacingMergeTree + dedupKey）与 MySQL 点位注册表 DDL · 验证：schema 迁移脚本可重复执行
- [ ] T046 [P] 监控与告警：端到端 P95 60s、成功率、丢弃率、协议拒绝率大盘与告警规则 · 验证：告警规则配置评审 + 指标自测

## Phase 6 — 混沌与验收（US4）
- [ ] T050 混沌矩阵：杀平台进程/杀业务进程/断网/磁盘满/服务端 5xx+429 组合演练 · 验证：全场景「零丢失」断言（丢弃仅允许 TTL/容量/超大三类，且计数吻合）
- [ ] T051 性能门禁：track P95 ≤ 2ms、init P95 ≤ 50ms、insert P95 ≤ 10ms、端到端 ≤ 60s · 验证：基准报告入 CI 工件
- [ ] T052 optOut/optIn 全链路：停止采集、清队列、状态持久化、恢复采集 · 验证：跨进程端到端用例
- [ ] T053 spec 覆盖核对：US1–US4 与 E1–E11 逐条映射到测试用例（/speckit.analyze 输入）· 验证：覆盖矩阵无缺口

## 依赖与里程碑
- Phase 1 → 2/3 → 4 → 5 → 6 串行；各 Phase 内 [P] 任务可并行
- M1（Phase 1–3 完）：单进程闭环可用；M2（Phase 4）：跨进程通道贯通；M3（Phase 5）：端到端实时上报；M4（Phase 6）：混沌验收通过
