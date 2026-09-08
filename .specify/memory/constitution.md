# 001-android-track-sdk · 项目宪法（Constitution）

> 本宪法约束 specs/001-android-track-sdk 下所有规格、计划与实现；冲突时以宪法为准

## 原则 I — 规格先行（Specification First）
- 一切实现行为必须可在 spec.md 中找到对应验收标准（US1–US4）或边界情况处置（E1–E11）
- 未在规格中定义的行为不得实现；需求变更先改规格再改代码

## 原则 II — 测试先行（Test-First Gate）
- 每个任务先写失败测试再实现（tasks.md 各项均含验证方式）
- 门禁：`mvn test` + lint + proto 兼容检查全绿方可合并；SDK 崩溃率 0 为硬性门禁

## 原则 III — 数据完整性优先（Data Integrity First）
- 全链路「至少一次 + dedupKey 幂等去重」；双进程「先落盘后传输」
- 丢弃仅允许三类且必须计数：TTL 过期、容量淘汰、超大事件（>1MB）；其余场景零丢失

## 原则 IV — 业务进程不受影响（Non-Intrusive）
- SDK 任何故障不得导致业务进程崩溃、ANR 或明显卡顿；track() P95 ≤ 2ms
- SDK 不申请业务未声明的权限；不阻塞主线程；异常一律拦截器隔离

## 原则 V — Java 技术栈（Java Stack Constraint)
- SDK 侧纯 Java 8（minSdk 21），服务端 Java 17 + Spring Boot 3；不引入 Kotlin/COROUTINES 依赖
- 协议演进仅允许 minor 向后兼容；major 变更须拒绝旧版并走兼容性监控

## 原则 VI — 可观测与可降级（Observable & Degradeable）
- 丢弃率、成功率、端到端延迟、协议拒绝率、健康度必须可观测并可告警
- 一切过载与故障路径必须有明确降级姿态（仅缓存/退避/熔断/渐进放量），禁止无限重试与无记名丢弃

## 治理（Governance）
- 本宪法由 /speckit.constitution 建立；修订须在 PR 中说明影响面并更新受影响的 spec/plan/tasks
- 所有 spec-kit 后续阶段（clarify/plan/tasks/analyze/implement）以此文件为最高裁决依据
