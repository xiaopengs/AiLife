import { beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("./db", () => ({
  createContent: vi.fn(),
  updateContent: vi.fn(),
  scheduleContent: vi.fn(),
  markContentPublished: vi.fn(),
  deleteContent: vi.fn(),
  getOverview: vi.fn(),
  listContent: vi.fn(),
  getStrategyProfile: vi.fn(),
  saveStrategyProfile: vi.fn(),
  listThemes: vi.fn(),
  createTheme: vi.fn(),
  deleteTheme: vi.fn(),
  listPublishedPerformance: vi.fn(),
  savePerformanceMetrics: vi.fn(),
}));

import { appRouter } from "./routers";
import * as db from "./db";
import type { TrpcContext } from "./_core/context";

const content = {
  themeId: null,
  themeName: "AI 工作流",
  contentType: "教程" as const,
  status: "idea" as const,
  title: "把零散灵感整理成可复用的 Skill 资产",
  brief: "帮助创作者搭建自己的内容输入系统。",
  body: "正文",
  tags: "#AI工作流,#技能成长",
  coverPoints: "大标题 + 三步法",
  assetNotes: "录屏与操作截图",
  reviewNotes: "核验截图与标题一致性",
};

function createCaller() {
  const ctx = {
    user: {
      id: 24,
      openId: "creator-24",
      name: "Skill 创作者",
      email: "creator@example.com",
      loginMethod: "manus",
      role: "user",
      createdAt: new Date(),
      updatedAt: new Date(),
      lastSignedIn: new Date(),
    },
    req: {} as TrpcContext["req"],
    res: {} as TrpcContext["res"],
  } as TrpcContext;
  return appRouter.createCaller(ctx);
}

describe("内容运营关键链路", () => {
  beforeEach(() => vi.clearAllMocks());

  it("将新的 Skill 选题按当前用户写入内容库", async () => {
    vi.mocked(db.createContent).mockResolvedValue({ id: 101n });
    const result = await createCaller().content.create(content);
    expect(result).toEqual({ id: 101n });
    expect(db.createContent).toHaveBeenCalledWith(24, content);
  });

  it("将内容更新、排期与人工发布结果准确交给当前用户的业务层", async () => {
    vi.mocked(db.updateContent).mockResolvedValue({ success: true });
    vi.mocked(db.scheduleContent).mockResolvedValue({ success: true });
    vi.mocked(db.markContentPublished).mockResolvedValue({ success: true });
    const caller = createCaller();
    await caller.content.update({ id: 101, content: { ...content, status: "review" } });
    await caller.content.schedule({ id: 101, scheduledAt: "2026-09-01T09:00:00.000Z" });
    await caller.content.markPublished({ id: 101, publishResult: "已在官方客户端人工发布并完成首轮互动。", publishedUrl: "https://www.xiaohongshu.com/explore/example" });
    expect(db.updateContent).toHaveBeenCalledWith(24, 101, expect.objectContaining({ status: "review" }));
    expect(db.scheduleContent).toHaveBeenCalledWith(24, 101, expect.any(Date));
    expect(db.markContentPublished).toHaveBeenCalledWith(24, 101, "已在官方客户端人工发布并完成首轮互动。", "https://www.xiaohongshu.com/explore/example");
  });

  it("为已发布笔记保存人工录入的复盘指标", async () => {
    vi.mocked(db.savePerformanceMetrics).mockResolvedValue({ success: true });
    const metrics = { impressions: 1200, likes: 86, comments: 12, collects: 43, shares: 9, followersGained: 8 };
    const result = await createCaller().analytics.save({ contentId: 101, metrics });
    expect(result).toEqual({ success: true });
    expect(db.savePerformanceMetrics).toHaveBeenCalledWith(24, 101, metrics);
  });

  it("拒绝负数复盘指标，防止无效数据进入数据库", async () => {
    await expect(createCaller().analytics.save({ contentId: 101, metrics: { impressions: -1, likes: 0, comments: 0, collects: 0, shares: 0, followersGained: 0 } })).rejects.toMatchObject({ code: "BAD_REQUEST" });
    expect(db.savePerformanceMetrics).not.toHaveBeenCalled();
  });
});
