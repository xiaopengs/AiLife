# 小红书运营工作台 — longgraph Ledger

> 这是本次运行唯一的记分板。权限顺序：`CHANGELOG.md` 与本运行 `executor.md` 的红线 > 本 ledger。环境事实见 `ops.md`；监督纠偏见 `directives.md`。
> 旧轮次仅存于 `archive/rounds.md`。正常执行不得读取归档。

## Status header

Current milestone: M1 已登录验收准备 | Round: 0 | Last round net lines: —
Next unclosed work item: M1-S1 获取并验证获准的已登录验收方式，完成五个工作区可达性检查
Last directive folded: none

Convergence tracker: rounds since last 5: **0** | net lines since last +400: **+0** | **next round converges: no**
Milestone gate: `open`
Run status: `active`

---

## Current slice (下一轮从这里开始)

Item: M1-S1 获取并验证获准的已登录验收方式，完成五个工作区可达性检查
Write set: 只读浏览器会话、验收证据文件和 `.longgraph/2026-08-28-deliver-xiaohongshu-ops-workbench/**`；若出现可复现缺口，先登记 GAP 再精确收窄写入集合
Context: C-01、C-02、C-07
Verify: 真实已登录会话按顺序访问总览、内容库、生产、日历、复盘；记录视口、访问结果和控制台结论；随后运行 `pnpm check && pnpm test && pnpm build`
Done when: 存在有效且获准的已登录验收方式，五个区域均可由真实工作台入口访问，且不绕过认证、不使用真实账号敏感数据。

---

## Starting snapshot

- 仓库：`main` 在本运行设计提交后等待推送。远程在设计过程中新增完整 MVP 实现与 UI 设计记录，已通过变基整合；工作树中 `.longgraph/2026-08-28-deliver-xiaohongshu-ops-workbench/**` 是本运行作者路径，其他改动必须保留。
- 实现状态：五个运营区域、业务数据结构、受保护业务接口、核心工作流测试、加载/异常/空状态和 UI 设计均已提交。`todo.md` 目前仅保留已登录端到端工作流验证、已登录视觉层次验证及持续变更记录为未完成项。
- 已测自动化基线：本任务环境已用锁定文件安装依赖后运行 `pnpm check`（通过）、`pnpm test`（3 个测试文件、7 个测试通过）和 `pnpm build`（通过）。构建输出两条未定义的可选分析环境变量警告及一个 JS chunk 尺寸建议，未作为失败处理，但其产品/发布处理结论尚未记录。
- 产品约束：内容日历与发布协同仅支持排期、检查、人工发布结果登记；不得使用非官方方式直接操作小红书账号。

---

## Gate scoreboard

| Gate | Status | Evidence / next action |
| --- | --- | --- |
| R-01 已登录五区可达性 | in-progress | 自动化基线已通过；待在获准已登录会话中依序验证五个入口、加载/空/错误状态。 |
| R-02 从选题到复盘的真实闭环 | open | M2：创建选题 → 编辑/审核 → 排期/检查 → 人工发布登记 → 指标回读与聚合。 |
| R-03 真实缺口的最小修复 | open | 仅处理 M1/M2 发现且可复现的缺口；每项需真实回归证据。 |
| R-04 桌面与窄屏体验验收 | open | M3：在已登录会话验证差异化工作面、关键操作可达、无阻断溢出。 |
| R-05 质量与合规发布审计 | in-progress | `check/test/build` 已通过；待记录可选分析变量警告、chunk 建议与人工合规审查结论。 |
| R-06 证据与变更可追溯 | in-progress | 本运行契约已创建；待汇总已登录验收、监督结果和最终提交记录。 |

## Pending promotion

Boundary: none
Audit surface: none
Evidence: `pnpm check`、`pnpm test`（3 文件 / 7 测试）、`pnpm build` 已在任务设计阶段通过；未替代真实已登录验收。

## owner-blocked

| ID | Decision in plain language | Recommended choice | Other choice(s) | Why now |
| --- | --- | --- | --- | --- |
| OB-001 | 是否授权使用一个非敏感的开发/演示身份完成已登录工作台验收？ | A — 使用现有获准开发身份和样例数据；不记录任何凭据或真实运营资料。 | B — 用户自行完成登录后由执行者仅观察；C — 暂停已登录验收，仅保留自动化基线。 | M1 和 M2 需要真实认证后的消费者路径，匿名入口不能替代。 |
| OB-002 | 是否处理可选分析环境变量警告或构建包体建议？ | A — 首版将其作为非阻断发布观察项，记录原因，不接入外部分析服务。 | B — 单独立项，并在确认服务、凭据、预算和数据规则后处理。 | 处理可能涉及外部服务或额外范围；当前构建已通过。 |
| OB-003 | 若出现需修改既有数据语义、公共合同或远程环境的缺口，是否允许继续？ | A — 仅处理前向兼容的本地最小修复，其余保持 owner-blocked。 | B — 逐项审批。 | 防止验收活动借机扩大技术和数据风险。 |

## Debt & gap register

| ID | Priority | Milestone | One line |
| --- | --- | --- | --- |
| GAP-001 | P1 | M1 | 获取可用于验收的有效、获准已登录会话；不得通过绕过认证或真实账号敏感数据替代。 |
| GAP-002 | P1 | M2 | 在已登录会话中记录选题创建、生产编辑、审核/状态推进、排期、检查、人工发布登记、指标保存和总览/复盘回读的逐步证据。 |
| GAP-003 | P1 | M3 | 用典型桌面和窄屏视口逐页验证五个工作区的关键操作可达性、响应式布局和异常/空状态。 |
| GAP-004 | P2 | M4 | 确认两条可选分析环境变量警告与 JS chunk 尺寸建议的发布处理结论；不得借此未经授权接入第三方服务。 |
| GAP-005 | P2 | M4 | 汇总每一项人工验收、自动化门槛、缺口关闭和监督结果，并记录最终发布建议。 |

## Rounds log — last 5 only

- R0 2026-08-28 | 任务设计基线 | changed: `.longgraph/2026-08-28-deliver-xiaohongshu-ops-workbench/**` | verify: 所有运行文件无模板占位符，且均不超过 200 行；`pnpm check`、`pnpm test`（3 文件 / 7 测试）、`pnpm build` 通过 | net +0 产品代码 | next: M1-S1（C-01、C-02、C-07）
