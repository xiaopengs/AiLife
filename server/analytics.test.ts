import { describe, expect, it } from "vitest";
import { buildStageDistribution, calculateCoreMetrics, calculateEngagementRate } from "./analytics";

describe("运营复盘指标", () => {
  it("按内容阶段完整汇总，即使某个阶段没有内容也保留该阶段", () => {
    expect(buildStageDistribution([{ status: "idea" }, { status: "idea" }, { status: "published" }])).toEqual([
      { status: "idea", count: 2 },
      { status: "draft", count: 0 },
      { status: "review", count: 0 },
      { status: "scheduled", count: 0 },
      { status: "published", count: 1 },
    ]);
  });

  it("汇总人工录入指标并安全处理空值和零曝光", () => {
    const metrics = calculateCoreMetrics([
      { impressions: 1200, likes: 84, comments: 9, collects: 36, shares: 11, followersGained: 7 },
      { impressions: null, likes: null, comments: null, collects: null, shares: null, followersGained: null },
    ]);
    expect(metrics).toEqual({ impressions: 1200, engagements: 140, followersGained: 7 });
    expect(calculateEngagementRate(metrics.impressions, metrics.engagements)).toBe(11.7);
    expect(calculateEngagementRate(0, 10)).toBe(0);
  });
});
