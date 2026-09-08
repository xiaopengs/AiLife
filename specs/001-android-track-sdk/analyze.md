# 001-android-track-sdk · 分析与一致性核查（Analyze）

> 核查对象：constitution.md · spec.md · plan.md · tasks.md · contracts/api.md · diagrams/*
> 结论：**通过（无阻断项）**，3 条备份见文末

## 1. 概念一致性（端侧数据中台）
- 宪法原则 I（端侧优先）：spec 「概念与范围界定」、plan 枞架分层、架构图「数据平台进程 · 端侧数据中台」与「云侧仅接口约定」三处口径一致 ✅
- 数据中台六大服务（接入/处理/存储/上报/监控/查询）在图、spec US3–US5、plan 模块划分、tasks Phase 4–6 四处对应 ✅
- 云侧收网关/配置中心/数据服务在图中为虚线容器，与 spec Non-Goals、plan §0、contracts §4 一致 ✅

## 2. 需求覆盖矩阵（spec → plan/tasks/contracts）

| 规格条目 | plan 对应 | tasks 对应 | contracts 对应 | 状态 |
| --- | --- | --- | --- | --- |
| US1 init/track/trackList 门禁与时延 | §1 §2 | T010–T014 | §1 API | ✅ |
| US1 未 init 不丢数据 | §3 D5 | T010 | §1（恢复队列语义） | ✅ |
| US2 四值结果码 | §3 D2 | T003 T030 | §2 §6 | ✅ |
| US2 退避重连 1s→30s ±20% | §5 时序 | T024 | §2 状态机 | ✅ |
| US2 协议 major/minor 兼容 | §3 D3 | T002 T031 | §2 | ✅ |
| US2 签名+permission 校验 | §1 IPC 行 | T030 | §2 权限模型 | ✅ |
| US3 处理流水线（校验→去重→合并→采样→补齐→会话） | §1 中台 | T033 | §2（insert 后处理链） | ✅ |
| US3 端侧统一存储按天分区 + TTL/配额 | §3 D7 §4 | T040 T041 | §5 schema | ✅ |
| US3 queryTrack 只读授权 | §1 中台 §3 D9 | T042 | §3 | ✅ |
| US4 三维触发（5s/50条/256KB） | §5 时序 | T050 | §1 TrackConfig | ✅ |
| US4 网络策略（Wi-Fi/蜂窝/断网） | §3 D8 | T052 | §4 | ✅ |
| US4 令牌桶 1000 ev/s 可配 | §3 D6 | T053 | §4 config | ✅ |
| US4 健康度降级/渐进放量 | §3 D6 | T054 | §2 状态机 | ✅ |
| US4 optOut/optIn（E12 防抖） | §1 | T062 | §1 API | ✅ |
| US5 大事件拆分/丢弃+计数 | §6 | T021 T060 | §6 | ✅ |
| US5 90s 去重窗口 | §4 | T022 | §4 幂等 | ✅ |
| US5 IPC 异常分类处置 | §5 | T024 | §2 状态机 | ✅ |
| US5 双进程先落盘后传输 | §3 D5 | T020 T032 | §2 §5 | ✅ |
| US5 SDK 崩溃率 0 | §6 | T060 T061 | 宪法 III/V | ✅ |
| US5 自监控指标 | §1 中台 | T055 | §3 queryMetrics | ✅ |
| US6 零丢失断言 | §6 | T060 | §6 | ✅ |
| E1–E12 边界情况 | §6 风险表 | T013/T021/T022/T023/T056/T062 | §6 错误码表 | ✅（E5 双时间戳落 contracts §5） |

## 3. 宪法一致性
- 原则 II（规格先行）：spec/plan/tasks/contracts 四件套齐备，无未定义行为落地 ✅
- 原则 III（测试先行）：全部 30 个任务均含验证方式；T061 性能门禁与宪法 V 数值一致 ✅
- 原则 IV（数据完整性）：丢弃仅三类 + 计数，T060 验收断訳与之对齐 ✅
- 原则 V（业务进程不受影响）：2ms/50ms/10ms 指标在 spec/plan/tasks/contracts 四处一致 ✅
- 原则 VI（Java 技术栈）：全端侧 Java 8、无 Kotlin、协议兼容策略在 plan §0/§3 与宪法 VI 一致 ✅
- 原则 VII（可观测与可降级）：指标清单 T055 与宪法 VII 对齐 ✅

## 4. 图纸一致性
- 架构图四层（接口/适配/队列存储/跨进程通道）与 plan §1 分层一一对应 ✅
- 图中端侧数据中台六服务（DataProvider/处理引擎/统一存储/上报调度·上报客户端/自监控/本地查询）与 spec US3–US5、plan §1、tasks Phase 4–6 一致 ✅
- architecture.svg 与 architecture.drawio 由同一脚本（gen_arch_diagram.py）生成，节点坐标 1:1 ✅

## 5. 备份（非阻断）
1. **update()/delete() 未定义**：DataProvider 仅定义 insert/query——删除确认由结果码驱动，属有意精简；实现时不得额外暴霰写接口
2. **flush() 与合并窗口语义**：flush() 仅提示调度，不缩短中台合并窗口；已在 contracts §1 注明
3. **云侧为契约方**：云端幂等去重/配置下发仅为接口约定，不参与端侧 SLO 与零丢失断言（spec 功能边界已声明）
