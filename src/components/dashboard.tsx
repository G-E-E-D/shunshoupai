/* eslint-disable react-hooks/set-state-in-effect -- restore browser drafts and fetch authenticated state after hydration */
"use client";

import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type FormEvent,
  type ReactNode,
} from "react";
import {
  ArrowRight,
  ArrowUpRight,
  Bell,
  CalendarDays,
  CalendarCheck2,
  Check,
  CheckCheck,
  ChevronLeft,
  ChevronRight,
  Circle,
  Clock3,
  Cloud,
  CloudOff,
  Coffee,
  Edit3,
  HelpCircle,
  KeyRound,
  LayoutDashboard,
  LoaderCircle,
  Menu,
  Plus,
  RefreshCw,
  Search,
  Settings2,
  ShieldCheck,
  Sparkles,
  Trash2,
  WandSparkles,
  X,
} from "lucide-react";
import type {
  EventDraft,
  ParseResult,
  ScheduleEvent,
  SessionInfo,
  ModelSettingsInfo,
} from "@/lib/types";
import ModelSettingsPanel from "@/components/model-settings";
import DeviceConnections from "@/components/device-connections";
import ReplicaConflict from "@/components/replica-conflict";
import { loadEditorDraft, loadReviewDraft, saveLocalDraft } from "@/components/draft-storage";
import { commitSchema } from "@/lib/schemas";
import {
  api,
  ApiError,
  clockTime,
  dayLabel,
  errorMessage,
  localInput,
  moveEventStart,
  requestId,
  shanghaiDay,
  shiftDay,
  touchesWindow,
} from "@/lib/client";

type View = "day" | "week" | "completed";
type PendingCommit = { request_id: string; events: EventDraft[] };
type Notice = { text: string; kind: "success" | "error" | "info" };
const EXAMPLE =
  "明天下午三点到四点面试，今天晚上七点到八点改简历，八点到九点准备面试。";
const EMPTY_SESSION: SessionInfo = {
  user: null,
  mode: "local",
  ai_provider: "rules",
};

function takePairingTicket(): string | null {
  const fragment = window.location.hash;
  if (!fragment.startsWith("#pair=")) return null;
  window.history.replaceState(null, "", window.location.pathname + window.location.search);
  const token = fragment.slice(6);
  if (!/^[A-Za-z0-9_-]{43}$/.test(token))
    throw new ApiError("INVALID_PAIRING", "配对二维码格式无效，请在另一台设备重新生成。", 400);
  return token;
}

function hasReplicaConflict(event: ScheduleEvent | undefined): boolean {
  return (event?.replica_conflicts?.length ?? 0) > 1;
}

function browserPairingIdentity() {
  let id: string | null = null;
  try { id = localStorage.getItem("shunshoupai-browser-device-id"); } catch { /* Cookie still identifies this connection. */ }
  if (!id || !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(id)) {
    id = requestId();
    try { localStorage.setItem("shunshoupai-browser-device-id", id); } catch { /* Pairing can continue without local storage. */ }
  }
  return { device_id: id, device_name: "网页设备", platform: "web" };
}

function Brand({ small = false }: { small?: boolean }) {
  return (
    <div className={`brand ${small ? "brand-small" : ""}`}>
      <span className="brand-icon">
        <CalendarCheck2 size={22} strokeWidth={2.2} />
      </span>
      <span>
        顺手排<span className="brand-dot">.</span>
      </span>
    </div>
  );
}

function Modal({
  title,
  subtitle,
  children,
  close,
  wide = false,
}: {
  title: string;
  subtitle?: string;
  children: ReactNode;
  close: () => void;
  wide?: boolean;
}) {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const previous = document.activeElement as HTMLElement;
    const focusables = () =>
      Array.from(
        ref.current?.querySelectorAll<HTMLElement>(
          'button, input, select, textarea, a[href], [tabindex="0"]',
        ) || [],
      ).filter((el) => !el.hasAttribute("disabled"));
    focusables()
      .find((el) => el.tagName === "INPUT")
      ?.focus();
    function handle(event: KeyboardEvent) {
      if (event.key === "Escape") close();
      if (event.key !== "Tab") return;
      const elements = focusables();
      const first = elements[0],
        last = elements.at(-1);
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last?.focus();
      }
      if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first?.focus();
      }
    }
    document.addEventListener("keydown", handle);
    const oldOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => {
      document.removeEventListener("keydown", handle);
      document.body.style.overflow = oldOverflow;
      previous?.focus();
    };
  }, [close]);
  return (
    <div
      className="modal-backdrop"
      onMouseDown={(e) => {
        if (e.target === e.currentTarget) close();
      }}
    >
      <div
        ref={ref}
        className={wide ? "modal modal-wide" : "modal"}
        role="dialog"
        aria-modal="true"
        aria-label={title}
      >
        <button
          className="icon-button modal-close"
          aria-label="关闭弹窗"
          onClick={close}
        >
          <X size={20} />
        </button>
        <h2>{title}</h2>
        {subtitle && <p className="modal-subtitle">{subtitle}</p>}
        {children}
      </div>
    </div>
  );
}

