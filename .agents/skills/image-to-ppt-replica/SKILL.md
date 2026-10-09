---
name: image-to-ppt-replica
description: 将任意图片（信息图、架构图、仪表盘截图、海报、PPT页面截图等）1:1 复刻为可编辑的 PPT 页面（pptd 格式，配合 kimi-slides 使用）。适用于用户上传一张设计图/截图并要求"1:1 输出PPT"、"把图片做成PPT"、"复刻这页幻灯片"、"图生PPT"、"图片转可编辑PPT"等场景。核心流程：网格定位 → 分区放大读图 → 像素取色 → 按比例换算坐标 → 用文本/形状/图标/图片元素重建 → 截图对比迭代。
---

# 图片 1:1 复刻为 PPT

将一张位图设计稿（信息图 / 架构图 / 海报 / PPT 截图）复刻为由可编辑元素组成的 PPT 页面。产出物为 .pptd（kimi-slides 中间格式），交付前须先阅读 kimi-slides 的 SKILL.md 及其 reference/pptd.md、reference/cli.md、reference/shapes.md。

## 复刻原则

1. **能用元素就不用图片**：文字、色块、卡片、线条、分隔符全部用 text/shape/line/icon 元素重建，保证可编辑性。
2. **图标用 Font Awesome 近似**：`icon` 元素支持 fas/far/fab 前缀；品牌图标（Google/Chrome/Android 等）优先用 fab。四芒星、双向箭头等用 shapes.md 中的形状（star4、upDownArrow、leftRightArrow、diamond 等）。
3. **只有照片/实景图/复杂插画才裁图复用**：用脚本从原图裁出该子区域，存入 pptd 项目的 media/ 目录，以 image 元素引用。
4. **页面比例跟随原图**：`size` 设为原图宽高比换算后的尺寸（如原图 1536×1024 → size [960,640]），使坐标换算只需一个线性比例因子。

## 工作流

### 步骤 1：图像勘测（不可跳过）

用 `scripts/analyze_image.py` 辅助完成，或在 Python 环境中手工执行等价操作：

```bash
# 1) 打印尺寸并生成带网格标注的参考图（每 100px 一条红线 + 坐标数字）
python3 scripts/analyze_image.py <image> --grid -o /tmp/grid.png
# 2) 把原图切成横向条带并放大，逐块辨认文字与布局
python3 scripts/analyze_image.py <image> --bands 6 -o /tmp/band_
# 3) 局部放大某区域（x0,y0,x1,y1 为原图像素坐标），2x 放大输出
python3 scripts/analyze_image.py <image> --crop 0,0,1000,200 -o /tmp/z1.png
# 4) 像素取色（支持多点）
python3 scripts/analyze_image.py <image> --px 60,70 48,222 760,550
```

必须获取的信息：
- **整体结构**：页面分几个大区块，各区块边界 y 坐标
- **每块内容**：逐块放大到文字清晰可读，逐字记录（注意全角/半角括号、斜杠、中英文混排）
- **精确坐标**：用网格图读出每个面板/卡片/图标的像素位置
- **颜色**：对标题、背景、卡片底、边框、强调条、辅助文字逐点取色
- **字号层级**：标题 / 副标题 / 正文 / 注释的相对大小关系

模糊处进一步放大（crop 更小区域 + 更高倍率）。文字必须逐字确认，不要凭印象写。

### 步骤 2：坐标映射

- 比例因子 `s = pptd_width / 原图宽度`，所有像素坐标、宽高、字号都乘 s。
- 字号估算：量取原图中一行文字的高度（像素），乘 s 后除以 1.2 左右作为 fontSize 初值；中文粗体标题的视觉高度约为字号的 1.0–1.1 倍。
- 推荐用 Python 数据类（如一个 `els=[]` 列表 + `txt()/shape()/icon()` 辅助函数）生成 elements，最后 `yaml.safe_dump` 写出 .page 文件；比手写 YAML 快且不易错。

### 步骤 3：重建页面

按"背景 → 大面板 → 卡片 → 图标 → 文字 → 装饰（箭头/分隔线/徽标）"的顺序生成元素。注意：

- `shape` 不支持内嵌文字，卡片 = shape + 叠加 text。
- 单行标题/标签设 `wrap: false` 防止意外折行；多行说明文字用显式 `\n` 控制断行位置，不要依赖自动换行。
- 卡片内描述文字在原图中常是"两端散字居中"排版，可直接用 `\n` 拆成两行短文本模拟。
- 圆角用 `roundRect` + `adjustments`（如 [8000] 小圆角、[16000] 大圆角）。
- 虚线框：`border: {style: dash, ...}`。
- 裁切复用的照片用 `fit: {mode: fill}` 或先确认裁切比例与 bounds 一致。
- 彩色 logo 文字（如 Google）用 `<span style="color:...">` 逐字母着色。

### 步骤 4：对比迭代（至少 2 轮）

```bash
kimi-slides check <pptd目录> --level auto
kimi-slides screenshot <pptd目录> -o /tmp/shots/
```

将截图与原图并排对比，逐项检查：文字错位/重叠、意外折行、面板边界不齐、图标偏差、颜色差异。每轮只修发现的问题，修完重新截图，直到视觉一致。

常见坑：
- 面板内小标题与下方卡片重叠 → 卡片下移或压缩标题区间
- `check` 的 TextOverflow 启发式可能有误报，以截图实际效果为准
- 文字与相邻箭头/图标相碰 → 缩小字号或挪位置

### 步骤 5：交付

- .pptd 必须位于 `/mnt/agents/output/<项目名>/`，media 用相对路径。
- 只交付 .pptd，不要转 pptx。
