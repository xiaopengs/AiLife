# 001-android-track-sdk · 任务拆解（Implementation Tasks）

> 依据 spec.md（US1–US6）与 plan.md 拆解；每项含验证方式；标注 [P] 可并行
> 测试先行（宪法原则 III）：每组任务先写失败测试再实现

## Phase 1 — 协议与工程骨架
- [ ] T001 [P] 初始化 Gradle 多模块工程（track-api/core/proto/hub/test），配置 Java 8 工具链、minSdk 21 与 lint 门禁 · 验证：`./gradlew validate` 全模块通过
- [ ] T002 [P] 编写 `track-proto/event.proto`（v1：Event{id, dedup_key, event_id, event_time, params, common, sdk_ver, proto_ver}，Batch{events, compress}）与 javalite 生成 · 验证：生成代码编译通过 + proto 兼容性基线测试
- [ ] T003 [P] 定义四值结果码与异常体系（RESULT_SUCCEEDED/THROTTLED/RETRY_LATER/INVALID + TrackException 层级） · 验证：枚举映射单测

## Phase 2 — SDK 接口层与适配层（US1）
- [ ] T010 [P] `AilifeTrack` 门面：init/track/trackList/flush/setUserProfile/optIn/optOut/getStatus 签名与门禁（未 init 时事件入恢复队列） · 验证：Robolectric 单测「未 init 不丢、init 后补传」
- [ ] T011 [P] init 异步连接通道（50ms 返回），配置校验与默认值回退（E8） · 验证：主线程 init 计时单测 + 非法 config 单测
- [ ] T012 线程安全内存队列（有界 + 单消费者批量出队，E4b） · 验证：并发压测单测 1k 线程 × 1k 事件无丢失
- [ ] T013 [P] 公共属性补齐与权限降级采集 · 验证：权限矩阵单测（声明/未声明权限两类路径）
- [ ] T014 [P] 自动采集插件：Activity/PV、点击/曝光采样、Crash/ANR 捕获、性能/网络 · 验证：各插件触发事件断言单测

## Phase 3 — 业务进程队列/存储与 ChannelCore（US6 前置）
- [ ] T020 [P] 业务进程 Room 磁盘兜底队列：event/send_meta 表 + 分片读写 + 「先落盘后传输」删除确认 · 验证：单测「写→读→确认删」状态流转
- [ ] T021 TTL 3 天 + 容量 20MB 淨汰器 + 丢弃计数（E10） · 验证：构造超限数据单测淨汰顺序与计数
- [ ] T022 [P] 90s 去重窗口（dedupKey，可配） · 验证：重复事件仅计一次单测
- [ ] T023 [P] E1/E3 处置：非法值剔除、磁盘满转降级内存缓冲（512KB 有界）与回灌 · 验证：模拟磁盘满单测
- [ ] T024 ChannelCore 状态机：指数退避重连（1s×2→30s，±20% 抖动）、DEAD_OBJECT/8s 超时处置 · 验证：伪 Provider 单测退避序列断言

## Phase 4 — 跨进程通道（US2）
- [ ] T030 [P] `AilifeTrackProvider`：insert 四值结果码、protobuf+gzip 解码、签名+permission 校验 · 验证：instrumentation 四码全路径
- [ ] T031 协议版本协商（major 拒绝 INVALID + 兼容性计数、minor 兼容） · 验证：双版本互操作测试（E7）
- [ ] T032 [P] 平台进程 Room inbox 表 + 单事务写入（插入处理 P95 ≤ 10ms，E4） · 验证：插入性能基准测试
- [ ] T033 [P] 端侧处理引擎：校验→去重→合并→采样→补齐→会话生成，隔离区机制 · 验证：流水线单测（各阶段异常互不传染）

## Phase 5 — 端侧统一存储与本地查询（US3）
- [ ] T040 [P] 按天分区存储：event_detail/session/user_profile/metric 表 · 验证：分区路由与读写分离单测
- [ ] T041 TTL/配额治理：TTL 3 天、配额 20MB、最新优先淨汰最旧分区 + 计数 · 验证：超限淨汰单测（US3）
- [ ] T042 [P] `queryTrack()` 只读查询 + 授权校验（看板/智能体契约） · 验证：授权/非授权路径单测，查询不阻塞写入

## Phase 6 — 上报调度与降级（US4/US5）
- [ ] T050 [P] 上报调度器：count/size/time 三维触发（5s/50条/256KB） · 验证：时间与阈值双触发单测
- [ ] T051 [P] 云侧上报客户端：批量 HTTPS（AES-GCM + HMAC 签名）、30s/1m/5m/30m 退避、401/403/429/5xx 分类处置 · 验证：MockWebServer 状态码矩阵单测
- [ ] T052 网络策略：Wi-Fi 立即报、蜂窝阈值批量、无网络滞留、恢复续传 · 验证：网络状态切换单测
- [ ] T053 [P] 令牌桶流控（1000 ev/s 可配 100–2000）+ 满时入待报队列 · 验证：限流边界单测
- [ ] T054 健康度计算与分级降级（< 0.6 仅缓存；≥ 0.8 持续 10min 渐进放量 1/4→1/2→1） · 验证：状态迁移表驱动单测
- [ ] T055 [P] 端侧自监控指标：丢弃率/成功率/延迟/协议拒绝率/存储水位，随批量上报与本地查询 · 验证：指标口径单测（US5）
- [ ] T056 [P] 云端配置拉取与校验（签名/黑白名单，E2/E8b） · 验证：非法配置拒绝单测

## Phase 7 — 混沌与验收（US6）
- [ ] T060 混沌矩阵：杀数据平台进程/杀业务进程/断网/磁盘满/云端 5xx+429 组合演练 · 验证：全场景「零丢失」断言（丢弃仅允许 TTL/配额/超大三类，且计数吻合）
- [ ] T061 性能门禁：track P95 ≤ 2ms、init P95 ≤ 50ms、插入处理 P95 ≤ 10ms、入库 ≤ 10s、Wi-Fi 上报 ≤ 30s · 验证：基准报告入 CI 工件
- [ ] T062 optOut/optIn 全链路：停止采集、清队列、状态持久化、防抖（E12）、恢复采集 · 验证：跨进程端到端用例
- [ ] T063 spec 覆盖核对：US1–US6 与 E1–E12 逐条映射到测试用例（/speckit.analyze 输入） · 验证：覆盖矩阵无缺口

## 依赖与里程碑
- Phase 1 → 2/3 → 4 → 5/6 → 7 串行；各 Phase 内 [P] 任务可并行
- M1（Phase 1–3 完）：单进程闭环可用；M2（Phase 4）：跨进程通道贯通；M3（Phase 5）：端侧资产与本地查询可用；M4（Phase 6）：实时上报与降级可用；M5（Phase 7）：混沌验收通过
