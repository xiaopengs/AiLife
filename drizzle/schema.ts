import { index, int, mysqlEnum, mysqlTable, text, timestamp, uniqueIndex, varchar } from "drizzle-orm/mysql-core";

/** Core user table backing the Manus OAuth flow. */
export const users = mysqlTable("users", {
  id: int("id").autoincrement().primaryKey(),
  openId: varchar("openId", { length: 64 }).notNull().unique(),
  name: text("name"),
  email: varchar("email", { length: 320 }),
  loginMethod: varchar("loginMethod", { length: 64 }),
  role: mysqlEnum("role", ["user", "admin"]).default("user").notNull(),
  createdAt: timestamp("createdAt").defaultNow().notNull(),
  updatedAt: timestamp("updatedAt").defaultNow().onUpdateNow().notNull(),
  lastSignedIn: timestamp("lastSignedIn").defaultNow().notNull(),
});

export const contentStatusValues = ["idea", "draft", "review", "scheduled", "published"] as const;
export const contentTypeValues = ["教程", "清单", "案例", "观点", "复盘"] as const;

/** One strategy record captures the positioning rules that guide a creator account. */
export const strategyProfiles = mysqlTable(
  "strategy_profiles",
  {
    id: int("id").autoincrement().primaryKey(),
    userId: int("userId").notNull(),
    accountName: varchar("accountName", { length: 120 }).notNull(),
    positioning: text("positioning").notNull(),
    targetAudience: text("targetAudience").notNull(),
    corePromise: text("corePromise").notNull(),
    brandVoice: text("brandVoice").notNull(),
    updatedAt: timestamp("updatedAt").defaultNow().onUpdateNow().notNull(),
    createdAt: timestamp("createdAt").defaultNow().notNull(),
  },
  table => [uniqueIndex("strategy_profiles_user_unique").on(table.userId)],
);

/** Reusable content pillars for the Skill creation vertical. */
export const skillThemes = mysqlTable(
  "skill_themes",
  {
    id: int("id").autoincrement().primaryKey(),
    userId: int("userId").notNull(),
    name: varchar("name", { length: 120 }).notNull(),
    description: text("description").notNull(),
    audienceNeed: text("audienceNeed").notNull(),
    createdAt: timestamp("createdAt").defaultNow().notNull(),
    updatedAt: timestamp("updatedAt").defaultNow().onUpdateNow().notNull(),
  },
  table => [index("skill_themes_user_idx").on(table.userId)],
);

/** A content item moves through ideation, writing, review, schedule, and publication. */
export const contentItems = mysqlTable(
  "content_items",
  {
    id: int("id").autoincrement().primaryKey(),
    userId: int("userId").notNull(),
    themeId: int("themeId"),
    themeName: varchar("themeName", { length: 120 }).notNull().default("未归类"),
    contentType: mysqlEnum("contentType", contentTypeValues).notNull().default("教程"),
    status: mysqlEnum("status", contentStatusValues).notNull().default("idea"),
    title: varchar("title", { length: 180 }).notNull(),
    brief: text("brief").notNull(),
    body: text("body").notNull(),
    tags: text("tags").notNull(),
    coverPoints: text("coverPoints").notNull(),
    assetNotes: text("assetNotes").notNull(),
    reviewNotes: text("reviewNotes").notNull(),
    scheduledAt: timestamp("scheduledAt"),
    publishedAt: timestamp("publishedAt"),
    publishedUrl: varchar("publishedUrl", { length: 500 }),
    publishResult: text("publishResult").notNull(),
    createdAt: timestamp("createdAt").defaultNow().notNull(),
    updatedAt: timestamp("updatedAt").defaultNow().onUpdateNow().notNull(),
  },
  table => [
    index("content_items_user_status_idx").on(table.userId, table.status),
    index("content_items_schedule_idx").on(table.userId, table.scheduledAt),
  ],
);

/** Manual post-publication metrics used for operational review. */
export const performanceMetrics = mysqlTable(
  "performance_metrics",
  {
    id: int("id").autoincrement().primaryKey(),
    userId: int("userId").notNull(),
    contentId: int("contentId").notNull(),
    impressions: int("impressions").notNull().default(0),
    likes: int("likes").notNull().default(0),
    comments: int("comments").notNull().default(0),
    collects: int("collects").notNull().default(0),
    shares: int("shares").notNull().default(0),
    followersGained: int("followersGained").notNull().default(0),
    recordedAt: timestamp("recordedAt").defaultNow().notNull(),
    updatedAt: timestamp("updatedAt").defaultNow().onUpdateNow().notNull(),
  },
  table => [
    uniqueIndex("performance_metrics_content_unique").on(table.contentId),
    index("performance_metrics_user_idx").on(table.userId),
  ],
);

export type User = typeof users.$inferSelect;
export type InsertUser = typeof users.$inferInsert;
export type ContentStatus = (typeof contentStatusValues)[number];
export type ContentType = (typeof contentTypeValues)[number];
