import { cn } from "@/lib/utils";

const statusMap = {
  idea: { label: "选题池", className: "bg-[#F5EBE7] text-[#9A5641]" },
  draft: { label: "草稿", className: "bg-[#F2F0EC] text-[#6E6259]" },
  review: { label: "待审核", className: "bg-[#FFF3D8] text-[#9A681C]" },
  scheduled: { label: "已排期", className: "bg-[#E8F0EB] text-[#47745B]" },
  published: { label: "已发布", className: "bg-[#F3E9F2] text-[#8A547D]" },
} as const;

export type ContentStatusKey = keyof typeof statusMap;

export function StatusPill({ status, className }: { status: ContentStatusKey; className?: string }) {
  const item = statusMap[status];
  return <span className={cn("inline-flex items-center rounded-full px-2.5 py-1 text-xs font-medium tracking-wide", item.className, className)}>{item.label}</span>;
}

export const statusLabel = (status: ContentStatusKey) => statusMap[status].label;
