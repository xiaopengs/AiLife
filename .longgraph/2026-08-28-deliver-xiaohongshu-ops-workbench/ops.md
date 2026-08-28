# 小红书运营工作台 — Environment & Ops Facts

> 先读取索引，再只打开当前 ledger 工作项所需的行。不得在每一轮重读整个文件。
> 红线：任何许可证密钥、凭据和真实运营数据均不得进入仓库、日志或提交；这里只记录处理政策。

## Context index

| ID | Use when | Read | Verify |
| --- | --- | --- | --- |
| C-01 | 已登录工作台入口、导航和品牌层次 | `client/src/App.tsx`、`client/src/components/DashboardLayout.tsx`、`client/src/pages/Home.tsx`、`docs/product/workbench-ui-design.md` | 已登录会话访问总览与每一个导航入口；记录控制台和截图结论。 |
| C-02 | 内容库与生产环节的真实消费者路径 | `client/src/pages/Library.tsx`、`client/src/pages/Workflow.tsx`、`server/routers/operations.ts`、`server/operations.router.test.ts` | 已登录创建选题、编辑生产资料、审核/状态推进；`pnpm test -- server/operations.router.test.ts`。 |
| C-03 | 日历、人工发布协同与合规边界 | `client/src/pages/Calendar.tsx`、`server/routers/operations.ts`、`todo.md`、`CHANGELOG.md#首版产品定义` | 已登录排期、执行检查、登记人工发布；人工审查不存在账号直接操作路径。 |
| C-04 | 运营复盘与指标聚合 | `client/src/pages/Analytics.tsx`、`server/analytics.ts`、`server/analytics.test.ts` | 已登录保存指标并查看主题/类型聚合；`pnpm test -- server/analytics.test.ts`。 |
| C-05 | 自动化质量门槛与构建输出 | `package.json#scripts`、`tsconfig.json`、`vitest.config.ts`、`client/index.html` | `pnpm check && pnpm test && pnpm build`；记录分析变量警告和 bundle 建议。 |
| C-06 | 真实认证边界和匿名入口 | `client/src/pages/Home.tsx`、`server/auth.logout.test.ts`、`CHANGELOG.md#预览验证记录` | 使用获准开发/演示身份；不得绕过认证，亦不得记录凭据。 |
| C-07 | 变更、缺口和最终验收的可追溯性 | `todo.md`、`CHANGELOG.md`、本运行 `ledger.md` 和 `directives.md` | 每次提交前 `git diff --check`；每个闭合切片关联测试或人工证据。 |

Always-hot: `ledger.md` 的 Status header 和 Current slice，以及 `directives.md` 中未折叠内容。  
On-reference only: 上述索引行、版本控制差异、对应测试证据和 `archive/`。不得在正常轮次读取 archive，也不得用全仓扫描替代索引。

## Build / test

项目的自动化质量门槛如下。执行者必须在每一个切片运行与其写入面相称的窄检查；监督者仅在里程碑提升、提交审计或窄测试不足以证明验收时运行完整门槛。

```sh
pnpm check
pnpm test
pnpm build
git diff --check
```

本任务设计时已验证：`pnpm check` 通过；`pnpm test` 通过（3 个测试文件、7 个测试）；`pnpm build` 通过。构建有两条缺失的可选分析环境变量警告，以及一条 JS chunk 尺寸建议；它们必须在 M4 形成明确、可审计的处理结论，不能被静默忽略或在未授权情况下通过外部服务消除。

## Standards / conventions

业务定义、已有实现和交付范围以 `todo.md`、`CHANGELOG.md` 与 `docs/product/workbench-ui-design.md` 为事实来源。已实现的五个运营区域不得在缺乏真实验收缺口的情况下重构或扩张。一次改动只有同时满足真实消费者路径、可重复验收检查和相称质量门槛时才算完成；每次需求、方案或代码变更均须更新 `CHANGELOG.md` 并提交。

## Credentials / secrets policy

任何凭据只能通过运行环境提供，绝不打印、复制进文档、写入测试夹具、提交到 Git 或置入 `.longgraph` 运行记录。已登录验收必须使用获准的开发/演示身份；发现疑似密钥、真实账号信息或生产连接字符串时，立即停止相关工作，登记 GAP，并请求用户决定处理方式。

## Data policy

验证过程仅使用获准的样例或开发数据。不得持久化、导出或提交真实小红书账号数据、身份信息或发布凭据。内容日历的“发布结果”仅是人工完成后的业务记录，不能被实现为直接操作平台账号的接口、脚本或自动化流程。

## Resource and launch facts

本任务是**提示词交付、人工触发**的长期验收任务，而非当前临时环境的常驻自动化。每次执行者与监督者均应在彼此独立的上下文中启动，以 `.longgraph/2026-08-28-deliver-xiaohongshu-ops-workbench/` 为唯一共享状态；不在当前临时环境创建 cron、后台守护进程或任务 ID。

建议每次执行者触发覆盖一个完整的可验证切片或当前里程碑的自然接缝；监督审计在每次里程碑候选提升、每个独立提交前以及最多每 3–4 次执行者触发后执行一次。
