import { and, desc, eq } from "drizzle-orm";
import { drizzle } from "drizzle-orm/mysql2";
import {
  contentItems,
  type ContentStatus,
  performanceMetrics,
  skillThemes,
  strategyProfiles,
  type InsertUser,
  users,
} from "../drizzle/schema";
import { buildStageDistribution, calculateCoreMetrics, calculateEngagementRate } from "./analytics";
import { ENV } from "./_core/env";

let _db: ReturnType<typeof drizzle> | null = null;

export async function getDb() {
  if (!_db && process.env.DATABASE_URL) {
    try {
      _db = drizzle(process.env.DATABASE_URL);
    } catch (error) {
      console.warn("[Database] Failed to connect:", error);
      _db = null;
    }
  }
  return _db;
}

async function requireDb() {
  const db = await getDb();
  if (!db) throw new Error("数据库暂不可用，请稍后重试。");
  return db;
}

export async function upsertUser(user: InsertUser): Promise<void> {
  if (!user.openId) throw new Error("User openId is required for upsert");
  const db = await getDb();
  if (!db) return;

  const values: InsertUser = { openId: user.openId, lastSignedIn: user.lastSignedIn ?? new Date() };
  const updateSet: Record<string, unknown> = { lastSignedIn: values.lastSignedIn };
  (["name", "email", "loginMethod"] as const).forEach(field => {
    if (user[field] !== undefined) {
      values[field] = user[field] ?? null;
      updateSet[field] = user[field] ?? null;
    }
  });
  if (user.role !== undefined) {
    values.role = user.role;
    updateSet.role = user.role;
  } else if (user.openId === ENV.ownerOpenId) {
    values.role = "admin";
    updateSet.role = "admin";
  }
  await db.insert(users).values(values).onDuplicateKeyUpdate({ set: updateSet });
}

export async function getUserByOpenId(openId: string) {
  const db = await getDb();
  if (!db) return undefined;
  const result = await db.select().from(users).where(eq(users.openId, openId)).limit(1);
  return result[0];
}

export type ContentInput = {
  themeId: number | null;
  themeName: string;
  contentType: "教程" | "清单" | "案例" | "观点" | "复盘";
  status: ContentStatus;
  title: string;
  brief: string;
  body: string;
  tags: string;
  coverPoints: string;
  assetNotes: string;
  reviewNotes: string;
};

export async function getOverview(userId: number) {
  const db = await requireDb();
  const [items, metrics] = await Promise.all([
    db
      .select({ id: contentItems.id, title: contentItems.title, status: contentItems.status, themeName: contentItems.themeName, scheduledAt: contentItems.scheduledAt, updatedAt: contentItems.updatedAt })
      .from(contentItems)
      .where(eq(contentItems.userId, userId)),
    db
      .select({ impressions: performanceMetrics.impressions, likes: performanceMetrics.likes, comments: performanceMetrics.comments, collects: performanceMetrics.collects, shares: performanceMetrics.shares, followersGained: performanceMetrics.followersGained })
      .from(performanceMetrics)
      .where(eq(performanceMetrics.userId, userId)),
  ]);
  const coreMetrics = calculateCoreMetrics(metrics);
  const stageDistribution = buildStageDistribution(items);
  const attentionItems = items
    .filter(item => item.status === "review" || item.status === "scheduled")
    .sort((left, right) => (left.scheduledAt?.getTime() ?? Number.MAX_SAFE_INTEGER) - (right.scheduledAt?.getTime() ?? Number.MAX_SAFE_INTEGER))
    .slice(0, 5);

  return {
    ideaReserve: items.filter(item => item.status === "idea").length,
    pendingReview: items.filter(item => item.status === "review").length,
    pendingPublish: items.filter(item => item.status === "scheduled").length,
    stageDistribution,
    attentionItems,
    metrics: { ...coreMetrics, engagementRate: calculateEngagementRate(coreMetrics.impressions, coreMetrics.engagements) },
  };
}

export async function listContent(userId: number, status?: ContentStatus) {
  const db = await requireDb();
  const where = status ? and(eq(contentItems.userId, userId), eq(contentItems.status, status)) : eq(contentItems.userId, userId);
  return db.select().from(contentItems).where(where).orderBy(desc(contentItems.updatedAt));
}

