"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { Check, LoaderCircle, RefreshCw } from "lucide-react";
import { api, ApiError, errorMessage } from "@/lib/client";
import type { ScheduleEvent } from "@/lib/types";

export default function ReplicaConflict({ eventId, onResolved, onBusyChange }: {
  eventId: string;
  onResolved: () => void;
  onBusyChange: (busy: boolean) => void;
}) {
  const [event, setEvent] = useState<ScheduleEvent | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");
  const active = useRef(true);
  const request = useRef(0);
  const action = useRef(false);

  const load = useCallback(async (message = "") => {
    const current = ++request.current;
    setLoading(true);
    setSelected(null);
    try {
      const result = await api<{ event: ScheduleEvent }>(`/api/events/${eventId}`);
      if (!active.current || current !== request.current) return;
      setEvent(result.event);
      setError(message);
    } catch (failure) {
      if (active.current && current === request.current) setError(errorMessage(failure));
    } finally {
      if (active.current && current === request.current) setLoading(false);
    }
  }, [eventId]);
  useEffect(() => {
    active.current = true;
    // eslint-disable-next-line react-hooks/set-state-in-effect -- Fetch the current conflict and its local CAS version before showing choices.
    void load();
    return () => { active.current = false; };
  }, [load]);

  async function resolve() {
    if (!event || !selected || loading || action.current) return;
    action.current = true; setSaving(true); onBusyChange(true); setError("");
    try {
      await api(`/api/events/${eventId}/resolve`, {
        method: "POST", body: JSON.stringify({ base_version: event.version, revision_id: selected }),
      });
      if (active.current) onResolved();
    } catch (failure) {
      if (!active.current) return;
      if (failure instanceof ApiError && failure.status === 409) {
        await load("又收到了新的修改，请查看全部版本后重新选择。你的上次选择没有覆盖新内容。");
      } else setError(errorMessage(failure));
    } finally {
      action.current = false; onBusyChange(false);
      if (active.current) setSaving(false);
    }
  }

  const variants = event?.replica_conflicts ?? [];
  return (
    <div className="replica-resolution">
      <p className="settings-note">不同设备的修改都已保留。请选择最终内容，确认后会生成一个新版本；未选之前不会覆盖或删除任何版本。</p>
      {loading ? <p role="status"><LoaderCircle size={18} className="spin" /> 正在读取最新版本…</p> : !event ? (
        <p className="settings-note">暂未读取到版本，恢复连接后可重新读取。原有内容没有改变。</p>
      ) : variants.length < 2 ? (
        <div className="inline-info">这项日程的冲突已处理。<button className="text-button" onClick={onResolved}>返回日程</button></div>
      ) : (
        <fieldset className="replica-choices" disabled={saving}>
          <legend>选择要保留的版本</legend>
          {variants.map(variant => (
            <label className={`replica-choice ${selected === variant.revision_id ? "is-selected" : ""}`} key={variant.revision_id}>
              <input type="radio" name="replica-version" value={variant.revision_id}
                aria-label={`选择版本：${variant.author || "未命名设备"}：${variant.payload.title}${variant.payload.deleted ? "（删除）" : ""}`}
                checked={selected === variant.revision_id} onChange={() => setSelected(variant.revision_id)} />
              <span>
                <strong>{variant.payload.title}</strong>
                <span>{variant.payload.start_local.replace("T", " ")} — {variant.payload.end_local.replace("T", " ")}</span>
                <span className={variant.payload.deleted ? "danger-text" : ""}>{variant.payload.deleted ? "删除此日程" : variant.payload.status === "completed" ? "已完成" : "待进行"}</span>
                <small>{variant.author || "未命名设备"} · {new Date(variant.changed_at).toLocaleString("zh-CN", { timeZone: "Asia/Shanghai" })}</small>
              </span>
            </label>
          ))}
        </fieldset>
      )}
      {error && <div className="inline-error" role="alert">{error}</div>}
      <div className="modal-actions">
        <button className="secondary-button" disabled={saving || loading} onClick={() => void load()}><RefreshCw size={16} />重新读取版本</button>
        <button className="primary-button" disabled={saving || loading || !selected || variants.length < 2} onClick={() => void resolve()}>
          {saving ? <LoaderCircle size={16} className="spin" /> : <Check size={16} />}使用所选版本
        </button>
      </div>
    </div>
  );
}
