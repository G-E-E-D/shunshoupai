import { z } from "zod";
import { localDateTimeSchema } from "./schemas";
import { eventDurationMinutes, validateLocal } from "./time";
import type { ScheduleEvent } from "./types";

const uuid = z.string().uuid().transform((value) => value.toLowerCase());
const syncEventSchema = z.object({
  title: z.string().trim().min(1).max(120),
  start_local: localDateTimeSchema,
  end_local: localDateTimeSchema,
  timezone: z.literal("Asia/Shanghai"),
  status: z.enum(["planned", "completed"]),
}).strict().refine((event) => {
  if (!validateLocal(event.start_local) || !validateLocal(event.end_local)) return false;
  const duration = eventDurationMinutes(event.start_local, event.end_local);
  return duration > 0 && duration <= 1440;
}, "结束时间必须晚于开始时间，单次日程不能超过 24 小时。");

export const syncMutationSchema = z.object({
  mutation_id: uuid,
  id: uuid,
  base_version: z.number().int().min(0).max(2_147_483_646),
  deleted: z.boolean(),
  event: syncEventSchema.optional(),
}).strict().refine((mutation) => mutation.deleted ? !mutation.event && mutation.base_version > 0 : !!mutation.event,
  "请提供完整日程，或指定要删除的已同步日程版本。");

export const syncRequestSchema = z.object({
  account_id: uuid,
  mutations: z.array(syncMutationSchema).max(20),
  after: uuid.optional(),
}).strict().refine((request) => new Set(request.mutations.map((item) => item.mutation_id)).size === request.mutations.length,
  "同步请求编号不能重复。");

export type SyncMutation = z.infer<typeof syncMutationSchema>;
export type SyncRequest = z.infer<typeof syncRequestSchema>;
export type SyncResult = {
  mutation_id: string;
  status: "applied" | "conflict";
  event?: ScheduleEvent;
  code?: string;
  message?: string;
};
export type SyncResponse = {
  results: SyncResult[];
  events: ScheduleEvent[];
  has_more: boolean;
  next_cursor: string | null;
};
