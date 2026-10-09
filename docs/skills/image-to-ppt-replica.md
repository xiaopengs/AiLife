# image-to-ppt-replica 安装记录

**状态：已纳入项目技能目录（本地来源压缩包）**
**安装日期：2026-10-10**
**来源：** 用户提供的技能压缩包 `image-to-ppt-replica.zip`
**目录位置：** `skills/image-to-ppt-replica/` 与 `.agents/skills/image-to-ppt-replica/`

## 1. 本次需求与实施结论

本次需求是安装 `image-to-ppt-replica` 技能，并将其提交到本仓库的 `skills/` 目录下统一版本化管理。技能来源为用户上传的压缩包，包含根入口 `SKILL.md` 与 `scripts/analyze_image.py`；不含安装脚本、网络请求或凭证读写逻辑，脚本仅依赖 Pillow 做本地图像处理。

安装沿用本仓库既有的技能目录约定：主副本置于 `skills/image-to-ppt-replica/`，并同步一份到通用 Agent 发现目录 `.agents/skills/image-to-ppt-replica/`，另补齐 `agents/openai.yaml` 以与其余技能的 `interface` / `policy` 声明保持一致。技能正文未作改写。

| 项目 | 结论 |
| --- | --- |
| 主副本 | `skills/image-to-ppt-replica/` |
| 发现目录副本 | `.agents/skills/image-to-ppt-replica/` |
| 已核验入口 | `SKILL.md`、`scripts/analyze_image.py` |
| 外部依赖 | `kimi-slides` 命令行（技能工作流要求，非本次安装范围） |
| `skills-lock.json` | 未改动；该文件记录上游 `mattpocock/skills` 的来源与哈希，由安装器维护 |
| 自动执行 | 未启用；`policy.allow_implicit_invocation: false` |

## 2. 它解决什么问题

该技能用于把任意位图设计稿（信息图、架构图、仪表盘截图、海报、PPT 页面截图等）**1:1 复刻**为由可编辑元素组成的 PPT 页面。核心流程是：网格定位 → 分区放大读图 → 像素取色 → 按比例换算坐标 → 用文本 / 形状 / 图标 / 图片元素重建 → 截图对比迭代。

产出物为 `.pptd`（kimi-slides 的中间格式），交付目录约定为 `/mnt/agents/output/<项目名>/`；技能明确要求只交付 `.pptd`，不转 `pptx`。

## 3. 使用边界与注意事项

- 使用前需先阅读 `kimi-slides` 的 `SKILL.md` 及 `reference/pptd.md`、`reference/cli.md`、`reference/shapes.md`；当前仓库未纳入该依赖，实际执行需另行具备相应运行环境。
- `scripts/analyze_image.py` 需要本地 Python 与 Pillow，仅在需要勘测图片（生成网格图、切条带、局部放大、取色）时调用。
- 复刻强调可编辑性：文字、色块、卡片、线条一律用元素重建，只有照片 / 实景图 / 复杂插画才裁图复用。
- 交付前须完成至少两轮截图对比迭代，以视觉一致为准，而非以 `check` 的启发式告警为准。
