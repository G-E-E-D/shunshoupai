export type TimeSource = "explicit" | "suggested" | "assumed";
export interface EventDraft {
  title: string;
  start_local: string;
  end_local: string;
  timezone: "Asia/Shanghai";
  time_source: TimeSource;
  assumptions: string[];
}
export interface ScheduleEvent {
  replica_conflicts?: Pick<import("./replica").ReplicaHead, "revision_id" | "author" | "changed_at" | "payload">[];
  id: string;
  title: string;
  start_at: string;
  end_at: string;
  timezone: string;
  status: "planned" | "completed";
  version: number;
  created_at: string;
  updated_at: string;
  deleted_at: string | null;
}
export interface ParseResult {
  events: EventDraft[];
  needs_clarification: boolean;
  questions: string[];
  warnings: string[];
  provider: "model" | "rules";
}
export interface SessionInfo {
  user: { id: string; email: string } | null;
  mode: "local" | "supabase";
  ai_provider: "model" | "rules";
  pairing_limited?: boolean;
  session_expires_at?: string | null;
  local_first?: boolean;
  role?: "owner" | "peer";
  space_id?: string;
  peer_id?: string;
  device?: { id: string; name: string };
  peer?: { id: string; name: string; platform: string };
  legacy_space_ids?: string[];
}
export interface ModelSettingsInfo {
  configured: boolean;
  base_url: string;
  model: string;
  has_api_key: boolean;
  key_hint: string | null;
  version: number;
  updated_at: string | null;
  effective_source: "personal" | "server" | "rules";
  encryption_available: boolean;
}
export interface ApiErrorBody {
  error: { code: string; message: string; details?: unknown };
}
