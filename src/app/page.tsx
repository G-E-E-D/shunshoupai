/* eslint-disable react-hooks/set-state-in-effect -- restore browser drafts and fetch authenticated state after hydration */
"use client";

import { useCallback, useEffect, useRef, useState, type FormEvent, type ReactNode } from "react";
import { ArrowRight, ArrowUpRight, Bell, CalendarDays, CalendarCheck2, Check, CheckCheck, ChevronLeft, ChevronRight, Circle, Clock3, Cloud, CloudOff, Coffee, Edit3, HelpCircle, LayoutDashboard, LoaderCircle, LogOut, Menu, Plus, RefreshCw, Search, Settings2, ShieldCheck, Sparkles, Trash2, WandSparkles, X } from "lucide-react";
import type { EventDraft, ParseResult, ScheduleEvent, SessionInfo } from "@/lib/types";
import { api, ApiError, clockTime, dayLabel, errorMessage, localInput, requestId, shanghaiDay, shiftDay } from "@/lib/client";

type View = "day" | "week" | "completed";
type PendingCommit = { request_id: string; events: EventDraft[] };
type Notice = { text: string; kind: "success" | "error" | "info" };
const EXAMPLE = "明天下午三点到四点面试，今天晚上七点到八点改简历，八点到九点准备面试。";
const EMPTY_SESSION: SessionInfo = { user: null, mode: "local", ai_provider: "rules" };

function Brand({ small = false }: { small?: boolean }) {
  return <div className={`brand ${small ? "brand-small" : ""}`}><span className="brand-icon"><CalendarCheck2 size={22} strokeWidth={2.2} /></span><span>顺手排<span className="brand-dot">.</span></span></div>;
}

function Modal({ title, subtitle, children, close }: { title: string; subtitle?: string; children: ReactNode; close: () => void }) {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const previous = document.activeElement as HTMLElement;
    const focusables = () => Array.from(ref.current?.querySelectorAll<HTMLElement>('button, input, select, textarea, a[href], [tabindex="0"]') || []).filter(el => !el.hasAttribute("disabled"));
    focusables().find(el => el.tagName === "INPUT")?.focus();
    function handle(event: KeyboardEvent) {
      if (event.key === "Escape") close();
      if (event.key !== "Tab") return;
      const elements = focusables();
      const first = elements[0], last = elements.at(-1);
      if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus(); }
      if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus(); }
    }
    document.addEventListener("keydown", handle);
    const oldOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => { document.removeEventListener("keydown", handle); document.body.style.overflow = oldOverflow; previous?.focus(); };
  }, [close]);
  return <div className="modal-backdrop" onMouseDown={e => { if (e.target === e.currentTarget) close(); }}><div ref={ref} className="modal" role="dialog" aria-modal="true" aria-label={title}><button className="icon-button modal-close" aria-label="关闭弹窗" onClick={close}><X size={20} /></button><h2>{title}</h2>{subtitle && <p className="modal-subtitle">{subtitle}</p>}{children}</div></div>;
}

function AuthModal({ close, onSuccess, mode }: { close: () => void; onSuccess: (session: SessionInfo) => void; mode: string }) {
  const [register, setRegister] = useState(false);
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");
  async function submit(e: FormEvent) {
    e.preventDefault(); setBusy(true); setError(""); setMessage("");
    try {
      const result = await api<SessionInfo & { message?: string }>(`/api/auth/${register ? "register" : "login"}`, { method: "POST", body: JSON.stringify({ email, password }) });
      if (result.user) { onSuccess(result); close(); }
      else setMessage(result.message || "注册成功，请检查邮箱完成验证后登录。");
    } catch (error) { setError(errorMessage(error)); }
    finally { setBusy(false); }
  }
  return <Modal title={register ? "给日程一个新家" : "欢迎回来"} subtitle="登录同一账号，在手机和电脑接着安排。" close={close}>
    <div className="auth-tabs"><button className={!register ? "active" : ""} onClick={() => { setRegister(false); setError(""); setMessage(""); }}>登录账号</button><button className={register ? "active" : ""} onClick={() => { setRegister(true); setError(""); setMessage(""); }}>注册账号</button></div>
    <form onSubmit={submit} className="form-stack"><label>邮箱<input type="email" autoComplete="email" placeholder="you@example.com" value={email} onChange={e => setEmail(e.target.value)} required maxLength={254} /></label><label>密码<input type="password" autoComplete={register ? "new-password" : "current-password"} placeholder="至少 8 个字符" minLength={8} maxLength={128} value={password} onChange={e => setPassword(e.target.value)} required /></label>{error && <div className="inline-error" role="alert">{error}</div>}{message && <div className="inline-info" role="status">{message}</div>}<button className="primary-button wide" disabled={busy}>{busy ? <LoaderCircle className="spin" size={17} /> : null}{register ? "创建账号" : "登录，继续安排"}<ArrowRight size={17} /></button></form><p className="auth-note"><ShieldCheck size={14} />{mode === "supabase" ? "由 Supabase 提供身份认证与数据隔离" : "本地服务账号 · 数据保存在当前服务端"}</p>
  </Modal>;
}

