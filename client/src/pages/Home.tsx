import { StatusPill, type ContentStatusKey, statusLabel } from "@/components/StatusPill";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import { trpc } from "@/lib/trpc";
import {
  ArrowUpRight,
  CalendarClock,
  CheckCircle2,
  ChevronRight,
  Eye,
  FilePenLine,
  Heart,
  Lightbulb,
  Send,
  Sparkles,
  TrendingUp,
  UsersRound,
} from "lucide-react";
import { Link } from "wouter";

const stageColors: Record<ContentStatusKey, string> = {
  idea: "bg-[#D77965]",
  draft: "bg-[#697A73]",
  review: "bg-[#D7A441]",
  scheduled: "bg-[#5F9A76]",
  published: "bg-[#A66F90]",
};

const stageSoftColors: Record<ContentStatusKey, string> = {
  idea: "bg-[#FFF0EB] text-[#A94F41]",
  draft: "bg-[#EAF0EC] text-[#48685A]",
  review: "bg-[#FFF6DD] text-[#946A18]",
  scheduled: "bg-[#E6F4EA] text-[#397454]",
  published: "bg-[#F5EAF2] text-[#815170]",
};

const formatNumber = (value: number) =>
  value >= 10000 ? `${(value / 10000).toFixed(value >= 100000 ? 0 : 1)}w` : value.toLocaleString("zh-CN");

function MetricCard({
  label,
  value,
  detail,
  icon: Icon,
  tone,
}: {
  label: string;
  value: string | number;
  detail: string;
  icon: typeof Lightbulb;
  tone: "terracotta" | "sage" | "amber" | "ink";
}) {
  const toneClass = {
    terracotta: "bg-[#FBE9E5] text-[#B64D41]",
    sage: "bg-[#E3F2E8] text-[#478360]",
    amber: "bg-[#FCF2D7] text-[#A8771C]",
    ink: "bg-[#E8EFEC] text-[#446858]",
  }[tone];

  return (
    <article className="group relative min-h-36 overflow-hidden rounded-2xl border border-white/80 bg-white p-4 shadow-[0_8px_28px_rgba(66,50,43,0.035)] transition-transform duration-200 hover:-translate-y-0.5">
      <div className="absolute -right-4 -bottom-8 h-24 w-24 rounded-full bg-current opacity-[0.035]" />
      <div className="flex items-start justify-between gap-3">
        <p className="text-xs font-semibold text-[#716E68]">{label}</p>
        <span className={cn("flex h-8 w-8 items-center justify-center rounded-xl", toneClass)}><Icon className="h-4 w-4" /></span>
      </div>
      <div className="mt-5 flex items-baseline gap-1.5"><strong className="font-serif text-[30px] leading-none tracking-tight text-[#302A28]">{value}</strong></div>
      <p className="mt-2 text-[11px] text-[#96918B]">{detail}</p>
      <div className={cn("mt-3 h-1 overflow-hidden rounded-full bg-[#F2EFEB]")}><span className={cn("block h-full rounded-full", tone === "terracotta" ? "w-[56%] bg-[#D77965]" : tone === "sage" ? "w-[72%] bg-[#5F9A76]" : tone === "amber" ? "w-[42%] bg-[#D7A441]" : "w-[65%] bg-[#697A73]")} /></div>
    </article>
  );
}

