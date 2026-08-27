import { z } from "zod";
import { contentStatusValues, contentTypeValues } from "../../drizzle/schema";
import * as db from "../db";
import { protectedProcedure, router } from "../_core/trpc";

const contentInput = z.object({
  themeId: z.number().int().positive().nullable(),
  themeName: z.string().trim().min(1).max(120),
  contentType: z.enum(contentTypeValues),
  status: z.enum(contentStatusValues),
  title: z.string().trim().min(1).max(180),
  brief: z.string().max(10000),
  body: z.string().max(30000),
  tags: z.string().max(1000),
  coverPoints: z.string().max(5000),
  assetNotes: z.string().max(5000),
  reviewNotes: z.string().max(5000),
});

const metricInput = z.object({
  impressions: z.number().int().min(0),
  likes: z.number().int().min(0),
  comments: z.number().int().min(0),
  collects: z.number().int().min(0),
  shares: z.number().int().min(0),
  followersGained: z.number().int().min(0),
});

export const contentRouter = router({
  overview: protectedProcedure.query(({ ctx }) => db.getOverview(ctx.user.id)),
  list: protectedProcedure.input(z.object({ status: z.enum(contentStatusValues).optional() }).optional()).query(({ ctx, input }) => db.listContent(ctx.user.id, input?.status)),
  create: protectedProcedure.input(contentInput).mutation(({ ctx, input }) => db.createContent(ctx.user.id, input)),
  update: protectedProcedure.input(z.object({ id: z.number().int().positive(), content: contentInput })).mutation(({ ctx, input }) => db.updateContent(ctx.user.id, input.id, input.content)),
  schedule: protectedProcedure.input(z.object({ id: z.number().int().positive(), scheduledAt: z.coerce.date() })).mutation(({ ctx, input }) => db.scheduleContent(ctx.user.id, input.id, input.scheduledAt)),
  markPublished: protectedProcedure.input(z.object({ id: z.number().int().positive(), publishResult: z.string().max(5000), publishedUrl: z.string().url().or(z.literal("")) })).mutation(({ ctx, input }) => db.markContentPublished(ctx.user.id, input.id, input.publishResult, input.publishedUrl)),
  delete: protectedProcedure.input(z.object({ id: z.number().int().positive() })).mutation(({ ctx, input }) => db.deleteContent(ctx.user.id, input.id)),
});

export const strategyRouter = router({
  get: protectedProcedure.query(({ ctx }) => db.getStrategyProfile(ctx.user.id)),
  save: protectedProcedure.input(z.object({ accountName: z.string().trim().min(1).max(120), positioning: z.string().trim().min(1).max(5000), targetAudience: z.string().trim().min(1).max(5000), corePromise: z.string().trim().min(1).max(5000), brandVoice: z.string().trim().min(1).max(5000) })).mutation(({ ctx, input }) => db.saveStrategyProfile(ctx.user.id, input)),
});

export const themesRouter = router({
  list: protectedProcedure.query(({ ctx }) => db.listThemes(ctx.user.id)),
  create: protectedProcedure.input(z.object({ name: z.string().trim().min(1).max(120), description: z.string().trim().min(1).max(5000), audienceNeed: z.string().trim().min(1).max(5000) })).mutation(({ ctx, input }) => db.createTheme(ctx.user.id, input)),
  delete: protectedProcedure.input(z.object({ id: z.number().int().positive() })).mutation(({ ctx, input }) => db.deleteTheme(ctx.user.id, input.id)),
});

export const analyticsRouter = router({
  list: protectedProcedure.query(({ ctx }) => db.listPublishedPerformance(ctx.user.id)),
  save: protectedProcedure.input(z.object({ contentId: z.number().int().positive(), metrics: metricInput })).mutation(({ ctx, input }) => db.savePerformanceMetrics(ctx.user.id, input.contentId, input.metrics)),
});
