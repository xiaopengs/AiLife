# 小红书运营工作台 — Environment & Ops Facts

> 先读取索引，再只打开当前 ledger 工作项所需的行。不得在每一轮重读整个文件。
> 红线：任何许可证密钥、凭据和真实运营数据均不得进入仓库、日志或提交；这里只记录处理政策。

## Context index

| ID | Use when | Read | Verify |
| --- | --- | --- | --- |
| C-01 | M1 应用壳层、认证后入口和全局导航 | `client/src/App.tsx`、`client/src/main.tsx`、`client/src/pages/Home.tsx`、`client/src/components/DashboardLayout.tsx` | `pnpm check && pnpm build` |
| C-02 | M1 路由或页面新增 | `client/src/pages/` 下当前页面、`client/src/components/` 中被复用的导航/错误边界组件 | `pnpm check && pnpm test` |
| C-03 | M2–M5 业务实体、关系与迁移 | `drizzle/schema.ts`、`drizzle/relations.ts`、`drizzle/0000_fantastic_prism.sql`、`server/db.ts` | 针对改动路径的测试；需要迁移时先核验 S-003 条件 |
| C-04 | M2–M5 受保护接口与真实前端消费者 | `server/routers.ts`、`server/auth.logout.test.ts`、`server/storage.ts`、`client/src/lib/trpc.ts`、对应业务页面 | `pnpm test` 与手动真实读写路径验证 |
| C-05 | 全部质量门槛与测试配置 | `package.json#scripts`、`tsconfig.json`、`vitest.config.ts`、`.prettierrc` | `pnpm check && pnpm test && pnpm build` |
| C-06 | 内容日历、发布协同与合规边界 | `todo.md` 的日历/发布项；`CHANGELOG.md` 的首版产品定义和技术方案基线；本运行 `executor.md#权限与红线` | 人工审查：不存在非官方小红书账号连接、发帖或自动化路径 |
| C-07 | 需求/方案变更与交付证据 | `CHANGELOG.md`、`todo.md`、本运行 `ledger.md` 与 `directives.md` | 每次提交前 `git diff --check`；每个闭合切片关联验收证据 |

Always-hot: `ledger.md` 的 Status header 和 Current slice，以及 `directives.md` 中未折叠内容。  
On-reference only: 上述索引行、版本控制差异、对应测试证据和 `archive/`。不得在正常轮次读取 archive，也不得用全仓扫描替代索引。

## Build / test

项目的基线质量门槛如下。执行者必须在每一个切片运行与其写入面相称的窄测试；监督者仅在里程碑提升、提交审计或窄测试不足以证明验收时运行完整门槛。

```sh
pnpm check
pnpm test
pnpm build
git diff --check
```

首次涉及数据库迁移时，先确认迁移命令、数据库配置和 S-003 预授权条件；不得在不清楚环境影响时推送或操作远程数据库。

## Standards / conventions

项目的业务定义与交付范围以 `todo.md` 和 `CHANGELOG.md` 为事实来源；每一次需求、方案或代码变更均须记录于 `CHANGELOG.md` 并提交。客户端遵循现有 TypeScript、Prettier 和共享组件约定。一次改动只有同时满足真实消费者路径、可重复验收检查和相称质量门槛时才算完成。

## Credentials / secrets policy

任何凭据只能通过运行环境提供，绝不打印、复制进文档、写入测试夹具、提交到 Git 或置入 `.longgraph` 运行记录。发现疑似密钥、真实账号信息或生产连接字符串时，立即停止相关工作，记录 GAP，并请求用户决定处理方式。

## Data policy

首版使用样例、测试或经允许的开发数据证明功能。不得持久化、导出或提交真实小红书账号数据、身份信息或发布凭据。内容日历的“发布结果”仅是人工完成后的业务记录，不能被实现为直接操作平台账号的接口、脚本或自动化流程。

## Resource and launch facts

本任务是**提示词交付、人工触发**的长期任务设计，而非当前沙箱的常驻自动化。每次执行者与监督者均应在彼此独立的上下文中启动，以 `.longgraph/2026-08-28-deliver-xiaohongshu-ops-workbench/` 为唯一共享状态；不在当前临时环境创建 cron、后台守护进程或任务 ID。

建议每次执行者触发覆盖一个完整的可验证切片或当前里程碑的自然接缝；监督审计在每次里程碑候选提升、每个独立提交前以及最多每 3–4 次执行者触发后执行一次。