function EventEditor({ event, day, close, save, busy }: { event?: ScheduleEvent; day: string; close: () => void; save: (draft: EventDraft, status?: ScheduleEvent["status"]) => Promise<void>; busy: boolean }) {
  const [title, setTitle] = useState(event?.title || "");
  const [start, setStart] = useState(event ? localInput(event.start_at) : `${day}T09:00`);
  const [end, setEnd] = useState(event ? localInput(event.end_at) : `${day}T10:00`);
  const [error, setError] = useState("");
  async function submit(e: FormEvent) {
    e.preventDefault(); setError("");
    if (end <= start) { setError("结束时间需要晚于开始时间。"); return; }
    try { await save({ title: title.trim(), start_local: `${start}:00`.slice(0, 19), end_local: `${end}:00`.slice(0, 19), timezone: "Asia/Shanghai", time_source: "explicit", assumptions: [] }); }
    catch (error) { setError(errorMessage(error)); }
  }
  return <Modal title={event ? "调整一下安排" : "手动添加日程"} subtitle="时间采用上海时区（UTC+8），保存前会检查冲突。" close={close}><form className="form-stack" onSubmit={submit}><label>日程标题<input placeholder="这段时间想做什么？" value={title} onChange={e => setTitle(e.target.value)} required maxLength={100} /></label><label>开始时间<input type="datetime-local" value={start} onChange={e => setStart(e.target.value)} required /></label><label>结束时间<input type="datetime-local" value={end} onChange={e => setEnd(e.target.value)} required /></label>{error && <div role="alert" className="inline-error">{error}</div>}<div className="modal-actions"><button type="button" className="secondary-button" onClick={close}>取消</button><button className="primary-button" disabled={busy}>{busy ? <LoaderCircle className="spin" size={16} /> : <Check size={16} />}保存日程</button></div></form></Modal>;
}