export async function createContent(userId: number, input: ContentInput) {
  const db = await requireDb();
  const result = await db.insert(contentItems).values({ userId, ...input, scheduledAt: null, publishedAt: null, publishedUrl: null, publishResult: "" });
  return { id: result[0].insertId };
}

export async function updateContent(userId: number, id: number, input: ContentInput) {
  const db = await requireDb();
  await db.update(contentItems).set({ ...input, updatedAt: new Date() }).where(and(eq(contentItems.id, id), eq(contentItems.userId, userId)));
  return { success: true } as const;
}

export async function scheduleContent(userId: number, id: number, scheduledAt: Date) {
  const db = await requireDb();
  await db.update(contentItems).set({ status: "scheduled", scheduledAt, updatedAt: new Date() }).where(and(eq(contentItems.id, id), eq(contentItems.userId, userId)));
  return { success: true } as const;
}

export async function markContentPublished(userId: number, id: number, publishResult: string, publishedUrl: string) {
  const db = await requireDb();
  await db
    .update(contentItems)
    .set({ status: "published", publishedAt: new Date(), publishResult, publishedUrl: publishedUrl || null, updatedAt: new Date() })
    .where(and(eq(contentItems.id, id), eq(contentItems.userId, userId)));
  return { success: true } as const;
}

export async function deleteContent(userId: number, id: number) {
  const db = await requireDb();
  await db.delete(contentItems).where(and(eq(contentItems.id, id), eq(contentItems.userId, userId)));
  return { success: true } as const;
}

export async function getStrategyProfile(userId: number) {
  const db = await requireDb();
  const result = await db.select().from(strategyProfiles).where(eq(strategyProfiles.userId, userId)).limit(1);
  return result[0] ?? null;
}

export type StrategyInput = { accountName: string; positioning: string; targetAudience: string; corePromise: string; brandVoice: string };

export async function saveStrategyProfile(userId: number, input: StrategyInput) {
  const db = await requireDb();
  const existing = await getStrategyProfile(userId);
  if (existing) {
    await db.update(strategyProfiles).set({ ...input, updatedAt: new Date() }).where(eq(strategyProfiles.userId, userId));
  } else {
    await db.insert(strategyProfiles).values({ userId, ...input });
  }
  return { success: true } as const;
}

export async function listThemes(userId: number) {
  const db = await requireDb();
  return db.select().from(skillThemes).where(eq(skillThemes.userId, userId)).orderBy(desc(skillThemes.updatedAt));
}

export async function createTheme(userId: number, input: { name: string; description: string; audienceNeed: string }) {
  const db = await requireDb();
  const result = await db.insert(skillThemes).values({ userId, ...input });
  return { id: result[0].insertId };
}

export async function deleteTheme(userId: number, id: number) {
  const db = await requireDb();
  await db.delete(skillThemes).where(and(eq(skillThemes.id, id), eq(skillThemes.userId, userId)));
  return { success: true } as const;
}

export async function listPublishedPerformance(userId: number) {
  const db = await requireDb();
  return db
    .select({
      contentId: contentItems.id,
      title: contentItems.title,
      themeName: contentItems.themeName,
      contentType: contentItems.contentType,
      publishedAt: contentItems.publishedAt,
      impressions: performanceMetrics.impressions,
      likes: performanceMetrics.likes,
      comments: performanceMetrics.comments,
      collects: performanceMetrics.collects,
      shares: performanceMetrics.shares,
      followersGained: performanceMetrics.followersGained,
    })
    .from(contentItems)
    .leftJoin(performanceMetrics, eq(performanceMetrics.contentId, contentItems.id))
    .where(and(eq(contentItems.userId, userId), eq(contentItems.status, "published")))
    .orderBy(desc(contentItems.publishedAt));
}

export type MetricsInput = { impressions: number; likes: number; comments: number; collects: number; shares: number; followersGained: number };

export async function savePerformanceMetrics(userId: number, contentId: number, input: MetricsInput) {
  const db = await requireDb();
  const existing = await db.select({ id: performanceMetrics.id }).from(performanceMetrics).where(and(eq(performanceMetrics.contentId, contentId), eq(performanceMetrics.userId, userId))).limit(1);
  if (existing[0]) {
    await db.update(performanceMetrics).set({ ...input, recordedAt: new Date(), updatedAt: new Date() }).where(eq(performanceMetrics.id, existing[0].id));
  } else {
    await db.insert(performanceMetrics).values({ userId, contentId, ...input, recordedAt: new Date() });
  }
  return { success: true } as const;
}
