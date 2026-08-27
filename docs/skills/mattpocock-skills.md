# mattpocock/skills 安装记录

> **状态：**已安装为项目级、可版本化副本
>
> **安装日期：**2026-08-28
>
> **上游仓库：**[mattpocock/skills][1]
>
> **固定版本：**`6654f6b60cd9d5be8b54c6fafe44346dabeb3b76`
>
> **上游提交日期：**2026-08-24T15:19:57+01:00
> **上游许可证：**MIT

## 安装决策

上游同时提供受管插件和项目内可编辑文件两种方式。为保证本项目的构建规则、提示词资产和协作流程可以在仓库中审计、复现并随提交同步，本次选择其项目级安装路径，而不采用外部受管插件。[1]

安装器的全代理选项会生成大量平台专用的重复副本。项目仅保留通用的 `skills/`、`.agents/skills/`、`skills-lock.json` 和完整的 `third_party/mattpocock-skills/` 上游快照；其余不适用于本项目的代理专用目录已清理。这一收敛避免将同一批 37 个技能重复存放数十次，同时保留标准化调用入口与可审计源代码。

| 项目资产 | 用途 | 更新原则 |
| --- | --- | --- |
| `skills/` | 安装器生成的项目级技能副本，共 37 个技能。 | 由 `npx skills@latest update --project --yes` 更新；更新后必须复核差异、更新本记录并提交。 |
| `.agents/skills/` | 通用 Agent 发现目录，对应同一批 37 个技能。 | 与 `skills/` 保持一致；仅为通用发现而保留。 |
| `skills-lock.json` | 记录每个技能的来源、路径和内容哈希。 | 不手工改写；随安装器更新一并提交。 |
| `third_party/mattpocock-skills/` | 上游完整快照，包含 README、许可证、文档、脚本和全部分类结构。 | 仅用于审计、比对和评估；更新时替换为新的上游快照并更新提交号。 |

## 可用能力概览

当前项目安装 37 个技能，覆盖工程规划、领域建模、代码审查、故障诊断、测试驱动开发、规范与工单转换、调研、交接、回顾和技术写作等类别。默认不应将某项技能视作自动执行许可：使用时仍需遵从项目约束、代码审查和安全要求。

| 类别 | 代表技能 | 预期使用场景 |
| --- | --- | --- |
| 需求与方案 | `grill-me`、`to-questionnaire`、`to-spec`、`to-tickets` | 需求澄清、方案规格化、工作项拆分。 |
| 架构与实现 | `domain-modeling`、`codebase-design`、`implement`、`prototype` | 模型设计、技术方案、分阶段实现。 |
| 质量保障 | `code-review`、`diagnosing-bugs`、`tdd`、`resolving-merge-conflicts` | 评审、排错、测试与代码整合。 |
| 协作与知识 | `research`、`handoff`、`retro`、`writing-for-agents` | 调研沉淀、交接、复盘与面向 Agent 的说明。 |

## 更新与审计步骤

更新前应先确认工作树干净，并对上游变更进行审阅。更新完成后，至少检查 `skills-lock.json`、`skills/`、`.agents/skills/` 和上游快照之间的差异。若技能新增外部执行、网络访问、凭证或自动化行为，需要先做单独的安全评审，再决定是否在项目中启用。

```bash
npx skills@latest update --project --yes
git diff -- skills-lock.json skills .agents/skills third_party/mattpocock-skills
git status --short
```

## 参考资料

[1]: https://github.com/mattpocock/skills "mattpocock/skills — Skills For Real Engineers"