function PairingModal({ close, token, onConnected }: {
  close: () => void;
  token: string | null;
  onConnected: (session: SessionInfo) => void;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const action = useRef(false);
  const requestClose = useCallback(() => { if (!action.current) close(); }, [close]);
  async function connect() {
    if (!token || action.current) return;
    action.current = true; setBusy(true); setError("");
    try {
      const result = await api<SessionInfo>("/api/pairing/redeem", {
        method: "POST", body: JSON.stringify({ token, ...browserPairingIdentity() }),
      });
      const session = { ...result };
      Reflect.deleteProperty(session, "device_token");
      onConnected(session);
    } catch (failure) { setError(errorMessage(failure)); }
    finally { action.current = false; setBusy(false); }
  }
  return (
    <Modal title="连接你的设备" subtitle="电脑和手机都可互相配对，无需账号或密码。" close={requestClose}>
      {!token && <>
        <div className="guide-step"><span>1</span><div><h3>另一台设备显示配对码</h3><p>打开顺手排，在「设备与同步」中显示一次性二维码或复制配对链接。</p></div></div>
        <div className="guide-step"><span>2</span><div><h3>扫码或粘贴配对链接</h3><p>两台设备连接同一个可信局域网，手机保持应用在前台以便被连接。</p></div></div>
      </>}
      <div className="inline-info pairing-consent">配对会共享本机全部日程及未来修改，也可能经对方传到它已配对的其他设备。解除配对只停止后续连接，无法撤回已传播的副本。模型密钥不会随日程传播。</div>
      {error && <div className="inline-error" role="alert">{error}</div>}
      <div className="modal-actions">
        {token && <button className="secondary-button" disabled={busy} onClick={requestClose}>暂不配对</button>}
        <button className="primary-button" disabled={busy} onClick={token ? () => void connect() : requestClose}>{busy ? "正在配对…" : token ? "同意配对并共享日程" : "知道了"}</button>
      </div>
    </Modal>
  );
}

function EventEditor({
  event,
  latestEvent,
  day,
  close,
  save,
  busy,
  storageKey,
  resolveConflict,
}: {
  event?: ScheduleEvent;
  latestEvent?: ScheduleEvent;
  day: string;
  close: () => void;
  save: (draft: EventDraft, baseVersion: number) => Promise<void>;
  busy: boolean;
  storageKey: string;
  resolveConflict: () => void;
}) {
  const [restored] = useState(() => loadEditorDraft(storageKey));
  const [baseVersion, setBaseVersion] = useState(restored?.baseVersion ?? event?.version ?? 0);
  const latest = latestEvent ?? event;
  const stale = !!latest && baseVersion !== latest.version;
  const unresolved = hasReplicaConflict(latest);
  const [title, setTitle] = useState(restored?.title ?? event?.title ?? "");
  const [start, setStart] = useState(
    restored?.start ?? (event ? localInput(event.start_at) : `${day}T09:00`),
  );
  const [end, setEnd] = useState(
    restored?.end ?? (event ? localInput(event.end_at) : `${day}T10:00`),
  );
  const [error, setError] = useState("");
  const [draftStored, setDraftStored] = useState(true);
  useEffect(() => {
    const changed = stale || title !== (latest?.title ?? "") ||
      start !== (latest ? localInput(latest.start_at) : `${day}T09:00`) ||
      end !== (latest ? localInput(latest.end_at) : `${day}T10:00`);
    setDraftStored(saveLocalDraft(storageKey, changed ? { title, start, end, baseVersion } : null));
  }, [title, start, end, storageKey, latest, day, baseVersion, stale]);
  async function submit(e: FormEvent) {
    e.preventDefault();
    await saveDraft(baseVersion);
  }
  async function saveDraft(version: number) {
    setError("");
    if (!title.trim()) {
      setError("请填写日程标题。");
      return;
    }
    if (end <= start) {
      setError("结束时间需要晚于开始时间。");
      return;
    }
    try {
      await save({
        title: title.trim(),
        start_local: `${start}:00`.slice(0, 19),
        end_local: `${end}:00`.slice(0, 19),
        timezone: "Asia/Shanghai",
        time_source: "explicit",
        assumptions: [],
      }, version);
      saveLocalDraft(storageKey, null);
    } catch (error) {
      setError(errorMessage(error));
    }
  }
  return (
    <Modal
      title={event ? "调整一下安排" : "手动添加日程"}
      subtitle="关闭后可继续编辑。调整开始时间时，会保留原时长。"
      close={close}
    >
      <form className="form-stack" onSubmit={submit}>
        <label>
          日程标题
          <input
            placeholder="这段时间想做什么？"
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            required
            maxLength={100}
          />
        </label>
        <label>
          开始时间
          <input
            type="datetime-local"
            value={start}
            onChange={(e) => {
              setEnd(moveEventStart(start, end, e.target.value));
              setStart(e.target.value);
            }}
            required
          />
        </label>
        <label>
          结束时间
          <input
            type="datetime-local"
            value={end}
            onChange={(e) => setEnd(e.target.value)}
            required
          />
        </label>
        <p className="settings-note">
          {!draftStored
            ? "此设备暂时无法保留草稿，请勿刷新或关闭页面。"
            : "输入自动保存在此设备，确认保存后同步。时间采用上海时区（UTC+8）。"}
        </p>
        {unresolved && <div className="inline-info">这项日程还有不同设备的并发版本。你的编辑稿已保留，请先处理版本冲突。<button type="button" className="text-button" disabled={busy} onClick={resolveConflict}>处理版本冲突</button></div>}
        {stale && latest && !unresolved && (
          <div className="inline-info editor-conflict" role="status">
            <strong>另一端已更新这项日程，你的编辑草稿仍然保留。</strong>
            <p>另一端：{latest.title}<br />
              {localInput(latest.start_at).replace("T", " ")} — {localInput(latest.end_at).replace("T", " ")}<br />
              {latest.status === "completed" ? "已完成" : "待进行"}
            </p>
            <div className="modal-actions">
              <button type="button" className="secondary-button" disabled={busy} onClick={() => {
                setTitle(latest.title);
                setStart(localInput(latest.start_at));
                setEnd(localInput(latest.end_at));
                setBaseVersion(latest.version);
                setError("");
              }}>使用另一端版本</button>
              <button type="button" className="primary-button" disabled={busy} onClick={() => {
                // Rebase only after the user has seen and explicitly accepted
                // the current remote version. Reopening a draft never rebases.
                setBaseVersion(latest.version);
                void saveDraft(latest.version);
              }}>保留我的修改并保存</button>
            </div>
          </div>
        )}
        {error && (
          <div role="alert" className="inline-error">
            {error}
          </div>
        )}
        <div className="modal-actions">
          <button type="button" className="secondary-button" disabled={busy} onClick={() => {
            saveLocalDraft(storageKey, null);
            close();
          }}>
            放弃编辑
          </button>
          <button className="primary-button" disabled={busy || stale || unresolved}>
            {busy ? (
              <LoaderCircle className="spin" size={16} />
            ) : (
              <Check size={16} />
            )}
            保存日程
          </button>
        </div>
      </form>
    </Modal>
  );
}

export default function Dashboard({ initialDay }: { initialDay: string }) {
  const [session, setSession] = useState<SessionInfo>(EMPTY_SESSION);
  const [ready, setReady] = useState(false);
  const [pairingOpen, setPairingOpen] = useState(false);
  const [pairingTicket, setPairingTicket] = useState<string | null>(null);
  const [conflictEvent, setConflictEvent] = useState<ScheduleEvent | null>(null);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [modelSettingsOpen, setModelSettingsOpen] = useState(false);
  const [modelSettings, setModelSettings] = useState<ModelSettingsInfo | null>(
    null,
  );
  const [helpOpen, setHelpOpen] = useState(false);
  const [mobileNav, setMobileNav] = useState(false);
  const [view, setView] = useState<View>("day");
  const [selectedDay, setSelectedDay] = useState(initialDay);
  const [today, setToday] = useState(initialDay);
  const currentDay = useRef(initialDay);
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
  const modelVersion = useRef(0);
  const sessionRetry = useRef(false);
  const sessionRequest = useRef<Promise<void> | null>(null);
  const refreshing = useRef<{ key: string; request: number } | null>(null);
  const userId = session.user?.id;
  const deviceName = session.peer?.name || session.device?.name || "我的设备";
  const draftKey = `shunshoupai-draft-${userId || "guest"}`;
  const pendingKey = `shunshoupai-pending-${userId || "guest"}`;
  const reviewKey = `shunshoupai-review-${userId || "guest"}`;
  const editorKey = `shunshoupai-editor-${userId || "guest"}-${editor && editor !== "new" ? editor.id : "new"}`;
  const closePairing = useCallback(() => { setPairingOpen(false); setPairingTicket(null); }, []);
  const closeConflict = useCallback(() => { if (!actionLock.current) setConflictEvent(null); }, []);
  const closeSettings = useCallback(() => setSettingsOpen(false), []);
  const closeModelSettings = useCallback(() => setModelSettingsOpen(false), []);
  const closeHelp = useCallback(() => setHelpOpen(false), []);
  const closeEditor = useCallback(() => {
    if (!actionLock.current) setEditor(null);
  }, []);
  const closeDelete = useCallback(() => {
    if (!actionLock.current) setDeleteEvent(null);
  }, []);

  useEffect(() => {
    function updateClock() {
      const nextDay = shanghaiDay();
      if (nextDay === currentDay.current) return;
      const previousDay = currentDay.current;
      currentDay.current = nextDay;
      setToday(nextDay);
      setSelectedDay((selected) =>
        selected === previousDay ? nextDay : selected,
      );
    }
    updateClock();
    const timer = setInterval(updateClock, 30_000);
    window.addEventListener("focus", updateClock);
    return () => {
      clearInterval(timer);
      window.removeEventListener("focus", updateClock);
    };
  }, []);

  const refreshModelSettings = useCallback(async () => {
    if (!userId) return;
    try {
      const { settings } = await api<{ settings: ModelSettingsInfo }>(
        "/api/model-settings",
      );
      if (userRef.current !== userId) return;
      if (settings.version < modelVersion.current) return;
      modelVersion.current = settings.version;
      setModelSettings(settings);
      setSession((s) => ({
        ...s,
        ai_provider: settings.effective_source === "rules" ? "rules" : "model",
      }));
    } catch {
      /* The settings form displays configuration errors without blocking the calendar. */
    }
  }, [userId]);

  const restoreSession = useCallback(() => {
    if (sessionRequest.current) return sessionRequest.current;
    const epoch = authEpoch.current;
    const request = api<SessionInfo>("/api/auth/session")
      .then((data) => {
        if (epoch !== authEpoch.current) return;
        sessionRetry.current = false;
        userRef.current = data.user?.id || null;
        setSession(data);
        setSyncError("");

      })
      .catch((error) => {
        if (epoch !== authEpoch.current) return;
        sessionRetry.current = true;
        setSyncError(errorMessage(error));
      })
      .finally(() => {
        sessionRequest.current = null;
        setReady(true);
      });
    sessionRequest.current = request;
    return request;
  }, []);

  const refresh = useCallback(
    async (quiet = false) => {
      if (!userId) {
        if (sessionRetry.current) await restoreSession();
        return;
      }
      const key = `${userId}:${selectedDay}`;
      // A slow connection must be allowed to finish; polling every eight
      // seconds used to invalidate every result when a response took longer.
      if (quiet && (refreshing.current?.key === key || actionLock.current)) return;
      const requestNumber = ++sequence.current;
      refreshing.current = { key, request: requestNumber };
      if (!quiet) setSyncing(true);
      try {
        const from = `${shiftDay(selectedDay, -35)}T00:00:00+08:00`;
        const to = `${shiftDay(selectedDay, 56)}T00:00:00+08:00`;
        const result = await api<{ events: ScheduleEvent[] }>(
          `/api/events?${new URLSearchParams({ from, to })}`,
        );
        if (requestNumber !== sequence.current || userRef.current !== userId)
          return;
        setEvents(result.events);
        setLastSync(new Date());
        setSyncError("");
        void refreshModelSettings();
      } catch (error) {
        if (requestNumber !== sequence.current || userRef.current !== userId)
          return;
        setSyncError(errorMessage(error));
        if (error instanceof ApiError && error.status === 401) {
          authEpoch.current++;
          userRef.current = null;
          setSession((s) => ({ ...s, user: null, peer: undefined, role: undefined }));
          setEvents([]);
          setNotice({ kind: "info", text: "设备连接已失效，请重新扫码配对。你的草稿仍保留。" });
        }
      } finally {
        if (refreshing.current?.request === requestNumber) refreshing.current = null;
        if (requestNumber === sequence.current) setSyncing(false);
      }
    },
    [selectedDay, userId, refreshModelSettings, restoreSession],
  );

  useEffect(() => {
    try {
      const ticket = takePairingTicket();
      if (ticket) { setPairingTicket(ticket); setPairingOpen(true); }
    } catch (failure) { setNotice({ kind: "info", text: errorMessage(failure) }); }
    void restoreSession();
    setOnline(navigator.onLine);
  }, [restoreSession]);
  useEffect(() => {
    if (ready) document.documentElement.setAttribute("data-shunshoupai-ready", "true");
    return () => { document.documentElement.removeAttribute("data-shunshoupai-ready"); };
  }, [ready]);
  useEffect(() => {
    userRef.current = userId || null;
    sequence.current++;
    setEvents([]);
    setLastSync(null);
    setSyncing(false);
    setParseResult(null);
    setParseError("");
    setCommitError("");
    setPending(null);
    setUncertain(false);
    setQuery("");
    setReminders([]);
    setModelSettings(null);
    modelVersion.current = 0;
    setModelSettingsOpen(false);
    setEditor(null);
    setDeleteEvent(null);
    setConflictEvent(null);
    if (userId) {
      setParseResult(loadReviewDraft(reviewKey));
      try {
        const savedText =
          localStorage.getItem(draftKey) ||
          localStorage.getItem("shunshoupai-draft-guest") ||
          "";
        setText(savedText);
        const saved = localStorage.getItem(pendingKey);
        if (saved) {
          const parsed = commitSchema.safeParse(JSON.parse(saved));
          if (parsed.success) {
            const payload = parsed.data;
            setPending(payload);
            setUncertain(true);
            setParseResult({
              events: payload.events,
              questions: [],
              warnings: [],
              needs_clarification: false,
              provider: session.ai_provider,
            });
          } else {
            localStorage.removeItem(pendingKey);
          }
        }
        setRemindersEnabled(
          localStorage.getItem(`shunshoupai-reminders-${userId}`) === "true",
        );
      } catch {
        /* storage can be unavailable in private browsers */
      }
    } else {
      try {
        setText(localStorage.getItem("shunshoupai-draft-guest") || "");
      } catch {
        setText("");
      }
    }
    // Retain the existing space ID storage keys so pre-pairing upgrades keep drafts and request IDs.
    // Provider changes are configuration changes, not a local data boundary.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [userId, draftKey, pendingKey, reviewKey]);
  useEffect(() => {
    void refresh();
    function regain() {
      if (document.visibilityState === "visible") void refresh(true);
    }
    function reconnect() {
      setOnline(true);
      void refresh();
    }
    function disconnect() {
      setOnline(false);
    }
    window.addEventListener("focus", regain);
    document.addEventListener("visibilitychange", regain);
    window.addEventListener("online", reconnect);
    window.addEventListener("offline", disconnect);
    const timer = setInterval(() => {
      if (document.visibilityState === "visible" && navigator.onLine)
        void refresh(true);
    }, 8000);
    return () => {
      clearInterval(timer);
      window.removeEventListener("focus", regain);
      document.removeEventListener("visibilitychange", regain);
      window.removeEventListener("online", reconnect);
      window.removeEventListener("offline", disconnect);
    };
  }, [refresh]);
  useEffect(() => {
    if (!notice) return;
    const timer = setTimeout(() => setNotice(null), 5500);
    return () => clearTimeout(timer);
  }, [notice]);
  useEffect(() => {
    if (!remindersEnabled || !userId) {
      setReminders([]);
      return;
    }
    function checkReminders() {
      if (document.visibilityState !== "visible") return;
      const now = Date.now();
      const due = events.filter(
        (event) =>
          event.status === "planned" &&
          new Date(event.start_at).getTime() - now <= 5 * 60_000 &&
          new Date(event.end_at).getTime() > now &&
          shanghaiDay(new Date(event.start_at)) === shanghaiDay(),
      );
      setReminders(
        due.filter((event) => {
          try {
            return !sessionStorage.getItem(
              `reminder-${userId}-${event.id}-${event.version}`,
            );
          } catch {
            return true;
          }
        }),
      );
    }
    checkReminders();
    const timer = setInterval(checkReminders, 30000);
    return () => clearInterval(timer);
  }, [remindersEnabled, events, userId]);

  function updateText(value: string) {
    setText(value);
    try {
      localStorage.setItem(draftKey, value);
    } catch {
      /* input stays in state */
    }
  }
  function updateReviewDraft(result: ParseResult | null) {
    setParseResult(result);
    saveLocalDraft(reviewKey, result);
  }
  function requireConnection() {
    if (userId) return true;
    setPairingOpen(true);
    return false;
  }
  function onConnected(data: SessionInfo) {
    authEpoch.current++;
    sessionRetry.current = false;
    userRef.current = data.user?.id || null;
    setSession(data);
    setSyncError("");
    closePairing();
    setNotice({ kind: "success", text: "配对完成，日程会在设备可达时自动合并。" });
  }
  function onDisconnected() {
    authEpoch.current++;
    sessionRetry.current = false;
    userRef.current = null;
    sequence.current++;
    setSession((s) => ({ ...s, user: null, peer: undefined, role: undefined }));
    setEvents([]);
    setSettingsOpen(false);
    setModelSettingsOpen(false);
    setEditor(null);
    setNotice({ kind: "info", text: "已解除配对，已保存在设备上的日程和草稿仍保留。" });
  }
  async function parse() {
    if (
      actionLock.current ||
      uncertain ||
      !requireConnection() ||
      parsing ||
      !text.trim()
    )
      return;
    if (!online) {
      setParseError(
        "当前离线，输入已保留。恢复联网后再整理，或先准备手动草稿。",
      );
      return;
    }
    const epoch = authEpoch.current;
    setParsing(true);
    setParseError("");
    setCommitError("");
    try {
      const result = await api<ParseResult>("/api/ai/parse", {
        method: "POST",
        body: JSON.stringify({ text, timezone: "Asia/Shanghai" }),
      });
      if (epoch !== authEpoch.current) return;
      updateReviewDraft(result);
      setPending(null);
      setUncertain(false);
    } catch (error) {
      if (epoch === authEpoch.current) setParseError(errorMessage(error));
    } finally {
      setParsing(false);
    }
  }
  function editDraft(index: number, field: keyof EventDraft, value: string) {
    if (uncertain || parsing || !parseResult) return;
    updateReviewDraft({
      ...parseResult,
      events: parseResult.events.map((event, i) =>
        i === index
          ? {
              ...event,
              [field]: field.endsWith("local")
                ? value.length === 16
                  ? `${value}:00`
                  : value
                : value,
            }
          : event,
      ),
    });
    setPending(null);
    setCommitError("");
  }
  async function sendCommit(payload: PendingCommit) {
    return api<{ events: ScheduleEvent[]; replayed?: boolean }>(
      "/api/events/commit",
      { method: "POST", body: JSON.stringify(payload) },
    );
  }
  async function commitDrafts() {
    if (parsing || !parseResult?.events.length || actionLock.current || !requireConnection())
      return;
    if (!online) {
      setCommitError(
        "当前离线，尚未发出保存请求。草稿已保留，恢复联网后再保存。",
      );
      return;
    }
    const invalid = parseResult.events.find(
      (event) => !event.title.trim() || event.end_local <= event.start_local,
    );
    if (invalid) {
      setCommitError("请填写标题，并确保每项的结束时间晚于开始时间。");
      return;
    }
    const payload = pending || {
      request_id: requestId(),
      events: parseResult.events,
    };
    const epoch = authEpoch.current;
    actionLock.current = true;
    setSaving(true);
    setCommitError("");
    setPending(payload);
    try {
      localStorage.setItem(pendingKey, JSON.stringify(payload));
    } catch {
      /* network retry still retains key in memory */
    }
    try {
      const result = await sendCommit(payload);
      if (epoch !== authEpoch.current) return;
      setUncertain(false);
      setPending(null);
      updateReviewDraft(null);
      updateText("");
      try {
        localStorage.removeItem(pendingKey);
        localStorage.removeItem("shunshoupai-draft-guest");
      } catch {
        /* no-op */
      }
      if (result.events[0]) {
        setSelectedDay(shanghaiDay(new Date(result.events[0].start_at)));
        setView("day");
      }
      await refresh();
      if (epoch !== authEpoch.current) return;
      setNotice({
        kind: "success",
        text: result.replayed
          ? "已确认上次保存成功，没有重复创建。"
          : `已保存 ${result.events.length} 项日程，其他设备会在 8 秒内刷新。`,
      });
    } catch (error) {
      if (epoch !== authEpoch.current) return;
      if (
        error instanceof ApiError &&
        error.status < 500 &&
        error.status !== 408
      ) {
        setPending(null);
        setUncertain(false);
        try {
          localStorage.removeItem(pendingKey);
        } catch {
          /* no-op */
        }
        setCommitError(errorMessage(error));
      } else {
        setUncertain(true);
        setCommitError(
          "保存状态待确认：请求可能已完成。请点击「核实并重试」，应用会沿用原请求编号，避免重复创建。",
        );
      }
    } finally {
      actionLock.current = false;
      setSaving(false);
    }
  }
  async function saveEditor(draft: EventDraft, baseVersion: number) {
    if (actionLock.current) return;
    if (!online) throw new Error("离线");
    const epoch = authEpoch.current;
    actionLock.current = true;
    setSaving(true);
    try {
      if (editor && editor !== "new")
        await api(`/api/events/${editor.id}`, {
          method: "PATCH",
          body: JSON.stringify({
            base_version: baseVersion,
            title: draft.title,
            start_local: draft.start_local,
            end_local: draft.end_local,
          }),
        });
      else {
        const payload = pending || { request_id: requestId(), events: [draft] };
        try {
          localStorage.setItem(pendingKey, JSON.stringify(payload));
        } catch {
          /* no-op */
        }
        try {
          await sendCommit(payload);
          try {
            localStorage.removeItem(pendingKey);
          } catch {
            /* successful server response stays successful when storage is blocked */
          }
        } catch (error) {
          if (epoch !== authEpoch.current) return;
          if (
            !(error instanceof ApiError) ||
            error.status >= 500 ||
            error.status === 408
          ) {
            setPending(payload);
            setUncertain(true);
            updateReviewDraft({
              events: payload.events,
              questions: [],
              warnings: [],
              needs_clarification: false,
              provider: "rules",
            });
            setCommitError("保存状态待确认，请使用「核实并重试」确认结果。");
            saveLocalDraft(editorKey, null);
            setEditor(null);
          } else {
            try {
              localStorage.removeItem(pendingKey);
            } catch {
              /* no-op */
            }
          }
          throw error;
        }
      }
      if (epoch !== authEpoch.current) return;
      setEditor(null);
      setSelectedDay(draft.start_local.slice(0, 10));
      setView("day");
      await refresh();
      if (epoch === authEpoch.current)
        setNotice({ kind: "success", text: "日程已保存。" });
    } catch (error) {
      if (epoch !== authEpoch.current) return;
      if (error instanceof ApiError && error.status === 409) await refresh();
      throw error;
    } finally {
      actionLock.current = false;
      setSaving(false);
    }
  }
  async function changeStatus(event: ScheduleEvent) {
    if (actionLock.current) return;
    if (!online) {
      setNotice({ kind: "error", text: "当前离线，未提交更改。" });
      return;
    }
    const epoch = authEpoch.current;
    actionLock.current = true;
    setSaving(true);
    try {
      await api(`/api/events/${event.id}`, {
        method: "PATCH",
        body: JSON.stringify({
          base_version: event.version,
          status: event.status === "completed" ? "planned" : "completed",
        }),
      });
      if (epoch !== authEpoch.current) return;
      await refresh();
      if (epoch === authEpoch.current)
        setNotice({
          kind: "success",
          text:
            event.status === "completed"
              ? "已恢复为待办。"
              : "又完成一件事，做得不错！",
        });
    } catch (error) {
      if (epoch !== authEpoch.current) return;
      setNotice({
        kind: "error",
        text:
          error instanceof ApiError
            ? error.message
            : "更改状态待确认，请刷新查看最新结果。",
      });
      await refresh();
    } finally {
      actionLock.current = false;
      setSaving(false);
    }
  }
  async function removeEvent() {
    if (!deleteEvent || actionLock.current) return;
    if (!online) {
      setNotice({ kind: "error", text: "当前离线，尚未删除。" });
      return;
    }
    const epoch = authEpoch.current;
    actionLock.current = true;
    setSaving(true);
    try {
      await api(`/api/events/${deleteEvent.id}`, {
        method: "DELETE",
        body: JSON.stringify({ base_version: deleteEvent.version }),
      });
      if (epoch !== authEpoch.current) return;
      setDeleteEvent(null);
      await refresh();
      if (epoch === authEpoch.current)
        setNotice({ kind: "success", text: "日程已删除。" });
    } catch (error) {
      if (epoch !== authEpoch.current) return;
      setNotice({
        kind: "error",
        text:
          error instanceof ApiError
            ? error.message
            : "删除状态待确认，请刷新查看。",
      });
      await refresh();
    } finally {
      actionLock.current = false;
      setSaving(false);
    }
  }
  function changeView(next: View) {
    setView(next);
    setMobileNav(false);
  }
  function addNew() {
    if (!requireConnection()) return;
    if (uncertain) {
      setNotice({
        kind: "info",
        text: "请先核实上次保存的结果，再添加新安排。",
      });
      return;
    }
    setEditor("new");
  }
  const selectedEvents = events.filter((event) =>
    touchesWindow(event, selectedDay),
  );
  const completedCount = selectedEvents.filter(
    (event) => event.status === "completed",
  ).length;
  const visibleEvents = events
    .filter((event) => {
      if (query && !event.title.toLowerCase().includes(query.toLowerCase()))
        return false;
      if (view === "completed") return event.status === "completed";
      if (view === "week")
        return touchesWindow(event, selectedDay, shiftDay(selectedDay, 7));
      return touchesWindow(event, selectedDay);
    })
    .sort((a, b) => a.start_at.localeCompare(b.start_at));
  const weekStart = shiftDay(
    selectedDay,
    -((new Date(`${selectedDay}T12:00:00+08:00`).getUTCDay() + 6) % 7),
  );
  const totalMinutes = selectedEvents
    .filter((e) => e.status !== "completed")
    .reduce(
      (sum, e) =>
        sum +
        (Math.min(
          Date.parse(e.end_at),
          Date.parse(`${shiftDay(selectedDay, 1)}T00:00:00+08:00`),
        ) -
          Math.max(
            Date.parse(e.start_at),
            Date.parse(`${selectedDay}T00:00:00+08:00`),
          )) /
          60000,
      0,
    );
  const title =
    view === "completed"
      ? "每一步，都算数"
      : selectedDay === today
        ? "今天，慢慢来，也来得及"
        : `${dayLabel(selectedDay)}，好好安排`;

  return (
    <div className="app-shell">
      {mobileNav && (
        <div className="nav-scrim" onClick={() => setMobileNav(false)} />
      )}
      <aside className={`sidebar ${mobileNav ? "sidebar-open" : ""}`}>
        <Brand />
        <div className="workspace-label">
          我的个人空间 <span>PERSONAL</span>
        </div>
        <nav className="main-nav" aria-label="主导航">
          <button
            className={view === "day" ? "nav-item active" : "nav-item"}
            onClick={() => changeView("day")}
          >
            <LayoutDashboard size={18} />
            日程概览{view === "day" && <span className="nav-active-dot" />}
          </button>
          <button
            className={view === "week" ? "nav-item active" : "nav-item"}
            onClick={() => changeView("week")}
          >
            <CalendarDays size={18} />
            未来七天
          </button>
          <button
            className={view === "completed" ? "nav-item active" : "nav-item"}
            onClick={() => changeView("completed")}
          >
            <CheckCheck size={18} />
            已完成
            <span className="nav-count">
              {events.filter((e) => e.status === "completed").length}
            </span>
          </button>
        </nav>
        <div className="sidebar-tip">
          <div className="tip-illustration">
            <Coffee size={28} strokeWidth={1.5} />
            <span>✦</span>
          </div>
          <strong>安排生活，也留点空白</strong>
          <p>
            不必把每一分钟填满。
            <br />
            留一点时间，给自己。
          </p>
          <span className="tip-line" />
        </div>
        <div className="sidebar-bottom">
          <button className="nav-item" onClick={() => setHelpOpen(true)}>
            <HelpCircle size={18} />
            使用指南
            <ArrowUpRight size={14} className="push-right" />
          </button>
          <button className="nav-item" onClick={() => setSettingsOpen(true)}>
            <Settings2 size={18} />
            设备与同步
          </button>
          <div className="user-box">
            <span className="avatar">
              {deviceName.slice(0, 1)}
            </span>
            <div>
              <strong>
                {session.user ? deviceName : "连接后接着安排"}
              </strong>
              <span>{session.user ? "设备日程" : "扫码配对，无需账号"}</span>
            </div>
            <button
              className="icon-button"
              aria-label={session.user ? "设备设置" : "配对说明"}
              onClick={() =>
                session.user ? setSettingsOpen(true) : setPairingOpen(true)
              }
            >
              {session.user ? (
                <Settings2 size={16} />
              ) : (
                <ArrowRight size={17} />
              )}
            </button>
          </div>
        </div>
      </aside>
      <div className="workspace">
        <header className="topbar">
          <div className="topbar-left">
            <button
              className="icon-button mobile-menu"
              aria-label="打开导航"
              onClick={() => setMobileNav((v) => !v)}
            >
              <Menu size={21} />
            </button>
            <span className="breadcrumb">我的工作台</span>
            <span className="breadcrumb-separator">/</span>
            <span>
              {view === "completed"
                ? "已完成"
                : view === "week"
                  ? "未来七天"
                  : "日程概览"}
            </span>
          </div>
          <div className="topbar-right">
            <span
              className={`sync-badge ${!online || syncError ? "is-offline" : ""}`}
              title={
                syncError ||
                (lastSync
                  ? `当前日程连接正常，最后读取 ${clockTime(lastSync.toISOString())}。各设备的合并状态请查看“设备与同步”。`
                  : "配对后同步日程")
              }
            >
              {!online ? (
                <CloudOff size={14} />
              ) : syncing ? (
                <RefreshCw size={14} className="spin" />
              ) : (
                <Cloud size={14} />
              )}
              <span>
                {!online
                  ? "当前离线"
                  : syncing
                    ? "正在读取"
                    : syncError
                      ? "连接待恢复"
                      : userId && lastSync
                        ? "连接正常"
                        : "尚未配对"}
              </span>
              {online && !syncError && userId && <i />}
            </span>
            <span className="topbar-divider" />
            <button
              className={`icon-button reminder-toggle ${remindersEnabled ? "enabled" : ""}`}
              aria-label="提醒设置"
              onClick={() => setSettingsOpen(true)}
            >
              <Bell size={18} />
              {remindersEnabled && <i />}
            </button>
            <button
              className="avatar avatar-small"
              title={session.user ? deviceName : "配对说明"}
              aria-label={session.user ? "打开设备与同步" : "配对说明"}
              onClick={() =>
                session.user ? setSettingsOpen(true) : setPairingOpen(true)
              }
            >
              {deviceName.slice(0, 1)}
            </button>
          </div>
        </header>
        <main>
          <section className="page-heading">
            <div>
              <div className="eyebrow">
                <span />
                LESS CHAOS, MORE LIFE
              </div>
              <h1>
                {title}
                <span className="heading-sparkle">✳</span>
              </h1>
              <p>把脑子里的安排说出来，剩下的，顺手排。</p>
            </div>
            <div className="heading-actions">
              <button className="secondary-button add-button" onClick={addNew}>
                <Plus size={17} />
                手动添加
              </button>
              <button className="secondary-button quick-compose" onClick={() => {
                const input = document.getElementById("schedule-input");
                input?.focus({ preventScroll: true });
                input?.scrollIntoView({ block: "center" });
              }}>
                <Sparkles size={17} />
                一句话安排
              </button>
            </div>
          </section>
          {(!online || syncError) && (
            <div className="connection-banner" role="status">
              <CloudOff size={17} />
              <span>
                {!online
                  ? "网络已断开。输入会保留，恢复连接后可继续。各设备的合并状态请查看“设备与同步”。"
                  : syncError}
              </span>
              <button onClick={() => void refresh()}>重新同步</button>
            </div>
          )}
          {reminders.length > 0 && (
            <div className="reminder-banner" role="status">
              <Bell size={18} />
              <div>
                <strong>这件事，别忘了</strong>
                <span>
                  {reminders
                    .map((e) => `${clockTime(e.start_at)} ${e.title}`)
                    .join(" · ")}
                </span>
              </div>
              <button
                className="icon-button"
                aria-label="知道了，关闭提醒"
                onClick={() => {
                  for (const event of reminders) {
                    try {
                      sessionStorage.setItem(
                        `reminder-${userId}-${event.id}-${event.version}`,
                        "true",
                      );
                    } catch {
                      /* no-op */
                    }
                  }
                  setReminders([]);
                }}
              >
                <X size={18} />
              </button>
            </div>
          )}
          <div className="dashboard-grid">
            <section className="composer-column" aria-label="日程整理">
              <div className="composer-card">
                <div className="card-topline">
                  <span className="section-icon">
                    <Sparkles size={19} />
                  </span>
                  <div>
                    <h2>想到什么，就写下来</h2>
                    <p>一段话，整理好你的大小事</p>
                  </div>
                  <span className="mini-label">
                    {session.ai_provider === "model" ? "AI 助手" : "规则体验"}
                  </span>
                </div>
                <div className={`input-area ${parsing ? "is-parsing" : ""}`}>
                  <textarea
                    id="schedule-input"
                    aria-label="描述你的日程安排"
                    placeholder={
                      "比如：明天下午三点到四点面试，\n今天晚上七点到八点改简历……"
                    }
                    value={text}
                    disabled={parsing || uncertain || saving}
                    onChange={(e) => updateText(e.target.value)}
                    maxLength={2000}
                    onKeyDown={(e) => {
                      if ((e.ctrlKey || e.metaKey) && e.key === "Enter") {
                        e.preventDefault();
                        void parse();
                      }
                    }}
                  />
                  <div className="input-meta">
                    <span>
                      <span className="tiny-dot" /> 支持明确时间的多项日程
                    </span>
                    <span>{text.length}/2000</span>
                  </div>
                </div>
                <div className="example-row">
                  <span>没想好？</span>
                  <button
                    onClick={() => {
                      if (!uncertain) updateText(EXAMPLE);
                    }}
                    disabled={uncertain || parsing || saving}
                  >
                    <WandSparkles size={13} />
                    试试这句话
                    <ArrowUpRight size={12} />
                  </button>
                </div>
                <button
                  className="primary-button parse-button"
                  onClick={() => void parse()}
                  disabled={
                    parsing || saving || !text.trim() || uncertain || !ready
                  }
                >
                  {parsing ? (
                    <LoaderCircle className="spin" size={18} />
                  ) : (
                    <Sparkles size={18} />
                  )}
                  {parsing ? "正在把想法整理好…" : "帮我整理一下"}
                  {!parsing && (
                    <span className="keyboard-hint">⌘ / Ctrl ↵</span>
                  )}
                </button>
                <p className="composer-footnote">
                  <ShieldCheck size={13} />
                  {session.ai_provider === "model"
                    ? "只整理当前输入，确认前不会保存"
                    : "未配置模型密钥 · 当前为有限规则解析"}
                </p>
                {parseError && (
                  <div className="inline-error" role="alert">
                    {parseError}
                    <button className="text-link" onClick={addNew}>
                      改为手动填写 <ArrowRight size={13} />
                    </button>
                  </div>
                )}
              </div>
              {parseResult ? (
                <div className="draft-section">
                  <div className="draft-heading">
                    <h3>
                      <span className="draft-dot" />
                      {parseResult.events.length
                        ? `${parseResult.events.length} 项草稿，等你确认`
                        : "还需要一点信息"}
                    </h3>
                    <span>
                      {parseResult.provider === "model"
                        ? "AI 解析"
                        : "规则解析"}
                    </span>
                  </div>
                  {!!parseResult.questions.length && (
                    <div className="clarification" role="status">
                      <HelpCircle size={17} />
                      <div>
                        <strong>先确认一下</strong>
                        {parseResult.questions.map((q, i) => (
                          <p key={i}>{q}</p>
                        ))}
                        <small>请在上方补充信息，再次整理。</small>
                      </div>
                    </div>
                  )}
                  {parseResult.warnings.map((warning, index) => (
                    <div className="draft-warning" key={index}>
                      {warning}
                    </div>
                  ))}
                  {parseResult.events.map((draft, index) => (
                    <div className="draft-card" key={index}>
                      <div className="draft-title-row">
                        <span className="draft-number">
                          {String(index + 1).padStart(2, "0")}
                        </span>
                        <input
                          aria-label={`草稿 ${index + 1} 标题`}
                          value={draft.title}
                          maxLength={100}
                          disabled={uncertain || saving || parsing}
                          onChange={(e) =>
                            editDraft(index, "title", e.target.value)
                          }
                        />
                        <button
                          aria-label={`移除草稿 ${index + 1}`}
                          className="icon-button"
                          disabled={uncertain || saving || parsing}
                          onClick={() => {
                            updateReviewDraft({
                              ...parseResult,
                              events: parseResult.events.filter(
                                (_, i) => i !== index,
                              ),
                            });
                            setPending(null);
                          }}
                        >
                          <X size={15} />
                        </button>
                      </div>
                      <div className="draft-time-fields">
                        <label>
                          开始
                          <input
                            type="datetime-local"
                            aria-label={`草稿 ${index + 1} 开始时间`}
                            value={draft.start_local.slice(0, 16)}
                            disabled={uncertain || saving || parsing}
                            onChange={(e) =>
                              editDraft(index, "start_local", e.target.value)
                            }
                          />
                        </label>
                        <ArrowRight size={13} />
                        <label>
                          结束
                          <input
                            type="datetime-local"
                            aria-label={`草稿 ${index + 1} 结束时间`}
                            value={draft.end_local.slice(0, 16)}
                            disabled={uncertain || saving || parsing}
                            onChange={(e) =>
                              editDraft(index, "end_local", e.target.value)
                            }
                          />
                        </label>
                      </div>
                      {(draft.time_source !== "explicit" ||
                        draft.assumptions.length > 0) && (
                        <p className="assumption-note">
                          {draft.time_source === "assumed"
                            ? "含默认假设 · "
                            : draft.time_source === "suggested"
                              ? "建议时间 · "
                              : ""}
                          {draft.assumptions.join("；") || "请确认时间后保存"}
                        </p>
                      )}
                    </div>
                  ))}
                  {commitError && (
                    <div className="inline-error" role="alert">
                      {commitError}
                    </div>
                  )}
                  {!!parseResult.events.length && (
                    <div className="draft-actions">
                      <button
                        className="text-button"
                        disabled={uncertain || saving || parsing}
                        onClick={() => {
                          updateReviewDraft(null);
                          setPending(null);
                          setCommitError("");
                        }}
                      >
                        取消草稿
                      </button>
                      <button
                        className="primary-button"
                        disabled={
                          saving ||
                          parsing ||
                          (parseResult.needs_clarification && !uncertain)
                        }
                        onClick={() => void commitDrafts()}
                      >
                        {saving ? (
                          <LoaderCircle size={16} className="spin" />
                        ) : (
                          <Check size={16} />
                        )}
                        {uncertain
                          ? "核实并重试"
                          : `确认保存 ${parseResult.events.length} 项`}
                      </button>
                    </div>
                  )}
                  <p className="draft-note">草稿尚未保存 · 上海时区 UTC+8</p>
                </div>
              ) : (
                <div className="how-it-works">
                  <div className="how-heading">
                    <span className="little-sparkle">✦</span>
                    <span>让安排，简单一点</span>
                  </div>
                  <div className="how-step">
                    <span>01</span>
                    <div>
                      <strong>随手写下</strong>
                      <p>不用想格式，像平时说话一样</p>
                    </div>
                  </div>
                  <div className="how-step">
                    <span>02</span>
                    <div>
                      <strong>看一眼，改一改</strong>
                      <p>时间、标题都由你最后决定</p>
                    </div>
                  </div>
                  <div className="how-step">
                    <span>03</span>
                    <div>
                      <strong>确认后，各端接着用</strong>
                      <p>配对一次，连接时自动同步</p>
                    </div>
                  </div>
                  <div className="device-note">
                    <span className="device-dots">
                      <i />
                      <i />
                      <i />
                    </span>
                    <span>今天的安排，始终在手边</span>
                  </div>
                </div>
              )}
            </section>
            <section className="agenda-column" aria-label="已保存日程">
              <div className="agenda-card">
                <div className="agenda-heading">
                  <div>
                    <span className="small-eyebrow">YOUR LITTLE PLANS</span>
                    <h2>
                      {view === "completed"
                        ? "完成的事"
                        : view === "week"
                          ? "接下来的一周"
                          : "我的日程"}
                      <span>{visibleEvents.length}</span>
                    </h2>
                  </div>
                  <div className="agenda-tools">
                    <button
                      className="icon-button"
                      title="重新同步日程"
                      aria-label="重新同步日程"
                      disabled={syncing || !userId}
                      onClick={() => void refresh()}
                    >
                      <RefreshCw size={16} className={syncing ? "spin" : ""} />
                    </button>
                    <button
                      className="icon-button"
                      title="添加日程"
                      aria-label="添加日程"
                      onClick={addNew}
                    >
                      <Plus size={19} />
                    </button>
                  </div>
                </div>
                <div className="date-navigation">
                  <div className="current-date">
                    <CalendarDays size={17} />
                    <strong>{dayLabel(selectedDay, true)}</strong>
                    {selectedDay === today && <span>今天</span>}
                  </div>
                  <div className="date-actions">
                    <button
                      className="today-button"
                      onClick={() => setSelectedDay(today)}
                    >
                      回今天
                    </button>
                    <button
                      className="icon-button"
                      aria-label="前一天"
                      onClick={() => setSelectedDay(shiftDay(selectedDay, -1))}
                    >
                      <ChevronLeft size={17} />
                    </button>
                    <button
                      className="icon-button"
                      aria-label="后一天"
                      onClick={() => setSelectedDay(shiftDay(selectedDay, 1))}
                    >
                      <ChevronRight size={17} />
                    </button>
                  </div>
                </div>
                <div className="week-strip">
                  {Array.from({ length: 7 }, (_, index) => {
                    const day = shiftDay(weekStart, index);
                    const count = events.filter((e) =>
                      touchesWindow(e, day),
                    ).length;
                    return (
                      <button
                        key={day}
                        className={`week-day ${day === selectedDay ? "selected" : ""} ${day === today ? "is-today" : ""}`}
                        onClick={() => setSelectedDay(day)}
                        aria-label={dayLabel(day, true)}
                        aria-pressed={day === selectedDay}
                      >
                        <span>
                          {["一", "二", "三", "四", "五", "六", "日"][index]}
                        </span>
                        <strong>{Number(day.slice(-2))}</strong>
                        <i className={count ? "has-events" : ""} />
                      </button>
                    );
                  })}
                </div>
                <div className="agenda-filter">
                  <div className="view-switch" aria-label="时间范围">
                    <button
                      className={view === "day" ? "active" : ""}
                      onClick={() => setView("day")}
                    >
                      当天
                    </button>
                    <button
                      className={view === "week" ? "active" : ""}
                      onClick={() => setView("week")}
                    >
                      七天
                    </button>
                    <button
                      className={view === "completed" ? "active" : ""}
                      onClick={() => setView("completed")}
                    >
                      已完成
                    </button>
                  </div>
                  <label className="search-box">
                    <Search size={14} />
                    <input
                      aria-label="搜索已加载日程"
                      placeholder="搜索日程"
                      value={query}
                      onChange={(e) => setQuery(e.target.value)}
                      maxLength={100}
                    />
                  </label>
                </div>
                <div className="agenda-list">
                  {!ready || (syncing && !lastSync) ? (
                    <div className="empty-state">
                      <LoaderCircle className="spin" size={26} />
                      <p>正在取回你的安排…</p>
                    </div>
                  ) : visibleEvents.length === 0 ? (
                    <div className="empty-state">
                      <div className="empty-art">
                        <span className="empty-orbit" />
                        <CalendarCheck2 size={40} strokeWidth={1.3} />
                        <span className="empty-star">✦</span>
                        <span className="empty-dot" />
                      </div>
                      <h3>
                        {query
                          ? "暂时没找到这项安排"
                          : !userId
                            ? "你的日程，留在自己的设备"
                            : view === "completed"
                              ? "每完成一件事，就在这里留个脚印"
                              : "时间留白，好事正要发生"}
                      </h3>
                      <p>
                        {query
                          ? "试试其他关键词，或切换日期范围。"
                          : session.user
                            ? "写下第一件想做的事，给今天一个小小的开始。"
                            : "手机应用可直接记录日程；配对后，这里会显示设备间的安排。"}
                      </p>
                      <button
                        className="empty-action"
                        onClick={() =>
                          query
                            ? setQuery("")
                            : session.user
                              ? addNew()
                              : setPairingOpen(true)
                        }
                      >
                        {query
                          ? "清空搜索"
                          : session.user
                            ? "添加第一项日程"
                            : "如何连接设备"}
                        <ArrowRight size={14} />
                      </button>
                    </div>
                  ) : (
                    visibleEvents.map((event, index) => {
                      const duration = Math.round(
                        (new Date(event.end_at).getTime() -
                          new Date(event.start_at).getTime()) /
                          60000,
                      );
                      const completed = event.status === "completed";
                      const conflicted = hasReplicaConflict(event);
                      return (
                        <article
                          className={`event-row ${completed ? "event-completed" : ""}`}
                          key={event.id}
                        >
                          <div className="event-time">
                            <strong>{clockTime(event.start_at)}</strong>
                            <span>{clockTime(event.end_at)}</span>
                            {(view !== "day" ||
                              shanghaiDay(new Date(event.start_at)) !==
                                selectedDay ||
                              shanghaiDay(new Date(event.end_at)) !==
                                selectedDay) && (
                              <small>
                                {dayLabel(
                                  shanghaiDay(new Date(event.start_at)),
                                )}
                              </small>
                            )}
                          </div>
                          <div
                            className={`event-content event-color-${index % 3}`}
                          >
                            <div className="event-main">
                              <button
                                className={`complete-button ${completed ? "is-complete" : ""}`}
                                disabled={saving || conflicted}
                                aria-label={`${completed ? "恢复" : "完成"}日程：${event.title}`}
                                onClick={() => void changeStatus(event)}
                              >
                                {completed ? (
                                  <Check size={13} />
                                ) : (
                                  <Circle size={17} />
                                )}
                              </button>
                              <h3>{event.title}</h3>
                            </div>
                            {conflicted && <button className="replica-conflict-entry" disabled={saving} aria-label={`处理版本冲突：${event.title}`} onClick={() => setConflictEvent(event)}>{event.replica_conflicts!.length} 个不同版本 · 选择要保留的内容</button>}
                            <div className="event-details">
                              <span>
                                <Clock3 size={12} />
                                {duration} 分钟
                              </span>
                              <span className="event-status">
                                {completed ? "已完成" : "待进行"}
                              </span>
                              <div className="event-row-actions">
                                <button
                                  className="icon-button"
                                  disabled={saving || conflicted}
                                  aria-label={`编辑日程：${event.title}`}
                                  onClick={() => setEditor(event)}
                                >
                                  <Edit3 size={14} />
                                </button>
                                <button
                                  className="icon-button delete-button"
                                  disabled={saving || conflicted}
                                  aria-label={`删除日程：${event.title}`}
                                  onClick={() => setDeleteEvent(event)}
                                >
                                  <Trash2 size={14} />
                                </button>
                              </div>
                            </div>
                          </div>
                        </article>
                      );
                    })
                  )}
                </div>
                <div className="agenda-footer">
                  <span>
                    <span className="green-dot" />
                    {lastSync
                      ? `最后同步 ${clockTime(lastSync.toISOString())}`
                      : "等待你的第一项安排"}
                  </span>
                  <span>上海时区 UTC+8</span>
                </div>
              </div>
              <div className="day-summary">
                <div className="summary-icon">
                  <CalendarCheck2 size={21} />
                </div>
                <div>
                  <strong>
                    {selectedEvents.length
                      ? `这一天的步调，由你掌握`
                      : "不赶时间，也不辜负时间"}
                  </strong>
                  <p>
                    {selectedEvents.length
                      ? `共 ${selectedEvents.length} 项安排 · 已完成 ${completedCount} 项 · 待办约 ${totalMinutes} 分钟`
                      : "安排好重要的事，给生活留一点松弛感。"}
                  </p>
                </div>
                <span className="summary-decoration">↗</span>
              </div>
            </section>
          </div>
          <footer className="page-footer">
            <Brand small />
            <span>把想法排进生活，把时间还给自己。</span>
            <span className="footer-mode">
              设备本地保存{" "}
              <span>·</span> 前台 8 秒同步
            </span>
          </footer>
        </main>
      </div>
      {notice && (
        <div
          className={`toast toast-${notice.kind}`}
          role={notice.kind === "error" ? "alert" : "status"}
        >
          {notice.kind === "success" ? (
            <Check size={18} />
          ) : notice.kind === "error" ? (
            <CloudOff size={18} />
          ) : (
            <Bell size={18} />
          )}
          <span>{notice.text}</span>
          <button
            className="icon-button"
            aria-label="关闭提示"
            onClick={() => setNotice(null)}
          >
            <X size={15} />
          </button>
        </div>
      )}
      {pairingOpen && (
        <PairingModal close={closePairing} token={pairingTicket} onConnected={onConnected} />
      )}
      {conflictEvent && (
        <Modal title="处理版本冲突" subtitle="每份修改都保留，最终内容由你选择。" close={closeConflict} wide>
          <ReplicaConflict eventId={conflictEvent.id} onBusyChange={(busy) => { actionLock.current = busy; setSaving(busy); }} onResolved={() => {
            setConflictEvent(null);
            void refresh();
            setNotice({ kind: "success", text: "日程版本已更新，其他设备连接后会收到结果。" });
          }} />
        </Modal>
      )}
      {editor && (
        <EventEditor
          key={editorKey}
          event={editor === "new" ? undefined : editor}
          latestEvent={editor === "new" ? undefined : events.find(event => event.id === editor.id)}
          day={selectedDay}
          close={closeEditor}
          save={saveEditor}
          busy={saving}
          storageKey={editorKey}
          resolveConflict={() => {
            if (editor !== "new") { setConflictEvent(editor); setEditor(null); }
          }}
        />
      )}
      {deleteEvent && (
        <Modal
          title="把这项安排移走？"
          subtitle={`「${deleteEvent.title}」删除后会从各端日程中消失。`}
          close={closeDelete}
        >
          <div className="modal-actions">
            <button
              className="secondary-button"
              onClick={closeDelete}
              disabled={saving}
            >
              再想想
            </button>
            <button
              className="primary-button danger-button"
              disabled={saving}
              onClick={() => void removeEvent()}
            >
              {saving ? (
                <LoaderCircle className="spin" size={16} />
              ) : (
                <Trash2 size={16} />
              )}
              确认删除
            </button>
          </div>
        </Modal>
      )}
      {settingsOpen && (
        <Modal
          title="设备与同步"
          subtitle="日程保存在你的设备，连接时自动合并。"
          close={closeSettings}
        >
          <div className="settings-list">
            <div>
              <span>当前设备</span>
              <strong>{session.user ? deviceName : "尚未配对"}</strong>
            </div>
            <div>
              <span>数据存储</span>
              <strong>
                {session.device?.name ? `${session.device.name} · 本机保存` : "各设备独立保存"}
              </strong>
            </div>
            <div>
              <span>解析方式</span>
              <strong>
                {session.ai_provider === "model"
                  ? modelSettings?.effective_source === "personal"
                    ? "个人模型（已配置）"
                    : "设备默认模型（已配置）"
                  : "有限规则解析（未接模型）"}
              </strong>
            </div>
            <div>
              <span>显示时区</span>
              <strong>Asia/Shanghai · UTC+8</strong>
            </div>
            <div>
              <label htmlFor="reminders">
                前台站内提醒<small>开始前 5 分钟及进行中提示</small>
              </label>
              <input
                id="reminders"
                type="checkbox"
                role="switch"
                checked={remindersEnabled}
                onChange={(e) => {
                  const enabled = e.target.checked;
                  setRemindersEnabled(enabled);
                  try {
                    localStorage.setItem(
                      `shunshoupai-reminders-${userId}`,
                      String(enabled),
                    );
                  } catch {
                    /* no-op */
                  }
                }}
                disabled={!userId}
              />
            </div>
          </div>
          <button
            className="secondary-button wide model-settings-entry"
            disabled={!userId || saving || parsing}
            onClick={() => {
              setSettingsOpen(false);
              setModelSettingsOpen(true);
            }}
          >
            <KeyRound size={16} />
            模型设置
            <ArrowRight size={15} />
          </button>
          <p className="settings-note">
            提醒需要页面在前台活跃运行；锁屏、后台或关闭网页时，不提供系统级准点提醒。配对设备连接时，网页日程在前台每
            8 秒和回到页面时刷新。
          </p>
          {session.user ? (
            <DeviceConnections role={session.role} onDisconnected={onDisconnected} />
          ) : (
            <button className="primary-button wide" onClick={() => {
              setSettingsOpen(false);
              setPairingOpen(true);
            }}>
              如何连接设备<ArrowRight size={16} />
            </button>
          )}
        </Modal>
      )}
      {modelSettingsOpen && userId && (
        <Modal
          title="模型设置"
          subtitle="用自己的模型整理日程，配置保存在当前连接的设备。"
          close={closeModelSettings}
          wide
        >
          <ModelSettingsPanel
            key={userId}
            onChanged={(settings) => {
              if (userRef.current !== userId) return;
              modelVersion.current = settings.version;
              setModelSettings(settings);
              setSession((s) => ({
                ...s,
                ai_provider:
                  settings.effective_source === "rules" ? "rules" : "model",
              }));
            }}
          />
        </Modal>
      )}
      {helpOpen && (
        <Modal
          title="从一句话开始"
          subtitle="三个小步骤，让安排有条不紊。"
          close={closeHelp}
        >
          <div className="guide-step">
            <span>1</span>
            <div>
              <h3>写下安排</h3>
              <p>
                输入明确的日期、上午/下午和起止时间，一次最多 5
                项。例如“明天下午三点到四点面试”。
              </p>
            </div>
          </div>
          <div className="guide-step">
            <span>2</span>
            <div>
              <h3>检查草稿</h3>
              <p>
                整理结果还没保存。检查具体日期与时间，可以直接改标题或时间；有歧义时，补充信息后再整理。
              </p>
            </div>
          </div>
          <div className="guide-step">
            <span>3</span>
            <div>
              <h3>确认，换端接着用</h3>
              <p>
                确认后日程才会保存。设备完成一次配对后，在可连接时自动合并，网页前台
                8 秒内自动刷新。
              </p>
            </div>
          </div>
          <div className="guide-boundaries">
            <strong>先把简单的事做好</strong>
            <p>
              当前支持上海时区的单次日程。手机应用可离线编辑，连接设备后自动合并；此网页的智能整理需要设备在线。循环、全天、自动找空档和外部日历同步暂不支持。
            </p>
          </div>
          <a
            className="repo-link"
            href="https://github.com/G-E-E-D/shunshoupai"
            target="_blank"
            rel="noreferrer"
          >
            查看源码与运行说明
            <ArrowUpRight size={15} />
          </a>
        </Modal>
      )}
    </div>
  );
}
