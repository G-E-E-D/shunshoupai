import { z } from "zod";

// Editing states can contain empty fields. Validate their shape without throwing
// away an unfinished edit; the normal API schema validates at save time.
const reviewSchema = z.object({
  events: z.array(z.object({
    title: z.string().max(120),
    start_local: z.string().max(19),
    end_local: z.string().max(19),
    timezone: z.literal("Asia/Shanghai"),
    time_source: z.enum(["explicit", "suggested", "assumed"]),
    assumptions: z.array(z.string().max(240)).max(10),
  })).max(5),
  needs_clarification: z.boolean(),
  questions: z.array(z.string().max(400)).max(10),
  warnings: z.array(z.string().max(1000)).max(20),
  provider: z.enum(["model", "rules"]),
});

const editorSchema = z.object({
  title: z.string().max(120),
  start: z.string().max(19),
  end: z.string().max(19),
  baseVersion: z.number().int().nonnegative(),
});

function read<T>(key: string, schema: z.ZodType<T>): T | null {
  try {
    const value = localStorage.getItem(key);
    if (!value) return null;
    const result = schema.safeParse(JSON.parse(value));
    return result.success ? result.data : null;
  } catch {
    return null;
  }
}

export const loadReviewDraft = (key: string) => read(key, reviewSchema);
export const loadEditorDraft = (key: string) => read(key, editorSchema);

export function saveLocalDraft(key: string, value: unknown): boolean {
  try {
    if (value === null) localStorage.removeItem(key);
    else localStorage.setItem(key, JSON.stringify(value));
    return true;
  } catch {
    return false;
  }
}