export default function Home() {
  const { data, isLoading, error } = trpc.content.overview.useQuery();
  const totalStages = data?.stageDistribution.reduce((total, stage) => total + stage.count, 0) ?? 0;
  const metrics = data?.metrics ?? { impressions: 0, engagements: 0, followersGained: 0, engagementRate: 0 };
  const stageDistribution = data?.stageDistribution ?? ["idea", "draft", "review", "scheduled", "published"].map(status => ({ status: status as ContentStatusKey, count: 0 }));

  const summaryCards = [
    { label: "选题储备", value: isLoading ? "—" : data?.ideaReserve ?? 0, detail: "等待进入内容生产", icon: Lightbulb, tone: "terracotta" as const },
    { label: "待审核", value: isLoading ? "—" : data?.pendingReview ?? 0, detail: "需要确认内容口径", icon: FilePenLine, tone: "amber" as const },
    { label: "待发布", value: isLoading ? "—" : data?.pendingPublish ?? 0, detail: "已进入人工排期", icon: Send, tone: "sage" as const },
    { label: "累计涨粉", value: isLoading ? "—" : formatNumber(metrics.followersGained), detail: "来自已登记的发布结果", icon: UsersRound, tone: "ink" as const },
  ];

  return (
    <div className="mx-auto max-w-[1440px] space-y-4 sm:space-y-5">
      <section className="overflow-hidden rounded-[22px] border border-[#ECE5DD] bg-[#FFFDFC] shadow-[0_16px_40px_rgba(73,53,44,0.035)]">
        <div className="relative grid gap-6 px-5 py-6 sm:px-7 sm:py-7 lg:grid-cols-[1fr_auto] lg:items-end">
          <div className="pointer-events-none absolute right-[-120px] top-[-105px] h-64 w-64 rounded-full bg-[#EFC8B6]/30 blur-3xl" />
          <div className="pointer-events-none absolute bottom-[-100px] right-[13%] h-48 w-48 rounded-full bg-[#D7B355]/10 blur-3xl" />
          <div className="relative">
            <p className="flex items-center gap-2 text-[10px] font-bold tracking-[0.16em] text-[#A06C5D]"><span className="h-1.5 w-1.5 rounded-full bg-[#5F9A76] shadow-[0_0_0_4px_rgba(95,154,118,0.12)]" />OPERATION DESK / 本周</p>
            <h1 className="mt-3 max-w-3xl font-serif text-3xl leading-tight tracking-[-0.04em] text-[#332C29] sm:text-[34px]">让每一次创作，都沉淀为可复用的增长线索。</h1>
            <p className="mt-3 max-w-2xl text-sm leading-6 text-[#817971]">从选题、打磨到人工发布与复盘，今天的优先事项已经整理在这里。</p>
          </div>
          <div className="relative flex flex-wrap items-center gap-2 lg:justify-end">
            <span className="rounded-lg border border-[#E8E1D9] bg-[#FFFCF8] px-3 py-2 text-[11px] font-medium text-[#746B64]">运营总览</span>
            <Link href="/library"><Button className="h-9 rounded-lg bg-[#413531] px-3.5 text-xs text-white shadow-sm hover:bg-[#5B4640]">新建选题 <ArrowUpRight className="ml-1.5 h-3.5 w-3.5" /></Button></Link>
          </div>
        </div>
        <div className="grid border-t border-[#EEE8E1] bg-[#FCFAF7] px-5 py-3 sm:px-7 sm:grid-cols-3">
          <div className="flex items-center gap-2 text-[11px] text-[#827A73]"><CheckCircle2 className="h-3.5 w-3.5 text-[#5B9772]" />内容与指标按登录身份隔离保存</div>
          <div className="mt-2 flex items-center gap-2 text-[11px] text-[#827A73] sm:mt-0"><CalendarClock className="h-3.5 w-3.5 text-[#B58631]" />发布日历仅用于人工协同</div>
          <div className="mt-2 flex items-center gap-2 text-[11px] text-[#827A73] sm:mt-0 sm:justify-end"><Sparkles className="h-3.5 w-3.5 text-[#A96B85]" />从一个待推进的选题开始</div>
        </div>
      </section>

      {error && <div className="rounded-xl border border-[#F0CBC3] bg-[#FFF5F2] px-4 py-3 text-sm text-[#984C3F]">暂时无法载入运营数据，请稍后刷新。</div>}

      <section className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4" aria-label="运营关键指标">
        {summaryCards.map(card => <MetricCard key={card.label} {...card} />)}
      </section>

      <section className="grid gap-4 xl:grid-cols-[minmax(0,1fr)_332px]">
        <div className="space-y-4">
          <section className="rounded-[18px] border border-[#EAE3DC] bg-[#FFFDFC] p-5 shadow-[0_8px_28px_rgba(66,50,43,0.025)] sm:p-6">
            <div className="flex flex-col justify-between gap-3 sm:flex-row sm:items-start">
              <div><p className="text-[10px] font-bold tracking-[0.15em] text-[#A39A91]">CONTENT PIPELINE</p><h2 className="mt-1 font-serif text-xl tracking-[-0.035em] text-[#3B322E]">内容节奏，一眼看清</h2></div>
              <Link href="/workflow" className="inline-flex items-center text-xs font-semibold text-[#935A4B] transition-colors hover:text-[#713A30]">进入内容生产 <ArrowUpRight className="ml-1 h-3.5 w-3.5" /></Link>
            </div>

            <div className="mt-7 overflow-hidden rounded-full bg-[#F0ECE6] p-1.5">
              <div className="flex h-3 overflow-hidden rounded-full">
                {stageDistribution.map(stage => <div key={stage.status} className={cn("h-full transition-all", stageColors[stage.status as ContentStatusKey])} style={{ width: `${totalStages ? (stage.count / totalStages) * 100 : 0}%` }} />)}
              </div>
            </div>
            <div className="mt-5 grid grid-cols-2 gap-x-3 gap-y-4 sm:grid-cols-5">
              {stageDistribution.map(stage => {
                const status = stage.status as ContentStatusKey;
                return <div key={status} className="rounded-xl border border-transparent px-2 py-1.5 transition-colors hover:border-[#EEE7DF]"><div className="flex items-center gap-2"><span className={cn("h-2 w-2 rounded-full", stageColors[status])} /><span className="text-[11px] text-[#827A73]">{statusLabel(status)}</span></div><p className="mt-2 font-serif text-2xl leading-none tracking-tight text-[#453A35]">{isLoading ? "—" : stage.count}</p></div>;
              })}
            </div>

            <div className="mt-6 rounded-xl border border-[#EDE4DC] bg-[#FCF7F3] px-4 py-3 sm:flex sm:items-center sm:justify-between">
              <div className="flex items-start gap-2.5"><span className="mt-0.5 flex h-6 w-6 shrink-0 items-center justify-center rounded-lg bg-[#F4DFD7] text-[#AA5949]"><Lightbulb className="h-3.5 w-3.5" /></span><p className="text-[11px] leading-5 text-[#7C6F68]"><strong className="font-semibold text-[#604941]">运营建议：</strong>优先将已确认的选题推进到草稿，再集中处理待审核内容，让发布节奏连续而不过载。</p></div>
              <Link href="/library" className="mt-2 inline-flex shrink-0 items-center text-[11px] font-semibold text-[#9A5B4C] sm:mt-0">查看选题池 <ChevronRight className="h-3.5 w-3.5" /></Link>
            </div>
          </section>

          <section className="rounded-[18px] border border-[#EAE3DC] bg-[#FFFDFC] shadow-[0_8px_28px_rgba(66,50,43,0.025)]">
            <div className="flex items-center justify-between border-b border-[#F0EBE5] px-5 py-4 sm:px-6">
              <div><p className="text-[10px] font-bold tracking-[0.15em] text-[#A39A91]">NEXT TO ACT</p><h2 className="mt-1 font-serif text-xl tracking-[-0.035em] text-[#3B322E]">下一步需要关注</h2></div>
              <Link href="/workflow" className="rounded-lg border border-[#E9E1D9] p-2 text-[#917A70] transition-colors hover:bg-[#F9F5F0] hover:text-[#875043]" aria-label="查看内容生产"><FilePenLine className="h-4 w-4" /></Link>
            </div>
            <div className="divide-y divide-[#F0EBE5] px-5 sm:px-6">
              {data?.attentionItems.length ? data.attentionItems.map(item => {
                const status = item.status as ContentStatusKey;
                return <Link href="/workflow" key={item.id} className="group flex items-center gap-3 py-3.5 transition-colors hover:bg-[#FFFCF9]">
                  <span className={cn("hidden h-9 w-1 rounded-full sm:block", stageColors[status])} />
                  <StatusPill status={status} />
                  <div className="min-w-0 flex-1"><p className="truncate text-sm font-semibold text-[#514640] group-hover:text-[#8B4D40]">{item.title}</p><p className="mt-1 text-[11px] text-[#978B82]">{item.themeName}{item.scheduledAt ? ` · ${new Date(item.scheduledAt).toLocaleDateString("zh-CN", { month: "long", day: "numeric" })}` : " · 等待处理"}</p></div>
                  <ChevronRight className="h-4 w-4 shrink-0 text-[#C0B7B0] transition-transform group-hover:translate-x-0.5" />
                </Link>;
              }) : <div className="py-9 text-center"><p className="text-sm font-medium text-[#77716B]">暂时没有待处理事项</p><p className="mt-1 text-xs text-[#A09790]">先从内容库中创建一个选题吧。</p><Link href="/library" className="mt-3 inline-flex items-center text-xs font-semibold text-[#97594B]">前往内容库 <ArrowUpRight className="ml-1 h-3.5 w-3.5" /></Link></div>}
            </div>
          </section>
        </div>

        <aside className="space-y-4">
          <section className="overflow-hidden rounded-[18px] border border-[#E5D8CF] bg-[#FFFDFC] shadow-[0_8px_28px_rgba(66,50,43,0.025)]">
            <div className="bg-[#F9F0EB] px-5 py-5"><div className="flex items-start justify-between"><div><p className="text-[10px] font-bold tracking-[0.15em] text-[#AA7161]">REVIEW SNAPSHOT</p><h2 className="mt-1 font-serif text-xl tracking-[-0.035em] text-[#493932]">复盘信号</h2></div><span className="flex h-8 w-8 items-center justify-center rounded-xl bg-white/70 text-[#B56955]"><TrendingUp className="h-4 w-4" /></span></div><p className="mt-3 text-[11px] leading-5 text-[#896D62]">仅汇总已登记的人工发布结果，用于判断下一轮内容方向。</p></div>
            <div className="grid grid-cols-2 gap-px bg-[#EEE6DF]">
              <div className="bg-white px-5 py-4"><p className="text-[10px] text-[#978C83]">累计曝光</p><strong className="mt-1 block font-serif text-2xl tracking-tight text-[#463A34]">{isLoading ? "—" : formatNumber(metrics.impressions)}</strong></div>
              <div className="bg-white px-5 py-4"><p className="text-[10px] text-[#978C83]">总互动</p><strong className="mt-1 block font-serif text-2xl tracking-tight text-[#463A34]">{isLoading ? "—" : formatNumber(metrics.engagements)}</strong></div>
              <div className="bg-white px-5 py-4"><p className="text-[10px] text-[#978C83]">互动率</p><strong className="mt-1 block font-serif text-2xl tracking-tight text-[#463A34]">{isLoading ? "—" : `${metrics.engagementRate}%`}</strong></div>
              <div className="bg-white px-5 py-4"><p className="text-[10px] text-[#978C83]">新增粉丝</p><strong className="mt-1 block font-serif text-2xl tracking-tight text-[#463A34]">{isLoading ? "—" : formatNumber(metrics.followersGained)}</strong></div>
            </div>
            <Link href="/analytics" className="flex items-center justify-between px-5 py-3.5 text-xs font-semibold text-[#8F584A] transition-colors hover:bg-[#FFFBF8]">进入运营复盘 <ArrowUpRight className="h-3.5 w-3.5" /></Link>
          </section>

          <section className="rounded-[18px] border border-[#E7E2DC] bg-[#30312E] p-5 text-[#FFF9F5] shadow-[0_12px_28px_rgba(46,42,39,0.12)]">
            <div className="flex items-start gap-3"><span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-xl bg-[#EE7868] text-white"><CalendarClock className="h-4 w-4" /></span><div><p className="text-[10px] font-bold tracking-[0.15em] text-[#E9AC9F]">MANUAL PUBLISH</p><h2 className="mt-1 font-serif text-lg tracking-[-0.03em]">发布，仍由你掌握。</h2></div></div>
            <p className="mt-4 text-[12px] leading-6 text-[#D9D2CC]">在发布日历中核对排期、完成发布前检查，并在人工发布完成后登记结果。</p>
            <Link href="/calendar" className="mt-5 inline-flex items-center rounded-lg bg-[#FFF8F2] px-3 py-2 text-xs font-bold text-[#463630] transition-colors hover:bg-white">前往发布协同 <ArrowUpRight className="ml-1.5 h-3.5 w-3.5" /></Link>
            <p className="mt-4 flex items-center gap-1.5 text-[10px] text-[#AFA7A1]"><CheckCircle2 className="h-3.5 w-3.5 text-[#7FB691]" />不通过非官方方式操作账号</p>
          </section>

          <section className="rounded-[18px] border border-[#EAE3DC] bg-[#FFFDFC] p-4 shadow-[0_8px_28px_rgba(66,50,43,0.025)]">
            <div className="flex gap-3"><span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-[#EEE8F8] text-[#7564A5]"><Heart className="h-4 w-4" /></span><div><p className="text-xs font-semibold text-[#514640]">建立自己的内容资产</p><p className="mt-1 text-[11px] leading-5 text-[#938A82]">将有效的选题、审核经验和发布结果留在一个可回溯的工作流中。</p></div></div>
          </section>
        </aside>
      </section>
    </div>
  );
}
