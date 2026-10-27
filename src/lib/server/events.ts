import type { EventDraft, ScheduleEvent } from "../types";
import type { AuthContext } from "./auth";
import { databaseError, AppError } from "./errors";
import { localStore } from "./local-store";

export async function getEvent(auth: AuthContext, id: string): Promise<ScheduleEvent> {
  if (!auth.supabase) return localStore().event(auth.user.id, id);
  const { data, error } = await auth.supabase.from("events").select("id,title,start_at,end_at,timezone,status,version,created_at,updated_at,deleted_at")
    .eq("user_id", auth.user.id).eq("id", id).maybeSingle();
  if (error) databaseError(error);
  if (!data) throw new AppError("NOT_FOUND", "日程不存在或已删除。", 404);
  return data as ScheduleEvent;
}

export async function listEvents(auth: AuthContext, from: string, to: string): Promise<ScheduleEvent[]> {
  if (!auth.supabase) return localStore().list(auth.user.id, from, to);
  const { data, error } = await auth.supabase.from("events").select("id,title,start_at,end_at,timezone,status,version,created_at,updated_at,deleted_at")
    .eq("user_id", auth.user.id).is("deleted_at", null).lt("start_at", to).gt("end_at", from).order("start_at").limit(501);
  if (error) databaseError(error);
  if ((data?.length ?? 0) > 500) throw new AppError("TOO_MANY_EVENTS", "这个时间范围内日程过多，请缩小查询范围。", 400);
  return (data ?? []) as ScheduleEvent[];
}

export async function commitEvents(auth: AuthContext, requestId: string, drafts: EventDraft[]) {
  if (!auth.supabase) return localStore().commit(auth.user.id, requestId, drafts);
  const { data, error } = await auth.supabase.rpc("commit_events", { p_request_id: requestId, p_events: drafts });
  if (error) databaseError(error);
  return data as { events: ScheduleEvent[]; replayed: boolean };
}

export async function mutateEvent(auth: AuthContext, id: string, baseVersion: number, patch: {
  title?: string; start_local?: string; end_local?: string; status?: "planned" | "completed";
}, deleting = false) {
  if (!auth.supabase) return localStore().mutate(auth.user.id, id, baseVersion, patch, deleting);
  const { data, error } = await auth.supabase.rpc("mutate_event", { p_id: id, p_base_version: baseVersion, p_patch: patch, p_delete: deleting });
  if (error) databaseError(error);
  return data as { event: ScheduleEvent };
}

export async function consumeAiQuota(auth: AuthContext) {
  const configured = Number(process.env.AI_DAILY_LIMIT || 50);
  const limit = Number.isFinite(configured) ? Math.max(1, Math.min(100, Math.floor(configured))) : 50;
  if (!auth.supabase) {
    if (!localStore().consumeAiQuota(auth.user.id, limit)) throw new AppError("AI_QUOTA_EXCEEDED", "今天的解析次数已用完，仍可手工创建日程。", 429);
    return;
  }
  const { data, error } = await auth.supabase.rpc("consume_ai_quota", { p_limit: limit });
  if (error) databaseError(error);
  if (!data) throw new AppError("AI_QUOTA_EXCEEDED", "今天的解析次数已用完，仍可手工创建日程。", 429);
}
