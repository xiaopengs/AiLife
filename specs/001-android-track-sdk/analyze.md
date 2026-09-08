# 001-android-track-sdk · 分析与一致性核查（Analyze）

> 核查对象：constitution.md · spec.md · plan.md · tasks.md · contracts/api.md · diagrams/*
> 结论：**通过（无阻断项）**，3 条备注见文末

## 1. 需求覆盖矩阵（spec → plan/tasks/contracts）

| 规格条目 | plan 对应 | tasks 对应 | contracts 对应 | 状态 |
| --- | --- | --- | --- | --- |
| US1 init/track/trackList 门禁与时延 | §1 §2 | T010–T013 | §1 API | ✅ |
| US1 未 init 不丢数据 | §3 D5 | T010 | §1（恢复队列语义） | ✅ |
| US2 四值结果码 | §3 D2 | T003 T030 | §2 §5 | ✅ |
| US2 退避重连 1s→30s ±20% | §5 时序 | T032 | §2 状态机 | ✅ |
| US2 协议 major/minor 兼容 | §3 D3 | T002 T031 | §2 §3 | ✅ |
| US2 签名+permission 校验 | §2 IPC 行 | T030 | §2 权限模型 | ✅ |
| US3 三维触发（5s/50条/256KB） | §5 时序 | T034 T040 | §1 TrackConfig | ✅ |
| US3 令牌桶 1000 ev/s 可配 | §3 D6 | T041 | §3 config | ✅ |
| US3 退避 30s/1m/5m/30m | §5 失败路径 | T040 | §3 §5 | ✅ |
| US3 健康度降级/渐进放量 | §3 D6 | T042 | §2 状态机 | ✅ |
| US3 TTL 3 天 / 容量 20MB | §3 D8 | T021 | §1 TrackConfig | ✅ |
| US3 optOut/optIn | §1 | T052 | §1 API | ✅ |
| US4 大事件拆分/丢弃+计数 | §6 | T021 T050 | §5 | ✅ |
| US4 90s 去重窗口 | §4 | T022 | §3 幂等 | ✅ |
| US4 IPC 异常分类处置 | §5 | T032 | §2 状态机 | ✅ |
| US4 双进程先落盘后传输 | §3 D5 | T020 T033 | §2 | ✅ |
| US4 SDK 崩溃率 0 | §6 | T050 T051 | 宪法 II/IV | ✅ |
| E1–E11 边界情况 | §6 风险表 | T013/T021/T022/T023/T046 | §5 错误码表 | ✅（E5 双时间戳落 contracts §4 schema） |

## 2. 宪法一致性
- 原则 I：spec/plan/tasks/contracts 四件套齐备，无未定义行为落地 ✅
- 原则 II：全部 25 个任务均含验证方式；T051 性能门禁与宪法 IV 数值一致 ✅
- 原则 III：丢弃仅三类 + 计数，T050 验收断言与之对齐 ✅
- 原则 IV：2ms/50ms/10ms 指标在 spec/plan/tasks/contracts 四处一致 ✅
- 原则 V：Java 8/17、无 Kotlin、协议兼容策略在 plan §0/§3 与宪法 V 一致 ✅
- 原则 VI：可观测指标清单 T046 与宪法 VI 对齐 ✅

## 3. 图纸一致性
- 架构图四层（接口/适配/队列存储/跨进程通道）与 plan §1 分层一一对应 ✅
- 图中服务端链路（网关→Kafka→Flink→ClickHouse/ES/MySQL/Hive）与 plan §0/§4、contracts §3/§4 一致 ✅
- architecture.svg 与 architecture.drawio 由同一脚本（gen_arch_diagram.py）生成，节点坐标 1:1 ✅

## 4. 备注（非阻断）
1. **update() 未定义**：DataProvider 仅定义 insert/query，未提供 update/delete——删除确认由结果码驱动，属有意精简；实现时不得额外暴露写接口
2. **merge window 与 flush() 语义**：flush() 仅提示调度，不缩短平台侧合并窗口；已在 contracts §1 注明，实现需保持一致
3. **ES/Hive 下游为 best-effort**：ES 检索与 Hive T+1 为下游消费方，不参与 SLO 与零丢失断言（spec 功能边界已声明）
