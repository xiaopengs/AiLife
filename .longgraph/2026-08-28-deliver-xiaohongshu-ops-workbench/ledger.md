# 小红书运营工作台 — longgraph Ledger

> 这是本次运行唯一的记分板。权限顺序：`CHANGELOG.md` 与本运行 `executor.md` 的红线 > 本 ledger。环境事实见 `ops.md`；监督纠偏见 `directives.md`。
> 旧轮次仅存于 `archive/rounds.md`。正常执行不得读取归档。

## Status header

Current milestone: M1 工作台基线 | Round: 0 | Last round net lines: —  
Next unclosed work item: M1-S1 盘点当前应用入口并建立工作台信息架构基线  
Last directive folded: none

Convergence tracker: rounds since last 5: **0** | net lines since last +400: **+0** | **next round converges: no**  
Milestone gate: `open`  
Run status: `active`

---

## Current slice (下一轮从这里开始)

Item: M1-S1 盘点当前应用入口并建立工作台信息架构基线  
Write set: `client/src/App.tsx`、必要的新页面/导航组件、相关测试；开始前以实际仓库结构为准收窄  
Context: C-01、C-02、C-03、C-05  
Verify: `pnpm check && pnpm test && pnpm build`  
Done when: 已存在一个经认证后可达的工作台壳层，能通往总览、内容库、生产流程、内容日历和运营复盘的真实入口，并保留可继续交付的路由 seam。

---

## Starting snapshot

- 仓库：`main` 与 `origin/main` 同步；生成本运行前工作树干净。本运行作者创建的路径仅为 `.longgraph/2026-08-28-deliver-xiaohongshu-ops-workbench/**`；其他脏改动均视为所有者或后续执行者工作，必须保留。
- 已有基线：全栈 TypeScript 项目，含登录、关系型数据库、受保护业务接口的模板能力；可见应用面主要为 `App.tsx`、`Home.tsx`、共享布局和组件。`todo.md` 列出了本 MVP 的五条业务能力和质量要求。
- 产品约束：内容日历与发布协同仅支持排期、检查、人工发布结果登记；不得使用非官方方式直接操作小红书账号。
- 门槛基线：`package.json` 已定义 `pnpm check`、`pnpm test`、`pnpm build`。现有测试面很窄，核心工作流测试属于交付范围，不能以绿色空测试代替。

---

## Gate scoreboard

| Gate | Status | Evidence / next action |
| --- | --- | --- |
| R-01 统一工作台与模块导航 | open | M1-S1 设计并实现真实入口；见 C-01、C-02。 |
| R-02 内容资产库与总览 | open | M2：真实数据模型、接口、读写消费者与总览状态；见 C-03、C-04。 |
| R-03 内容生产与审核流转 | open | M3：维护字段、审核意见和受控状态转移；见 C-03、C-04。 |
| R-04 内容日历与人工发布协同 | open | M4：排期、检查、人工结果登记；合规红线审查；见 C-04、C-06。 |
| R-05 运营复盘指标与分析 | open | M5：指标录入、主题/类型关联与聚合展示；见 C-03、C-04。 |
| R-06 核心工作流质量与交付验收 | open | M5：核心路径测试、`check/test/build` 和人工验收记录；见 C-05、C-07。 |

## Pending promotion

Boundary: none  
Audit surface: none  
Evidence: none

## owner-blocked

| ID | Decision in plain language | Recommended choice | Other choice(s) | Why now |
| --- | --- | --- | --- | --- |
| OB-001 | 当发现现有数据库结构不足时，是否允许执行超出前向 MVP 迁移的 schema/公共合同调整？ | A — 仅允许具备迁移、回退/前进说明、真实消费者和测试证据的前向兼容改动。 | B — 暂不改 schema/合同；C — 逐项由所有者批准。 | 未预授权的破坏性或公共合同变更会阻塞相关切片。 |
| OB-002 | 是否接入外部、小红书官方能力或任何需要凭据/付费的服务？ | A — 首版不接入，继续人工协同。 | B — 仅在先确认官方能力、数据规则、预算和凭据范围后单独立项。 | 外部集成会改变数据、合规与成本边界。 |

## Debt & gap register

| ID | Priority | Milestone | One line |
| --- | --- | --- | --- |
| GAP-001 | P1 | M1 | 现有 `App.tsx`、认证入口、布局与路由当前结构需在 M1-S1 中以实际代码定位后记录到 C-01/C-02。 |
| GAP-002 | P1 | M2 | MVP 业务实体及关联关系需以真实内容库消费者反推，避免先建泛化框架。 |
| GAP-003 | P1 | M3 | 生产状态枚举、审核权限和合法迁移规则需在实现前写成可测试约束。 |
| GAP-004 | P1 | M4 | 日历粒度、发布前检查项及人工发布结果字段需由真实运营界面闭环验证。 |
| GAP-005 | P1 | M5 | 指标口径、主题/内容类型聚合和空数据状态需以可复验的测试夹具固定。 |
| GAP-006 | P2 | M5 | 关键流程的人工界面验收记录格式与保存位置需在首个可运行路径后确定。 |

## Rounds log — last 5 only

（尚无运行轮次。）
