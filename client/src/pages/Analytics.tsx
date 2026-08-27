import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { trpc } from "@/lib/trpc";
import { BarChart3, Save, TrendingUp, Users } from "lucide-react";
import { useMemo, useState } from "react";
import { toast } from "sonner";

type MetricKey = "impressions" | "likes" | "comments" | "collects" | "shares" | "followersGained";
const metricKeys: MetricKey[] = ["impressions", "likes", "comments", "collects", "shares", "followersGained"];
const labels: Record<MetricKey, string> = { impressions: "曝光", likes: "赞", comments: "评", collects: "藏", shares: "分享", followersGained: "涨粉" };
const asNumber = (value: number | null) => value ?? 0;

export default function Analytics() {
  const utils = trpc.useUtils();
  const query = trpc.analytics.list.useQuery();
  const save = trpc.analytics.save.useMutation({ onSuccess: () => { utils.analytics.list.invalidate(); utils.content.overview.invalidate(); toast.success("复盘数据已保存"); }, onError: () => toast.error("请输入有效的非负整数") });
  const [editing, setEditing] = useState<Record<number, Record<MetricKey, number>>>({});
  const rows = query.data ?? [];
  const totals = useMemo(() => rows.reduce((sum, row) => ({ impressions: sum.impressions + asNumber(row.impressions), engagements: sum.engagements + asNumber(row.likes) + asNumber(row.comments) + asNumber(row.collects) + asNumber(row.shares), followers: sum.followers + asNumber(row.followersGained) }), { impressions: 0, engagements: 0, followers: 0 }), [rows]);
  const groups = useMemo(() => Object.values(rows.reduce<Record<string, { label: string; impressions: number; engagements: number; count: number }>>((acc, row) => { const key = `${row.themeName} · ${row.contentType}`; const current = acc[key] ?? { label: key, impressions: 0, engagements: 0, count: 0 }; current.impressions += asNumber(row.impressions); current.engagements += asNumber(row.likes) + asNumber(row.comments) + asNumber(row.collects) + asNumber(row.shares); current.count += 1; acc[key] = current; return acc; }, {})).sort((a, b) => b.engagements - a.engagements), [rows]);
  const inputValue = (row: typeof rows[number], key: MetricKey) => editing[row.contentId]?.[key] ?? asNumber(row[key]);
  const patch = (id: number, key: MetricKey, value: number) => setEditing(current => {
    const row = rows.find(item => item.contentId === id);
    const baseline: Record<MetricKey, number> = {
      impressions: asNumber(row?.impressions ?? null),
      likes: asNumber(row?.likes ?? null),
      comments: asNumber(row?.comments ?? null),
      collects: asNumber(row?.collects ?? null),
      shares: asNumber(row?.shares ?? null),
      followersGained: asNumber(row?.followersGained ?? null),
    };
    return { ...current, [id]: { ...baseline, ...(current[id] ?? {}), [key]: value } };
  });
  const submit = (id: number) => { const row = rows.find(item => item.contentId === id); if (!row) return; save.mutate({ contentId: id, metrics: { impressions: inputValue(row, "impressions"), likes: inputValue(row, "likes"), comments: inputValue(row, "comments"), collects: inputValue(row, "collects"), shares: inputValue(row, "shares"), followersGained: inputValue(row, "followersGained") } }); };
  const engagementRate = totals.impressions ? ((totals.engagements / totals.impressions) * 100).toFixed(1) : "0.0";
  const maxEngagement = Math.max(...groups.map(group => group.engagements), 1);

  if (query.isLoading) return <AnalyticsState title="正在汇总运营复盘" description="正在读取已发布内容及其手动录入的表现数据。" />;
  if (query.error) return <AnalyticsState title="运营复盘暂时未能载入" description="请检查网络后重试；已经保存的复盘数据仍然保留。" retry={() => query.refetch()} />;

  return <div className="mx-auto max-w-7xl space-y-6"><header><p className="text-xs font-medium tracking-[0.18em] text-[#A56F61]">OPERATIONS REVIEW</p><h1 className="mt-2 font-serif text-3xl text-[#403530]">运营复盘</h1><p className="mt-2 text-sm text-[#82776E]">录入真实的发布表现，理解什么内容值得在下一轮继续投入。</p></header>
    <section className="grid gap-4 md:grid-cols-3">{[{ label: "累计曝光", value: totals.impressions.toLocaleString("zh-CN"), icon: TrendingUp, color: "bg-[#F6E9E2] text-[#A7624E]" }, { label: "总互动", value: totals.engagements.toLocaleString("zh-CN"), icon: BarChart3, color: "bg-[#F8F0DC] text-[#9F7625]" }, { label: "累计涨粉", value: totals.followers.toLocaleString("zh-CN"), icon: Users, color: "bg-[#E9F1EB] text-[#557D65]" }].map(item => <div key={item.label} className="rounded-2xl border border-[#E9E1D9] bg-[#FFFDFC] p-5"><div className="flex items-start justify-between"><div><p className="text-sm text-[#786D65]">{item.label}</p><p className="mt-3 font-serif text-3xl text-[#403530]">{item.value}</p></div><span className={`flex h-9 w-9 items-center justify-center rounded-xl ${item.color}`}><item.icon className="h-4 w-4" /></span></div></div>)}</section>
    <section className="grid gap-6 xl:grid-cols-[0.9fr_1.1fr]"><div className="rounded-[1.5rem] border border-[#E9E1D9] bg-[#FFFDFC] p-5 sm:p-6"><p className="text-xs font-medium tracking-[0.15em] text-[#A49A91]">PATTERN SIGNAL</p><h2 className="mt-1 font-serif text-xl text-[#413632]">主题与类型表现</h2><p className="mt-2 text-sm text-[#887D74]">当前整体互动率：<strong className="font-serif text-lg text-[#695045]">{engagementRate}%</strong></p><div className="mt-6 space-y-4">{groups.length ? groups.map(group => <div key={group.label}><div className="flex items-end justify-between gap-3"><div><p className="text-sm font-medium text-[#5A4E47]">{group.label}</p><p className="mt-0.5 text-xs text-[#968B82]">{group.count} 篇内容 · {group.engagements.toLocaleString("zh-CN")} 次互动</p></div><span className="text-xs text-[#89675B]">{group.impressions.toLocaleString("zh-CN")} 曝光</span></div><div className="mt-2 h-2 overflow-hidden rounded-full bg-[#F1ECE6]"><div className="h-full rounded-full bg-[#B87461]" style={{ width: `${(group.engagements / maxEngagement) * 100}%` }} /></div></div>) : <div className="rounded-xl bg-[#F8F4EF] p-5 text-sm leading-6 text-[#958A81]">还没有已发布内容。完成一次人工发布登记后，就可以在这里录入数据。</div>}</div></div>
      <div className="rounded-[1.5rem] border border-[#E9E1D9] bg-[#FFFDFC] p-5 sm:p-6"><p className="text-xs font-medium tracking-[0.15em] text-[#A49A91]">NEXT ROUND</p><h2 className="mt-1 font-serif text-xl text-[#413632]">把复盘带回选题</h2><div className="mt-5 rounded-2xl bg-[#F7F1ED] p-5 text-sm leading-7 text-[#725E54]">优先观察能够带来收藏、评论或持续涨粉的主题与内容结构。数据不只用于衡量结果，也用于校准下一轮内容 Brief 中的读者问题、表达角度与素材配置。</div><div className="mt-4 rounded-xl border border-dashed border-[#E3CEC3] p-3 text-xs leading-5 text-[#917A6F]">所有数据均由你主动录入，平台不通过非官方方式读取小红书账号数据。</div></div></section>
    <section className="overflow-hidden rounded-[1.5rem] border border-[#E9E1D9] bg-[#FFFDFC]"><div className="border-b border-[#EEE8E2] px-5 py-5 sm:px-6"><p className="text-xs font-medium tracking-[0.15em] text-[#A49A91]">MANUAL METRICS ENTRY</p><h2 className="mt-1 font-serif text-xl text-[#413632]">已发布笔记数据</h2></div><div className="overflow-x-auto"><table className="w-full min-w-[900px] text-left"><thead className="bg-[#FBF8F5]"><tr>{["笔记", "主题 / 类型", ...metricKeys.map(key => labels[key]), ""].map(header => <th key={header} className="px-4 py-3 text-xs font-medium text-[#8C8178]">{header}</th>)}</tr></thead><tbody className="divide-y divide-[#F0EAE4]">{rows.length ? rows.map(row => <tr key={row.contentId}><td className="max-w-56 px-4 py-4"><p className="truncate text-sm font-medium text-[#514640]">{row.title}</p><p className="mt-1 text-xs text-[#9B9087]">{row.publishedAt ? new Date(row.publishedAt).toLocaleDateString("zh-CN") : "已发布"}</p></td><td className="px-4 py-4 text-xs text-[#7D7269]">{row.themeName} · {row.contentType}</td>{metricKeys.map(key => <td key={key} className="px-2 py-4"><Input type="number" min="0" value={inputValue(row, key)} onChange={event => patch(row.contentId, key, Math.max(0, Number(event.target.value) || 0))} className="h-8 w-20 border-[#E7DED6] bg-[#FFFEFC] px-2 text-xs" /></td>)}<td className="px-4 py-4"><Button onClick={() => submit(row.contentId)} disabled={save.isPending} size="sm" variant="outline" className="rounded-lg border-[#DCC8BD] text-[#8E5547] hover:bg-[#FBF3EF]"><Save className="mr-1.5 h-3.5 w-3.5" />保存</Button></td></tr>) : <tr><td colSpan={9} className="px-6 py-14 text-center text-sm text-[#988D84]">暂时没有已发布笔记。</td></tr>}</tbody></table></div></section>
  </div>;
}

function AnalyticsState({ title, description, retry }: { title: string; description: string; retry?: () => void }) { return <div className="mx-auto flex min-h-[60vh] max-w-7xl items-center justify-center"><div className="max-w-lg rounded-[1.75rem] border border-[#E9E1D9] bg-[#FFFDFC] p-9 text-center"><p className="text-xs font-medium tracking-[0.18em] text-[#A56F61]">OPERATIONS REVIEW</p><h1 className="mt-3 font-serif text-2xl text-[#443732]">{title}</h1><p className="mt-3 text-sm leading-6 text-[#8B8077]">{description}</p>{retry ? <Button onClick={retry} variant="outline" className="mt-6 rounded-xl border-[#DCC9BE] text-[#8E5547] hover:bg-[#FBF3EF]">重新载入</Button> : <div className="mx-auto mt-6 h-1.5 w-24 animate-pulse rounded-full bg-[#E8D2C9]" />}</div></div>; }
