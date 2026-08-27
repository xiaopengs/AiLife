import type { ContentStatus } from "../drizzle/schema";

export const CONTENT_STATUSES: ContentStatus[] = ["idea", "draft", "review", "scheduled", "published"];

type StageItem = { status: ContentStatus };
type MetricRow = {
  impressions: number | null;
  likes: number | null;
  comments: number | null;
  collects: number | null;
  shares: number | null;
  followersGained: number | null;
};

export function buildStageDistribution(items: StageItem[]) {
  return CONTENT_STATUSES.map(status => ({
    status,
    count: items.filter(item => item.status === status).length,
  }));
}

export function calculateCoreMetrics(rows: MetricRow[]) {
  return rows.reduce(
    (total, row) => ({
      impressions: total.impressions + (row.impressions ?? 0),
      engagements:
        total.engagements +
        (row.likes ?? 0) +
        (row.comments ?? 0) +
        (row.collects ?? 0) +
        (row.shares ?? 0),
      followersGained: total.followersGained + (row.followersGained ?? 0),
    }),
    { impressions: 0, engagements: 0, followersGained: 0 },
  );
}

export function calculateEngagementRate(impressions: number, engagements: number) {
  if (impressions <= 0) return 0;
  return Number(((engagements / impressions) * 100).toFixed(1));
}
