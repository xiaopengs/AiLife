import { useAuth } from "@/_core/hooks/useAuth";
import { Avatar, AvatarFallback } from "@/components/ui/avatar";
import { Button } from "@/components/ui/button";
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from "@/components/ui/dropdown-menu";
import { Sidebar, SidebarContent, SidebarFooter, SidebarHeader, SidebarInset, SidebarMenu, SidebarMenuButton, SidebarMenuItem, SidebarProvider, SidebarTrigger, useSidebar } from "@/components/ui/sidebar";
import { startLogin } from "@/const";
import { useIsMobile } from "@/hooks/useMobile";
import { BarChart3, CalendarDays, LayoutDashboard, Library, LogOut, PanelLeft, PenLine, Sparkles } from "lucide-react";
import { type CSSProperties, useEffect, useRef, useState } from "react";
import { useLocation } from "wouter";
import { DashboardLayoutSkeleton } from "./DashboardLayoutSkeleton";

const menuItems = [
  { icon: LayoutDashboard, label: "运营总览", path: "/" },
  { icon: Library, label: "Skill 内容库", path: "/library" },
  { icon: PenLine, label: "内容生产", path: "/workflow" },
  { icon: CalendarDays, label: "发布日历", path: "/calendar" },
  { icon: BarChart3, label: "运营复盘", path: "/analytics" },
];

const SIDEBAR_WIDTH_KEY = "skill-ops-sidebar-width";
const DEFAULT_WIDTH = 264;
const MIN_WIDTH = 220;
const MAX_WIDTH = 360;

export default function DashboardLayout({ children }: { children: React.ReactNode }) {
  const [sidebarWidth, setSidebarWidth] = useState(() => Number(localStorage.getItem(SIDEBAR_WIDTH_KEY)) || DEFAULT_WIDTH);
  const { loading, user } = useAuth();

  useEffect(() => localStorage.setItem(SIDEBAR_WIDTH_KEY, String(sidebarWidth)), [sidebarWidth]);

  if (loading) return <DashboardLayoutSkeleton />;
  if (!user) {
    return (
      <div className="min-h-screen bg-[#F4F2ED] px-5 py-8 text-[#302A28]">
        <div className="mx-auto flex min-h-[calc(100vh-4rem)] max-w-md flex-col items-center justify-center rounded-[2rem] border border-[#E9E1D9] bg-[#FFFDFC] p-10 text-center shadow-[0_24px_80px_rgba(68,46,37,0.08)]">
          <div className="mb-7 flex h-14 w-14 items-center justify-center rounded-2xl bg-[#E84A3B] text-white shadow-lg shadow-[#E84A3B]/20"><Sparkles className="h-6 w-6" /></div>
          <p className="font-serif text-sm tracking-[0.22em] text-[#D65043]">ATELIER / SKILL</p>
          <h1 className="mt-3 font-serif text-3xl leading-tight">让每一次创作<br />都有清晰的去处</h1>
          <p className="mt-4 max-w-sm text-sm leading-7 text-[#766C65]">登录后进入你的内容运营工作台，管理从选题到复盘的每一个关键节点。</p>
          <Button onClick={() => startLogin()} className="mt-8 h-11 w-full rounded-xl bg-[#E84A3B] text-white hover:bg-[#CB392D]">进入工作台</Button>
        </div>
      </div>
    );
  }

  return <SidebarProvider style={{ "--sidebar-width": `${sidebarWidth}px` } as CSSProperties}><DashboardLayoutContent setSidebarWidth={setSidebarWidth}>{children}</DashboardLayoutContent></SidebarProvider>;
}

