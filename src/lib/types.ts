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
}
export interface ApiErrorBody {
  error: { code: string; message: string; details?: unknown };
}