export default function Home() {
  const [session, setSession] = useState<SessionInfo>(EMPTY_SESSION);
  const [ready, setReady] = useState(false);
  const [authOpen, setAuthOpen] = useState(false);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [helpOpen, setHelpOpen] = useState(false);
  const [mobileNav, setMobileNav] = useState(false);
  const [view, setView] = useState<View>("day");
  const [selectedDay, setSelectedDay] = useState(shanghaiDay());
  const [events, setEvents] = useState<ScheduleEvent[]>([]);
  const [syncing, setSyncing] = useState(false);
  const [lastSync, setLastSync] = useState<Date | null>(null);
  const [syncError, setSyncError] = useState("");
  const [online, setOnline] = useState(true);
  const [text, setText] = useState("");
  const [parsing, setParsing] = useState(false);
  const [parseResult, setParseResult] = useState<ParseResult | null>(null);
  const [parseError, setParseError] = useState("");
  const [saving, setSaving] = useState(false);
  const [pending, setPending] = useState<PendingCommit | null>(null);
  const [commitError, setCommitError] = useState("");
  const [uncertain, setUncertain] = useState(false);
  const [notice, setNotice] = useState<Notice | null>(null);
  const [editor, setEditor] = useState<ScheduleEvent | "new" | null>(null);
  const [deleteEvent, setDeleteEvent] = useState<ScheduleEvent | null>(null);
  const [query, setQuery] = useState("");
  const [remindersEnabled, setRemindersEnabled] = useState(false);
  const [reminders, setReminders] = useState<ScheduleEvent[]>([]);
  const sequence = useRef(0);
  const authEpoch = useRef(0);
  const actionLock = useRef(false);
  const userRef = useRef<string | null>(null);
  const today = shanghaiDay();
  const userId = session.user?.id;
  const draftKey = `shunshoupai-draft-${userId || "guest"}`;
  const pendingKey = `shunshoupai-pending-${userId || "guest"}`;
  const closeAuth = useCallback(() => setAuthOpen(false), []);
  const closeSettings = useCallback(() => setSettingsOpen(false), []);
  const closeHelp = useCallback(() => setHelpOpen(false), []);
  const closeEditor = useCallback(() => { if (!actionLock.current) setEditor(null); }, []);
  const closeDelete = useCallback(() => { if (!actionLock.current) setDeleteEvent(null); }, []);

  const refresh = useCallback(async (quiet = false) => {
    if (!userId) return;
    const requestNumber = ++sequence.current;
    if (!quiet) setSyncing(true);
    try {
      const from = `${shiftDay(selectedDay, -35)}T00:00:00+08:00`;
      const to = `${shiftDay(selectedDay, 56)}T00:00:00+08:00`;
      const result = await api<{ events: ScheduleEvent[] }>(`/api/events?${new URLSearchParams({ from, to })}`);
      if (requestNumber !== sequence.current || userRef.current !== userId) return;
      setEvents(result.events); setLastSync(new Date()); setSyncError("");
    } catch (error) {
      if (requestNumber !== sequence.current || userRef.current !== userId) return;
      setSyncError(errorMessage(error));
      if (error instanceof ApiError && error.status === 401) { setSession(s => ({ ...s, user: null })); setEvents([]); setNotice({ kind: "info", text: "登录已过期，请重新登录后继续。" }); }
    } finally { if (requestNumber === sequence.current) setSyncing(false); }
  }, [selectedDay, userId]);

  useEffect(() => {
    api<SessionInfo>("/api/auth/session").then(data => { userRef.current = data.user?.id || null; setSession(data); }).catch(error => setSyncError(errorMessage(error))).finally(() => setReady(true));
    setOnline(navigator.onLine);
  }, []);
  useEffect(() => {
    userRef.current = userId || null;
    sequence.current++;
    setEvents([]); setLastSync(null); setSyncing(false); setParseResult(null); setParseError(""); setCommitError(""); setPending(null); setUncertain(false); setQuery(""); setReminders([]);
    if (userId) {
      try {
        const savedText = localStorage.getItem(draftKey) || localStorage.getItem("shunshoupai-draft-guest") || "";
        setText(savedText);
        const saved = localStorage.getItem(pendingKey);
        if (saved) { const payload: PendingCommit = JSON.parse(saved); if (payload.request_id && Array.isArray(payload.events)) { setPending(payload); setUncertain(true); setParseResult({ events: payload.events, questions: [], warnings: [], needs_clarification: false, provider: session.ai_provider }); } }
        setRemindersEnabled(localStorage.getItem(`shunshoupai-reminders-${userId}`) === "true");
      } catch { /* storage can be unavailable in private browsers */ }
    } else { setText(""); }
  // Session changes reset account-scoped data; provider is configuration, not an account boundary.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [userId, draftKey, pendingKey]);
  useEffect(() => {
    void refresh();
    function regain() { if (document.visibilityState === "visible") void refresh(true); }
    function reconnect() { setOnline(true); void refresh(); }
    function disconnect() { setOnline(false); }
    window.addEventListener("focus", regain);
    document.addEventListener("visibilitychange", regain);
    window.addEventListener("online", reconnect);
    window.addEventListener("offline", disconnect);
    const timer = setInterval(() => { if (document.visibilityState === "visible" && navigator.onLine) void refresh(true); }, 8000);
    return () => { clearInterval(timer); window.removeEventListener("focus", regain); document.removeEventListener("visibilitychange", regain); window.removeEventListener("online", reconnect); window.removeEventListener("offline", disconnect); };
  }, [refresh]);
  useEffect(() => { if (!notice) return; const timer = setTimeout(() => setNotice(null), 5500); return () => clearTimeout(timer); }, [notice]);
  useEffect(() => {
    if (!remindersEnabled || !userId) { setReminders([]); return; }
    function checkReminders() {
      if (document.visibilityState !== "visible") return;
      const now = Date.now();
      const due = events.filter(event => event.status === "planned" && new Date(event.start_at).getTime() - now <= 5 * 60_000 && new Date(event.end_at).getTime() > now && shanghaiDay(new Date(event.start_at)) === shanghaiDay());
      setReminders(due.filter(event => { try { return !sessionStorage.getItem(`reminder-${userId}-${event.id}-${event.version}`); } catch { return true; } }));
    }
    checkReminders(); const timer = setInterval(checkReminders, 30000); return () => clearInterval(timer);
  }, [remindersEnabled, events, userId]);

  function updateText(value: string) { setText(value); try { localStorage.setItem(draftKey, value); } catch { /* input stays in state */ } }
  function requireAuth() { if (userId) return true; setAuthOpen(true); return false; }
  function onLogin(data: SessionInfo) { authEpoch.current++; userRef.current = data.user?.id || null; setSession(data); setNotice({ kind: "success", text: "登录成功，接着安排你的生活吧。" }); }
  async function logout() {
    try {
      await api("/api/auth/logout", { method: "POST", body: "{}" }); authEpoch.current++; userRef.current = null; sequence.current++; setSession(s => ({ ...s, user: null })); setEvents([]); setSettingsOpen(false); setEditor(null); setNotice({ kind: "info", text: "已退出登录。" });
    } catch (error) { setNotice({ kind: "error", text: errorMessage(error) }); }
  }
  async function parse() {
    if (!requireAuth() || parsing || !text.trim()) return;
    if (!online) { setParseError("当前离线，输入已保留。恢复联网后再整理，或先准备手动草稿。"); return; }
    const epoch = authEpoch.current;
    setParsing(true); setParseError(""); setParseResult(null); setCommitError("");
    try {
      const result = await api<ParseResult>("/api/ai/parse", { method: "POST", body: JSON.stringify({ text, timezone: "Asia/Shanghai" }) });
      if (epoch !== authEpoch.current) return;
      setParseResult(result); setPending(null); setUncertain(false);
    } catch (error) { if (epoch === authEpoch.current) setParseError(errorMessage(error)); }
    finally { setParsing(false); }
  }
  function editDraft(index: number, field: keyof EventDraft, value: string) {
    if (uncertain || !parseResult) return;
    setParseResult({ ...parseResult, events: parseResult.events.map((event, i) => i === index ? { ...event, [field]: field.endsWith("local") ? (value.length === 16 ? `${value}:00` : value) : value } : event) }); setPending(null); setCommitError("");
  }
  async function sendCommit(payload: PendingCommit) {
    return api<{ events: ScheduleEvent[]; replayed?: boolean }>("/api/events/commit", { method: "POST", body: JSON.stringify(payload) });
  }
  async function commitDrafts() {
    if (!parseResult?.events.length || actionLock.current || !requireAuth()) return;
    if (!online) { setCommitError("当前离线，尚未发出保存请求。草稿已保留，恢复联网后再保存。"); return; }
    const invalid = parseResult.events.find(event => !event.title.trim() || event.end_local <= event.start_local);
    if (invalid) { setCommitError("请填写标题，并确保每项的结束时间晚于开始时间。"); return; }
    const payload = pending || { request_id: requestId(), events: parseResult.events };
    actionLock.current = true; setSaving(true); setCommitError(""); setPending(payload);
    try { localStorage.setItem(pendingKey, JSON.stringify(payload)); } catch { /* network retry still retains key in memory */ }
    try {
      const result = await sendCommit(payload);
      setUncertain(false); setPending(null); setParseResult(null); updateText("");
      try { localStorage.removeItem(pendingKey); localStorage.removeItem("shunshoupai-draft-guest"); } catch { /* no-op */ }
      if (result.events[0]) { setSelectedDay(shanghaiDay(new Date(result.events[0].start_at))); setView("day"); }
      await refresh(); setNotice({ kind: "success", text: result.replayed ? "已确认上次保存成功，没有重复创建。" : `已保存 ${result.events.length} 项日程，其他设备会在 8 秒内刷新。` });
    } catch (error) {
      if (error instanceof ApiError && error.status < 500 && error.status !== 408) {
        setPending(null); setUncertain(false); try { localStorage.removeItem(pendingKey); } catch { /* no-op */ }
        setCommitError(errorMessage(error));
      } else { setUncertain(true); setCommitError("保存状态待确认：服务端可能已收到请求。请点击「核实并重试」，应用会沿用原请求编号，避免重复创建。"); }
    } finally { actionLock.current = false; setSaving(false); }
  }
  async function saveEditor(draft: EventDraft) {
    if (actionLock.current) return;
    if (!online) throw new Error("离线");
    actionLock.current = true; setSaving(true);
    try {
      if (editor && editor !== "new") await api(`/api/events/${editor.id}`, { method: "PATCH", body: JSON.stringify({ base_version: editor.version, title: draft.title, start_local: draft.start_local, end_local: draft.end_local }) });
      else {
        const payload = pending || { request_id: requestId(), events: [draft] };
        try { localStorage.setItem(pendingKey, JSON.stringify(payload)); } catch { /* no-op */ }
        try { await sendCommit(payload); localStorage.removeItem(pendingKey); }
        catch (error) {
          if (!(error instanceof ApiError) || error.status >= 500 || error.status === 408) {
            setPending(payload); setUncertain(true); setParseResult({ events: payload.events, questions: [], warnings: [], needs_clarification: false, provider: "rules" }); setCommitError("保存状态待确认，请使用「核实并重试」确认结果。"); setEditor(null);
          } else { localStorage.removeItem(pendingKey); }
          throw error;
        }
      }
      setEditor(null); setSelectedDay(draft.start_local.slice(0, 10)); setView("day"); await refresh(); setNotice({ kind: "success", text: "日程已保存。" });
    } catch (error) { if (error instanceof ApiError && error.status === 409) await refresh(); throw error; }
    finally { actionLock.current = false; setSaving(false); }
  }
  async function changeStatus(event: ScheduleEvent) {
    if (actionLock.current) return;
    if (!online) { setNotice({ kind: "error", text: "当前离线，未提交更改。" }); return; }
    actionLock.current = true; setSaving(true);
    try { await api(`/api/events/${event.id}`, { method: "PATCH", body: JSON.stringify({ base_version: event.version, status: event.status === "completed" ? "planned" : "completed" }) }); await refresh(); setNotice({ kind: "success", text: event.status === "completed" ? "已恢复为待办。" : "又完成一件事，做得不错！" }); }
    catch (error) { setNotice({ kind: "error", text: error instanceof ApiError ? error.message : "更改状态待确认，请刷新查看最新结果。" }); await refresh(); }
    finally { actionLock.current = false; setSaving(false); }
  }
  async function removeEvent() {
    if (!deleteEvent || actionLock.current) return;
    if (!online) { setNotice({ kind: "error", text: "当前离线，尚未删除。" }); return; }
    actionLock.current = true; setSaving(true);
    try { await api(`/api/events/${deleteEvent.id}`, { method: "DELETE", body: JSON.stringify({ base_version: deleteEvent.version }) }); setDeleteEvent(null); await refresh(); setNotice({ kind: "success", text: "日程已删除。" }); }
    catch (error) { setNotice({ kind: "error", text: error instanceof ApiError ? error.message : "删除状态待确认，请刷新查看。" }); await refresh(); }
    finally { actionLock.current = false; setSaving(false); }
  }
  function changeView(next: View) { setView(next); setMobileNav(false); }
  function addNew() { if (!requireAuth()) return; if (uncertain) { setNotice({ kind: "info", text: "请先核实上次保存的结果，再添加新安排。" }); return; } setEditor("new"); }
  const selectedEvents = events.filter(event => shanghaiDay(new Date(event.start_at)) === selectedDay);
  const completedCount = selectedEvents.filter(event => event.status === "completed").length;
  const visibleEvents = events.filter(event => {
    if (query && !event.title.toLowerCase().includes(query.toLowerCase())) return false;
    if (view === "completed") return event.status === "completed";
    const day = shanghaiDay(new Date(event.start_at));
    if (view === "week") return day >= selectedDay && day <= shiftDay(selectedDay, 6);
    return day === selectedDay;
  }).sort((a, b) => a.start_at.localeCompare(b.start_at));
  const weekStart = shiftDay(selectedDay, -((new Date(`${selectedDay}T12:00:00+08:00`).getUTCDay() + 6) % 7));
  const totalMinutes = selectedEvents.filter(e => e.status !== "completed").reduce((sum, e) => sum + (new Date(e.end_at).getTime() - new Date(e.start_at).getTime()) / 60000, 0);
  const title = view === "completed" ? "每一步，都算数" : selectedDay === today ? "今天，慢慢来，也来得及" : `${dayLabel(selectedDay)}，好好安排`;

  return <div className="app-shell">
    {mobileNav && <div className="nav-scrim" onClick={() => setMobileNav(false)} />}
    <aside className={`sidebar ${mobileNav ? "sidebar-open" : ""}`}>
      <Brand />
      <div className="workspace-label">我的个人空间 <span>PERSONAL</span></div>
      <nav className="main-nav" aria-label="主导航"><button className={view === "day" ? "nav-item active" : "nav-item"} onClick={() => changeView("day")}><LayoutDashboard size={18} />日程概览{view === "day" && <span className="nav-active-dot" />}</button><button className={view === "week" ? "nav-item active" : "nav-item"} onClick={() => changeView("week")}><CalendarDays size={18} />未来七天</button><button className={view === "completed" ? "nav-item active" : "nav-item"} onClick={() => changeView("completed")}><CheckCheck size={18} />已完成<span className="nav-count">{events.filter(e => e.status === "completed").length}</span></button></nav>
      <div className="sidebar-tip"><div className="tip-illustration"><Coffee size={28} strokeWidth={1.5} /><span>✦</span></div><strong>安排生活，也留点空白</strong><p>不必把每一分钟填满。<br />留一点时间，给自己。</p><span className="tip-line" /></div>
      <div className="sidebar-bottom"><button className="nav-item" onClick={() => setHelpOpen(true)}><HelpCircle size={18} />使用指南<ArrowUpRight size={14} className="push-right" /></button><button className="nav-item" onClick={() => setSettingsOpen(true)}><Settings2 size={18} />偏好与账号</button><div className="user-box"><span className="avatar">{session.user ? session.user.email.slice(0, 1).toUpperCase() : "S"}</span><div><strong>{session.user ? session.user.email.split("@")[0] : "你的日程，有处安放"}</strong><span>{session.user ? "个人账号" : "登录后开启跨端同步"}</span></div><button className="icon-button" aria-label={session.user ? "账号设置" : "登录账号"} onClick={() => session.user ? setSettingsOpen(true) : setAuthOpen(true)}>{session.user ? <Settings2 size={16} /> : <ArrowRight size={17} />}</button></div></div>
    </aside>
    <div className="workspace">
      <header className="topbar"><div className="topbar-left"><button className="icon-button mobile-menu" aria-label="打开导航" onClick={() => setMobileNav(v => !v)}><Menu size={21} /></button><span className="breadcrumb">我的工作台</span><span className="breadcrumb-separator">/</span><span>{view === "completed" ? "已完成" : view === "week" ? "未来七天" : "日程概览"}</span></div><div className="topbar-right"><span className={`sync-badge ${!online || syncError ? "is-offline" : ""}`} title={syncError || (lastSync ? `最后同步 ${clockTime(lastSync.toISOString())}，前台每 8 秒刷新` : "登录后同步日程")}>
        {!online ? <CloudOff size={14} /> : syncing ? <RefreshCw size={14} className="spin" /> : <Cloud size={14} />}<span>{!online ? "当前离线" : syncing ? "正在同步" : syncError ? "同步待恢复" : userId && lastSync ? "已同步" : "跨端接着用"}</span>{online && !syncError && <i />}</span><span className="topbar-divider" /><button className={`icon-button reminder-toggle ${remindersEnabled ? "enabled" : ""}`} aria-label="提醒设置" onClick={() => setSettingsOpen(true)}><Bell size={18} />{remindersEnabled && <i />}</button><button className="avatar avatar-small" title={session.user?.email || "登录账号"} aria-label={session.user ? "打开账号设置" : "登录账号"} onClick={() => session.user ? setSettingsOpen(true) : setAuthOpen(true)}>{session.user ? session.user.email.slice(0, 1).toUpperCase() : "S"}</button></div></header>
      <main>
        <section className="page-heading"><div><div className="eyebrow"><span />LESS CHAOS, MORE LIFE</div><h1>{title}<span className="heading-sparkle">✳</span></h1><p>把脑子里的安排说出来，剩下的，顺手排。</p></div><button className="secondary-button add-button" onClick={addNew}><Plus size={17} />手动添加</button></section>
        {(!online || syncError) && <div className="connection-banner" role="status"><CloudOff size={17} /><span>{!online ? "网络已断开。输入会保留，尚未联网的更改不会显示为已同步。" : syncError}</span><button onClick={() => void refresh()}>重新同步</button></div>}
        {reminders.length > 0 && <div className="reminder-banner" role="status"><Bell size={18} /><div><strong>这件事，别忘了</strong><span>{reminders.map(e => `${clockTime(e.start_at)} ${e.title}`).join(" · ")}</span></div><button className="icon-button" aria-label="知道了，关闭提醒" onClick={() => { for (const event of reminders) { try { sessionStorage.setItem(`reminder-${userId}-${event.id}-${event.version}`, "true"); } catch { /* no-op */ } } setReminders([]); }}><X size={18} /></button></div>}
        <div className="dashboard-grid">
          <section className="composer-column" aria-label="日程整理">
            <div className="composer-card"><div className="card-topline"><span className="section-icon"><Sparkles size={19} /></span><div><h2>想到什么，就写下来</h2><p>一段话，整理好你的大小事</p></div><span className="mini-label">{session.ai_provider === "model" ? "AI 助手" : "规则体验"}</span></div>
              <div className={`input-area ${parsing ? "is-parsing" : ""}`}><textarea aria-label="描述你的日程安排" placeholder={"比如：明天下午三点到四点面试，\n今天晚上七点到八点改简历……"} value={text} disabled={parsing || uncertain} onChange={e => updateText(e.target.value)} maxLength={2000} onKeyDown={e => { if ((e.ctrlKey || e.metaKey) && e.key === "Enter") { e.preventDefault(); void parse(); } }} /><div className="input-meta"><span><span className="tiny-dot" /> 支持明确时间的多项日程</span><span>{text.length}/2000</span></div></div>
              <div className="example-row"><span>没想好？</span><button onClick={() => { if (!uncertain) updateText(EXAMPLE); }} disabled={uncertain || parsing}><WandSparkles size={13} />试试这句话<ArrowUpRight size={12} /></button></div>
              <button className="primary-button parse-button" onClick={() => void parse()} disabled={parsing || !text.trim() || uncertain || !ready}>{parsing ? <LoaderCircle className="spin" size={18} /> : <Sparkles size={18} />}{parsing ? "正在把想法整理好…" : "帮我整理一下"}{!parsing && <span className="keyboard-hint">⌘ / Ctrl ↵</span>}</button>
              <p className="composer-footnote"><ShieldCheck size={13} />{session.ai_provider === "model" ? "只整理当前输入，确认前不会保存" : "未配置模型密钥 · 当前为有限规则解析"}</p>
              {parseError && <div className="inline-error" role="alert">{parseError}<button className="text-link" onClick={addNew}>改为手动填写 <ArrowRight size={13} /></button></div>}
            </div>
            {parseResult ? <div className="draft-section"><div className="draft-heading"><h3><span className="draft-dot" />{parseResult.events.length ? `${parseResult.events.length} 项草稿，等你确认` : "还需要一点信息"}</h3><span>{parseResult.provider === "model" ? "AI 解析" : "规则解析"}</span></div>
              {!!parseResult.questions.length && <div className="clarification" role="status"><HelpCircle size={17} /><div><strong>先确认一下</strong>{parseResult.questions.map((q, i) => <p key={i}>{q}</p>)}<small>请在上方补充信息，再次整理。</small></div></div>}
              {parseResult.warnings.map((warning, index) => <div className="draft-warning" key={index}>{warning}</div>)}
              {parseResult.events.map((draft, index) => <div className="draft-card" key={index}><div className="draft-title-row"><span className="draft-number">{String(index + 1).padStart(2, "0")}</span><input aria-label={`草稿 ${index + 1} 标题`} value={draft.title} maxLength={100} disabled={uncertain || saving} onChange={e => editDraft(index, "title", e.target.value)} /><button aria-label={`移除草稿 ${index + 1}`} className="icon-button" disabled={uncertain || saving} onClick={() => { setParseResult({ ...parseResult, events: parseResult.events.filter((_, i) => i !== index) }); setPending(null); }}><X size={15} /></button></div><div className="draft-time-fields"><label>开始<input type="datetime-local" aria-label={`草稿 ${index + 1} 开始时间`} value={draft.start_local.slice(0, 16)} disabled={uncertain || saving} onChange={e => editDraft(index, "start_local", e.target.value)} /></label><ArrowRight size={13} /><label>结束<input type="datetime-local" aria-label={`草稿 ${index + 1} 结束时间`} value={draft.end_local.slice(0, 16)} disabled={uncertain || saving} onChange={e => editDraft(index, "end_local", e.target.value)} /></label></div>{(draft.time_source !== "explicit" || draft.assumptions.length > 0) && <p className="assumption-note">{draft.time_source === "assumed" ? "含默认假设 · " : draft.time_source === "suggested" ? "建议时间 · " : ""}{draft.assumptions.join("；") || "请确认时间后保存"}</p>}</div>)}
              {commitError && <div className="inline-error" role="alert">{commitError}</div>}
              {!!parseResult.events.length && <div className="draft-actions"><button className="text-button" disabled={uncertain || saving} onClick={() => { setParseResult(null); setPending(null); setCommitError(""); }}>取消草稿</button><button className="primary-button" disabled={saving || (parseResult.needs_clarification && !uncertain)} onClick={() => void commitDrafts()}>{saving ? <LoaderCircle size={16} className="spin" /> : <Check size={16} />}{uncertain ? "核实并重试" : `确认保存 ${parseResult.events.length} 项`}</button></div>}
              <p className="draft-note">草稿尚未保存 · 上海时区 UTC+8</p>
            </div> : <div className="how-it-works"><div className="how-heading"><span className="little-sparkle">✦</span><span>让安排，简单一点</span></div><div className="how-step"><span>01</span><div><strong>随手写下</strong><p>不用想格式，像平时说话一样</p></div></div><div className="how-step"><span>02</span><div><strong>看一眼，改一改</strong><p>时间、标题都由你最后决定</p></div></div><div className="how-step"><span>03</span><div><strong>确认后，各端接着用</strong><p>同一账号，手机电脑共享日程</p></div></div><div className="device-note"><span className="device-dots"><i /><i /><i /></span><span>今天的安排，始终在手边</span></div></div>}
          </section>
          <section className="agenda-column" aria-label="已保存日程">
            <div className="agenda-card"><div className="agenda-heading"><div><span className="small-eyebrow">YOUR LITTLE PLANS</span><h2>{view === "completed" ? "完成的事" : view === "week" ? "接下来的一周" : "我的日程"}<span>{visibleEvents.length}</span></h2></div><div className="agenda-tools"><button className="icon-button" title="重新同步日程" aria-label="重新同步日程" disabled={syncing || !userId} onClick={() => void refresh()}><RefreshCw size={16} className={syncing ? "spin" : ""} /></button><button className="icon-button" title="添加日程" aria-label="添加日程" onClick={addNew}><Plus size={19} /></button></div></div>
              <div className="date-navigation"><div className="current-date"><CalendarDays size={17} /><strong>{dayLabel(selectedDay, true)}</strong>{selectedDay === today && <span>今天</span>}</div><div className="date-actions"><button className="today-button" onClick={() => setSelectedDay(today)}>回今天</button><button className="icon-button" aria-label="前一天" onClick={() => setSelectedDay(shiftDay(selectedDay, -1))}><ChevronLeft size={17} /></button><button className="icon-button" aria-label="后一天" onClick={() => setSelectedDay(shiftDay(selectedDay, 1))}><ChevronRight size={17} /></button></div></div>
              <div className="week-strip">{Array.from({ length: 7 }, (_, index) => { const day = shiftDay(weekStart, index); const count = events.filter(e => shanghaiDay(new Date(e.start_at)) === day).length; return <button key={day} className={`week-day ${day === selectedDay ? "selected" : ""} ${day === today ? "is-today" : ""}`} onClick={() => setSelectedDay(day)} aria-label={dayLabel(day, true)} aria-pressed={day === selectedDay}><span>{["一", "二", "三", "四", "五", "六", "日"][index]}</span><strong>{Number(day.slice(-2))}</strong><i className={count ? "has-events" : ""} /></button>; })}</div>
              <div className="agenda-filter"><div className="view-switch" aria-label="时间范围"><button className={view === "day" ? "active" : ""} onClick={() => setView("day")}>当天</button><button className={view === "week" ? "active" : ""} onClick={() => setView("week")}>七天</button><button className={view === "completed" ? "active" : ""} onClick={() => setView("completed")}>已完成</button></div><label className="search-box"><Search size={14} /><input aria-label="搜索已加载日程" placeholder="搜索日程" value={query} onChange={e => setQuery(e.target.value)} maxLength={100} /></label></div>
              <div className="agenda-list">
                {!ready || syncing && !lastSync ? <div className="empty-state"><LoaderCircle className="spin" size={26} /><p>正在取回你的安排…</p></div> : visibleEvents.length === 0 ? <div className="empty-state"><div className="empty-art"><span className="empty-orbit" /><CalendarCheck2 size={40} strokeWidth={1.3} /><span className="empty-star">✦</span><span className="empty-dot" /></div><h3>{query ? "暂时没找到这项安排" : view === "completed" ? "每完成一件事，就在这里留个脚印" : "时间留白，好事正要发生"}</h3><p>{query ? "试试其他关键词，或切换日期范围。" : session.user ? "写下第一件想做的事，给今天一个小小的开始。" : "先写下一段安排，登录后就能保存并跨端继续。"}</p><button className="empty-action" onClick={() => query ? setQuery("") : session.user ? addNew() : setAuthOpen(true)}>{query ? "清空搜索" : session.user ? "添加第一项日程" : "登录，开启我的日程"}<ArrowRight size={14} /></button></div> : visibleEvents.map((event, index) => {
                  const duration = Math.round((new Date(event.end_at).getTime() - new Date(event.start_at).getTime()) / 60000);
                  const completed = event.status === "completed";
                  return <article className={`event-row ${completed ? "event-completed" : ""}`} key={event.id}><div className="event-time"><strong>{clockTime(event.start_at)}</strong><span>{clockTime(event.end_at)}</span>{view !== "day" && <small>{dayLabel(shanghaiDay(new Date(event.start_at)))}</small>}</div><div className={`event-content event-color-${index % 3}`}><div className="event-main"><button className={`complete-button ${completed ? "is-complete" : ""}`} disabled={saving} aria-label={`${completed ? "恢复" : "完成"}日程：${event.title}`} onClick={() => void changeStatus(event)}>{completed ? <Check size={13} /> : <Circle size={17} />}</button><h3>{event.title}</h3></div><div className="event-details"><span><Clock3 size={12} />{duration} 分钟</span><span className="event-status">{completed ? "已完成" : "待进行"}</span><div className="event-row-actions"><button className="icon-button" disabled={saving} aria-label={`编辑日程：${event.title}`} onClick={() => setEditor(event)}><Edit3 size={14} /></button><button className="icon-button delete-button" disabled={saving} aria-label={`删除日程：${event.title}`} onClick={() => setDeleteEvent(event)}><Trash2 size={14} /></button></div></div></div></article>;
                })}
              </div>
              <div className="agenda-footer"><span><span className="green-dot" />{lastSync ? `最后同步 ${clockTime(lastSync.toISOString())}` : "等待你的第一项安排"}</span><span>上海时区 UTC+8</span></div>
            </div>
            <div className="day-summary"><div className="summary-icon"><CalendarCheck2 size={21} /></div><div><strong>{selectedEvents.length ? `今天的步调，由你掌握` : "不赶时间，也不辜负时间"}</strong><p>{selectedEvents.length ? `共 ${selectedEvents.length} 项安排 · 已完成 ${completedCount} 项 · 待办约 ${totalMinutes} 分钟` : "安排好重要的事，给生活留一点松弛感。"}</p></div><span className="summary-decoration">↗</span></div>
          </section>
        </div>
        <footer className="page-footer"><Brand small /><span>把想法排进生活，把时间还给自己。</span><span className="footer-mode">{session.mode === "supabase" ? "Supabase 云端模式" : "本地持久化模式"} <span>·</span> 前台 8 秒同步</span></footer>
      </main>
    </div>
    {notice && <div className={`toast toast-${notice.kind}`} role={notice.kind === "error" ? "alert" : "status"}>{notice.kind === "success" ? <Check size={18} /> : notice.kind === "error" ? <CloudOff size={18} /> : <Bell size={18} />}<span>{notice.text}</span><button className="icon-button" aria-label="关闭提示" onClick={() => setNotice(null)}><X size={15} /></button></div>}
    {authOpen && <AuthModal close={closeAuth} onSuccess={onLogin} mode={session.mode} />}
    {editor && <EventEditor event={editor === "new" ? undefined : editor} day={selectedDay} close={closeEditor} save={saveEditor} busy={saving} />}
    {deleteEvent && <Modal title="把这项安排移走？" subtitle={`「${deleteEvent.title}」删除后会从各端日程中消失。`} close={closeDelete}><div className="modal-actions"><button className="secondary-button" onClick={closeDelete} disabled={saving}>再想想</button><button className="primary-button danger-button" disabled={saving} onClick={() => void removeEvent()}>{saving ? <LoaderCircle className="spin" size={16} /> : <Trash2 size={16} />}确认删除</button></div></Modal>}
    {settingsOpen && <Modal title="照自己的步调来" subtitle="日程和账号，只属于你。" close={closeSettings}><div className="settings-list"><div><span>当前账号</span><strong>{session.user?.email || "尚未登录"}</strong></div><div><span>数据存储</span><strong>{session.mode === "supabase" ? "Supabase 云端数据库" : "当前服务端 SQLite 数据库"}</strong></div><div><span>解析方式</span><strong>{session.ai_provider === "model" ? "已连接模型 API" : "有限规则解析（未接模型）"}</strong></div><div><span>显示时区</span><strong>Asia/Shanghai · UTC+8</strong></div><div><label htmlFor="reminders">前台站内提醒<small>开始前 5 分钟及进行中提示</small></label><input id="reminders" type="checkbox" role="switch" checked={remindersEnabled} onChange={e => { const enabled = e.target.checked; setRemindersEnabled(enabled); try { localStorage.setItem(`shunshoupai-reminders-${userId}`, String(enabled)); } catch { /* no-op */ } }} disabled={!userId} /></div></div><p className="settings-note">提醒需要页面在前台活跃运行；锁屏、后台或关闭网页时，不提供系统级准点提醒。同一服务地址、同一账号可跨设备使用，日程在前台每 8 秒和回到页面时刷新。</p>{session.user ? <button className="secondary-button wide" onClick={() => void logout()}><LogOut size={16} />退出账号</button> : <button className="primary-button wide" onClick={() => { setSettingsOpen(false); setAuthOpen(true); }}>登录账号<ArrowRight size={16} /></button>}</Modal>}
    {helpOpen && <Modal title="从一句话开始" subtitle="三个小步骤，让安排有条不紊。" close={closeHelp}><div className="guide-step"><span>1</span><div><h3>写下安排</h3><p>输入明确的日期、上午/下午和起止时间，一次最多 5 项。例如“明天下午三点到四点面试”。</p></div></div><div className="guide-step"><span>2</span><div><h3>检查草稿</h3><p>整理结果还没保存。检查具体日期与时间，可以直接改标题或时间；有歧义时，补充信息后再整理。</p></div></div><div className="guide-step"><span>3</span><div><h3>确认，换端接着用</h3><p>确认后日程才会入库。两台设备访问同一个服务地址、登录同一账号，前台 8 秒内自动刷新。</p></div></div><div className="guide-boundaries"><strong>先把简单的事做好</strong><p>当前支持上海时区的单次日程。循环、全天、自动找空档、离线编辑和外部日历同步暂不支持。未配置模型时，使用明确标记的规则解析。</p></div><a className="repo-link" href="https://github.com/G-E-E-D/shunshoupai" target="_blank" rel="noreferrer">查看源码与运行说明<ArrowUpRight size={15} /></a></Modal>}
  </div>;
}
