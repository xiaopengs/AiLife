# longgraph-skill 安装与使用指南

**状态：已安装（本地技能目录）**  
**安装日期：2026-08-28**  
**来源：** [`levi-qiao/longgraph-skill`](https://github.com/levi-qiao/longgraph-skill)  
**固定版本：** `220bbf7c5f71025b8e5991b3436e3d6ef766d143`（仓库 `main` 于安装时的提交）

## 1. 本次需求与实施结论

本次需求是在当前环境中安装并掌握 `longgraph-skill`。该技能的定位是把**需要多个回合、持久状态、里程碑闸门与独立验收**的复杂工作编译为一套长期运行的工作图，而不是替代普通的一次性任务。[1] 技能已经以固定提交安装到本机的 `$HOME/skills/longgraph`，并已核验根入口与四个子技能入口文件均存在且非空。

安装采用**审阅后、固定版本的本地克隆**，没有执行远程管道安装命令，也没有启动任何由技能生成的运行节点。这样既保留技能文档和模板，又避免在本环境中自动拉取更新或执行未经本任务验证的后台流程。此安装使技能内容可供后续按需加载；是否将其注册为特定宿主的斜杠命令，取决于该宿主自身的插件/技能加载机制。

| 项目 | 结论 |
| --- | --- |
| 本地安装目录 | `$HOME/skills/longgraph` |
| 固定版本 | `220bbf7c5f71025b8e5991b3436e3d6ef766d143` |
| 已核验入口 | `SKILL.md`、`loop-converge`、`loop-deliver`、`loop-research`、`loop-graph` |
| 自动执行 | 未启用；技能本身也要求作者入口不得执行或恢复生成的运行节点 [2] |
| 外部宿主配置 | 未修改；当前环境中未检测到 Codex、Cursor、Grok Build 或 Claude Code 的相应技能目录 |

## 2. 它解决什么问题

`longgraph` 适合把一个长周期目标拆为由持久记分板（ledger）协调的工作回合。每一个回合只处理一项可独立验收的工作，执行者负责更新记分板，监督者则在独立上下文重新验证，并通过单向的 directives 边纠正偏差。[1] 这种职责隔离适用于复杂工程需求、持续收敛的代码治理，或需要先研究再决策的技术选型。

> **适用原则：** 当任务可以作为一个普通目标在一个上下文内完成时，不应额外套用 longgraph；只有工作需要多轮推进、可追溯状态、验收闸门或独立审核时才使用。[2]

| 入口 | 何时使用 | 预期作用 |
| --- | --- | --- |
| `/longgraph` | 尚未确定属于哪种工作形态 | 路由至最合适的预设；必要时使用通用 `loop-graph` [2] |
| `/loop-deliver` | 新功能、集成、迁移或行为变更，且需要多个经验证的垂直切片 | 通过需求访谈编译执行者、监督者、ledger、directives 和运维产物 [3] |
| `/loop-converge` | 清理未使用代码、消除重复、整合和瘦身 | 使用代码收敛预设 [2] |
| `/loop-research` | 在作出承诺前，需要证据支持的方案比较 | 使用受控研究、开源实现和一手资料进行选择 [2] |
| `loop-graph` | 预设都不匹配，但仍需持久状态、闸门与独立验证 | 编译自定义工作图；应避免用于简单任务 [2] |

## 3. 推荐使用方式

在支持斜杠命令的宿主中，先直接调用 `/longgraph`。它会检查工作区和当前宿主，再仅就无法推断的 owner 决策发起询问；根据仓库文档，这些决策通常围绕验收结果、授权边界和启动方式。[1] 对于明确的需求交付，也可以直接调用 `/loop-deliver`，以减少路由步骤。[3]

针对本项目，建议只有在一个需求需要跨多个开发回合并且每个回合都要留存验收证据时使用，例如“完成多模块内容运营工作台，并逐切片验收数据模型、接口、页面和业务流程”。普通的页面调整、单一接口修复或一次性文档工作，应继续采用常规任务流程。

```text
示例：使用 longgraph 为一个多回合需求建立工作图

/loop-deliver
目标：为小红书运营工作台新增内容日历与人工发布结果登记。
验收：每个垂直切片都通过类型检查、相关测试和人工验收记录。
授权：允许在当前仓库修改应用代码、迁移和测试；发布动作不直接操作小红书账号。
启动：先生成并审阅 .longgraph/<日期-标识>/ 下的产物，再决定是否启动运行节点。
```

生成后的 run 目录位于 `.longgraph/<date-slug>/`，其内容是已冻结的本次运行契约。[1] 在启动前，应至少审阅目标、工作项、验收标准、可修改范围与停止条件；不要把生成的提示词或脚本当作无需复核即可执行的指令。

## 4. 宿主适配与本次限制

上游提供的 `install.sh` 主要面向会跟随符号链接的 Codex、Cursor 与 Grok Build：它会把根路由和三个预设链接到相应技能目录；Claude Code 则需要通过其插件市场安装。[4] 当前环境中未发现这些宿主目录，因此本次没有运行该脚本，也没有对任何外部客户端配置进行改写。

| 宿主 | 上游建议的接入方式 | 本次状态 |
| --- | --- | --- |
| Codex / Cursor / Grok Build | 运行上游安装脚本，将技能和预设链接到各自的 skills 目录 [4] | 未配置：目录不存在 |
| Claude Code | 添加 marketplace 后安装 `longgraph@longgraph-skill` 插件 [4] | 未配置：未发现相应插件目录 |
| 当前环境 | 按需读取 `$HOME/skills/longgraph/SKILL.md` 及其子技能文档 | 已完成本地安装与入口核验 |

## 5. 更新、回退与维护

为保证可复现性，当前版本处于 detached HEAD 的固定提交，而非自动跟随远程 `main`。后续如需更新，应先检查上游变更、审阅 `install.sh` 和 `SKILL.md` 的差异，再创建新的固定版本记录；不要直接执行 `curl | sh`。若更新引入不兼容行为，可将本地目录切换回本文档记录的提交。

每次修改此技能的安装方式、版本或项目使用策略时，均应同步更新本文件和 `CHANGELOG.md`，并提交到项目仓库，以满足本项目对需求与方案变化的可追溯性要求。

## References

[1]: https://github.com/levi-qiao/longgraph-skill "levi-qiao/longgraph-skill — README"
[2]: https://github.com/levi-qiao/longgraph-skill/blob/main/SKILL.md "longgraph root skill"
[3]: https://github.com/levi-qiao/longgraph-skill/blob/main/skills/loop-deliver/SKILL.md "loop-deliver preset"
[4]: https://github.com/levi-qiao/longgraph-skill/blob/main/install.sh "longgraph installer"
