import { z } from "zod";
import { eventDurationMinutes, validateLocal } from "./time";

export const localDateTimeSchema = z.string().refine(validateLocal, "请填写 2000–2100 年内有效的日期和时间（上海时区）。");
export const eventDraftSchema = z.object({
  title: z.string().trim().min(1, "日程标题不能为空。").max(120, "标题最多 120 字。"),
  start_local: localDateTimeSchema,
  end_local: localDateTimeSchema,
  timezone: z.literal("Asia/Shanghai"),
  time_source: z.enum(["explicit", "suggested", "assumed"]),
  assumptions: z.array(z.string().trim().min(1).max(240)).max(10),
}).strict().superRefine((event, ctx) => {
  if (validateLocal(event.start_local) && validateLocal(event.end_local)) {
    const duration = eventDurationMinutes(event.start_local, event.end_local);
    if (duration <= 0 || duration > 24 * 60) ctx.addIssue({ code: "custom", path: ["end_local"], message: "结束时间必须晚于开始时间，单次日程不能超过 24 小时。" });
  }
  if (event.time_source === "assumed" && event.assumptions.length === 0) {
    ctx.addIssue({ code: "custom", path: ["assumptions"], message: "默认时间必须说明假设，供用户确认。" });
  }
});

export const authSchema = z.object({
  email: z.string().trim().toLowerCase().email("请输入有效邮箱。").max(254),
  password: z.string().min(8, "密码至少 8 位。").max(128, "密码最多 128 位。"),
}).strict();

export const parseInputSchema = z.object({
  text: z.string().trim().min(1, "先写下想安排的事吧。").max(2000, "一次最多输入 2000 字。"),
  timezone: z.literal("Asia/Shanghai").default("Asia/Shanghai"),
}).strict();

export const commitSchema = z.object({
  request_id: z.string().uuid("保存请求标识无效。"),
  events: z.array(eventDraftSchema).min(1, "至少确认一项日程。").max(5, "一次最多保存 5 项日程。"),
}).strict();

export const patchEventSchema = z.object({
  base_version: z.number().int().positive(),
  title: z.string().trim().min(1).max(120).optional(),
  start_local: localDateTimeSchema.optional(),
  end_local: localDateTimeSchema.optional(),
  status: z.enum(["planned", "completed"]).optional(),
}).strict().refine((value) => Object.keys(value).some((key) => key !== "base_version"), "请提供需要修改的字段。");

export const deleteEventSchema = z.object({ base_version: z.number().int().positive() }).strict();

export const modelResultSchema = z.object({
  intent: z.literal("create_events").optional(),
  needs_clarification: z.boolean(),
  questions: z.array(z.string().trim().min(1).max(400)).max(10),
  events: z.array(eventDraftSchema).max(5),
}).strict().superRefine((value, ctx) => {
  if (value.needs_clarification && value.questions.length === 0) ctx.addIssue({ code: "custom", message: "需要澄清时必须提供问题。" });
  if (!value.needs_clarification && (value.questions.length > 0 || value.events.length === 0)) ctx.addIssue({ code: "custom", message: "解析结果不完整，请补充时间后重试。" });
});