function DashboardLayoutContent({ children, setSidebarWidth }: { children: React.ReactNode; setSidebarWidth: (width: number) => void }) {
  const { user, logout } = useAuth();
  const [location, setLocation] = useLocation();
  const { state, toggleSidebar } = useSidebar();
  const [isResizing, setIsResizing] = useState(false);
  const sidebarRef = useRef<HTMLDivElement>(null);
  const isMobile = useIsMobile();
  const isCollapsed = state === "collapsed";
  const activeMenuItem = menuItems.find(item => item.path === location);

  useEffect(() => {
    const resize = (event: MouseEvent) => {
      if (!isResizing || isCollapsed) return;
      const left = sidebarRef.current?.getBoundingClientRect().left ?? 0;
      const width = event.clientX - left;
      if (width >= MIN_WIDTH && width <= MAX_WIDTH) setSidebarWidth(width);
    };
    const stop = () => setIsResizing(false);
    if (isResizing) {
      document.addEventListener("mousemove", resize);
      document.addEventListener("mouseup", stop);
      document.body.style.cursor = "col-resize";
      document.body.style.userSelect = "none";
    }
    return () => {
      document.removeEventListener("mousemove", resize);
      document.removeEventListener("mouseup", stop);
      document.body.style.cursor = "";
      document.body.style.userSelect = "";
    };
  }, [isResizing, isCollapsed, setSidebarWidth]);

  return (
    <>
      <div ref={sidebarRef} className="relative">
        <Sidebar collapsible="icon" className="border-r border-[#333635] bg-[#242625] text-[#F6F1EA]" disableTransition={isResizing}>
          <SidebarHeader className="h-24 justify-center px-3">
            <div className="flex w-full items-center gap-3">
              <button onClick={toggleSidebar} className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl text-[#C5BBB1] transition-colors hover:bg-white/10 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#F07A6E]" aria-label="切换导航"><PanelLeft className="h-4 w-4" /></button>
              {!isCollapsed && <div className="min-w-0"><p className="font-serif text-[15px] font-semibold tracking-wide text-[#F8F2E9]">Skill Atelier</p><p className="mt-0.5 text-[10px] tracking-[0.18em] text-[#ED766A]">CONTENT OPERATIONS</p></div>}
            </div>
          </SidebarHeader>
          <SidebarContent className="gap-0 px-2">
            <p className="px-3 pb-2 text-[10px] font-medium tracking-[0.18em] text-[#9F9A92] group-data-[collapsible=icon]:hidden">WORKSPACE</p>
            <SidebarMenu>
              {menuItems.map(item => <SidebarMenuItem key={item.path}><SidebarMenuButton isActive={location === item.path} onClick={() => setLocation(item.path)} tooltip={item.label} className="h-11 rounded-xl px-3 text-[#D6D0C8] transition-all hover:bg-white/10 hover:text-white data-[active=true]:bg-[#E84A3B] data-[active=true]:font-medium data-[active=true]:text-white"><item.icon className="h-4 w-4" /><span>{item.label}</span></SidebarMenuButton></SidebarMenuItem>)}
            </SidebarMenu>
          </SidebarContent>
          <SidebarFooter className="p-3">
            {!isCollapsed && <div className="mb-3 rounded-xl bg-white/7 px-3 py-2.5"><p className="text-xs font-medium text-[#EEE6DC]">人工发布协同</p><p className="mt-1 text-[11px] leading-4 text-[#B7B0A8]">仅记录排期与发布结果</p></div>}
            <DropdownMenu><DropdownMenuTrigger asChild><button className="flex w-full items-center gap-3 rounded-xl px-1 py-1 text-left transition-colors hover:bg-white/10 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#F07A6E]"><Avatar className="h-9 w-9 border border-white/10 bg-[#3B3E3C]"><AvatarFallback className="bg-[#3B3E3C] text-xs font-medium text-[#F2BCB3]">{user?.name?.charAt(0).toUpperCase() || "S"}</AvatarFallback></Avatar><div className="min-w-0 flex-1 group-data-[collapsible=icon]:hidden"><p className="truncate text-sm font-medium text-[#F4EEE6]">{user?.name || "创作者"}</p><p className="mt-0.5 truncate text-xs text-[#AAA49B]">内容运营空间</p></div></button></DropdownMenuTrigger><DropdownMenuContent align="end" className="w-44"><DropdownMenuItem onClick={logout} className="cursor-pointer text-destructive focus:text-destructive"><LogOut className="mr-2 h-4 w-4" />退出登录</DropdownMenuItem></DropdownMenuContent></DropdownMenu>
          </SidebarFooter>
        </Sidebar>
        {!isCollapsed && <div className="absolute right-0 top-0 z-50 h-full w-1 cursor-col-resize transition-colors hover:bg-[#F07A6E]/40" onMouseDown={() => setIsResizing(true)} />}
      </div>
      <SidebarInset className="bg-[#F4F2ED]">
        {isMobile && <div className="sticky top-0 z-40 flex h-14 items-center gap-2 border-b border-[#E9E1D9] bg-[#FBFAF7]/95 px-3 backdrop-blur"><SidebarTrigger className="h-9 w-9 rounded-xl" /><span className="font-serif text-base text-[#3D3531]">{activeMenuItem?.label ?? "Skill Atelier"}</span></div>}
        <main className="min-h-screen p-4 sm:p-6 lg:p-8">
          <div className="mb-5 hidden items-center justify-between rounded-2xl border border-[#E6E0D9] bg-[#FBFAF7]/80 px-5 py-3 backdrop-blur lg:flex">
            <div className="flex items-center gap-3"><span className="h-2 w-2 rounded-full bg-[#E84A3B]" /><div><p className="text-xs font-medium tracking-[0.12em] text-[#A09288]">CURRENT DESK</p><p className="mt-0.5 text-sm font-semibold text-[#443A35]">{activeMenuItem?.label ?? "运营总览"}</p></div></div>
            <div className="flex items-center gap-3 text-xs"><span className="rounded-full bg-[#F1ECE5] px-3 py-1.5 font-medium text-[#776C63]">{new Date().toLocaleDateString("zh-CN", { year: "numeric", month: "long" })} 运营周期</span><span className="rounded-full bg-[#F8E6E2] px-3 py-1.5 font-medium text-[#A04B3E]">人工发布协同</span></div>
          </div>
          {children}
        </main>
      </SidebarInset>
    </>
  );
}
