import { z } from "zod";
import { eventDurationMinutes, validateLocal } from "./time";

export const MAX_HEADS = 32;
export const MAX_ANCESTORS = 2048;
const MAX_DATE_MILLISECONDS = 8_640_000_000_000_000;

const uuid = z.string().uuid().transform(value => value.toLowerCase());
const localTime = z.string().regex(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}$/)
  .refine(validateLocal, "请填写 2000–2100 年内的有效上海时间。");

/** Values are immutable wire data: validation never trims or rewrites text. */
export const replicaPayloadSchema = z.object({
  title: z.string().min(1).max(120).refine(value => value.trim().length > 0, "日程标题不能为空。"),
  start_local: localTime,
  end_local: localTime,
  timezone: z.literal("Asia/Shanghai"),
  status: z.enum(["planned", "completed"]),
  deleted: z.boolean(),
}).strict().superRefine((payload, context) => {
  if (!validateLocal(payload.start_local) || !validateLocal(payload.end_local)) return;
  const duration = eventDurationMinutes(payload.start_local, payload.end_local);
  if (duration <= 0 || duration > 1440) context.addIssue({
    code: "custom", path: ["end_local"], message: "结束时间必须晚于开始时间，单次日程不能超过 24 小时。",
  });
});

export const replicaHeadSchema = z.object({
  revision_id: uuid,
  ancestors: z.array(uuid).max(MAX_ANCESTORS)
    .transform(values => [...new Set(values)].sort()),
  payload: replicaPayloadSchema,
  author: z.string().max(80),
  // Display metadata only. Clock skew never selects a winning revision.
  changed_at: z.number().int().min(-MAX_DATE_MILLISECONDS).max(MAX_DATE_MILLISECONDS),
}).strict().refine(head => !head.ancestors.includes(head.revision_id), {
  path: ["ancestors"], message: "版本不能以自己为祖先。",
});

export const replicaRecordSchema = z.object({
  id: uuid,
  heads: z.array(replicaHeadSchema).min(1).max(MAX_HEADS),
}).strict();
export const replicaSchema = replicaRecordSchema;

export type ReplicaPayload = z.infer<typeof replicaPayloadSchema>;
export type ReplicaHead = z.infer<typeof replicaHeadSchema>;
export type ReplicaRecord = z.infer<typeof replicaRecordSchema>;
export type ReplicaErrorCode = "REVISION_COLLISION" | "CAUSAL_CYCLE" | "INVALID_ANCESTRY" | "LIMIT_EXCEEDED" | "EVENT_ID_MISMATCH";

export class ReplicaError extends Error {
  constructor(public readonly code: ReplicaErrorCode, message: string) {
    super(message);
    this.name = "ReplicaError";
  }
}

const headsSchema = z.array(replicaHeadSchema).max(MAX_HEADS);

// These keys have an explicit order shared by every caller. Ancestors have
// already been sorted by the schema; incoming JSON property order is irrelevant.
function payloadKey(payload: ReplicaPayload): string {
  return JSON.stringify([
    payload.title, payload.start_local, payload.end_local,
    payload.timezone, payload.status, payload.deleted,
  ]);
}

function headKey(head: ReplicaHead): string {
  return JSON.stringify([head.revision_id, head.ancestors, payloadKey(head.payload), head.author, head.changed_at]);
}

function checkedUnion(heads: ReplicaHead[]): ReplicaHead[] {
  const byId = new Map<string, ReplicaHead>();
  for (const head of heads) {
    const previous = byId.get(head.revision_id);
    if (previous && headKey(previous) !== headKey(head)) {
      throw new ReplicaError("REVISION_COLLISION", "同一版本编号的内容发生变化，已停止合并并保留本机数据。");
    }
    if (!previous) byId.set(head.revision_id, head);
  }

  const visited = new Set<string>();
  const visiting = new Set<string>();
  function visit(id: string) {
    if (visiting.has(id)) throw new ReplicaError("CAUSAL_CYCLE", "版本祖先关系形成循环，已停止合并。");
    if (visited.has(id)) return;
    visiting.add(id);
    for (const ancestor of byId.get(id)!.ancestors) if (byId.has(ancestor)) visit(ancestor);
    visiting.delete(id);
    visited.add(id);
  }
  for (const id of byId.keys()) visit(id);

  for (const head of byId.values()) {
    const ancestors = new Set(head.ancestors);
    for (const id of ancestors) {
      const known = byId.get(id);
      if (known && known.ancestors.some(ancestor => !ancestors.has(ancestor))) {
        throw new ReplicaError("INVALID_ANCESTRY", "版本缺少已知的传递祖先，已停止合并。");
      }
    }
  }
  return [...byId.values()];
}

/**
 * Join two partial sets of heads. Missing records/heads never mean deletion.
 * Validation and collision checks precede pruning, so invalid input cannot
 * silently hide a local head. The caller must persist the result atomically.
 */
export function mergeHeads(local: readonly ReplicaHead[], incoming: readonly ReplicaHead[]): ReplicaHead[] {
  const union = checkedUnion([...headsSchema.parse(local), ...headsSchema.parse(incoming)]);
  const superseded = new Set(union.flatMap(head => head.ancestors));
  const result = union.filter(head => !superseded.has(head.revision_id))
    .sort((a, b) => a.revision_id < b.revision_id ? -1 : a.revision_id > b.revision_id ? 1 : 0);
  if (result.length > MAX_HEADS) {
    throw new ReplicaError("LIMIT_EXCEEDED", "并发版本过多，请先处理已有冲突；没有丢弃任何版本。");
  }
  return result;
}

export function mergeReplica(local: ReplicaRecord | undefined, incoming: ReplicaRecord): ReplicaRecord {
  const next = replicaRecordSchema.parse(incoming);
  const previous = local === undefined ? undefined : replicaRecordSchema.parse(local);
  if (previous && previous.id !== next.id) {
    throw new ReplicaError("EVENT_ID_MISMATCH", "不同日程不能合并成同一条记录。");
  }
  return { id: next.id, heads: mergeHeads(previous?.heads ?? [], next.heads) };
}

/**
 * baseHeads are the complete heads the user actually observed/confirmed.
 * Never replace them with newly received heads or display-only representatives.
 * IDs and timestamps come from the caller, making retries fully deterministic.
 */
export function createRevision(input: {
  baseHeads: readonly ReplicaHead[];
  revisionId: string;
  payload: ReplicaPayload;
  author: string;
  changedAt: number;
}): ReplicaHead {
  const base = mergeHeads(input.baseHeads, []);
  const revisionId = uuid.parse(input.revisionId);
  const ancestors = new Set(base.flatMap(head => [head.revision_id, ...head.ancestors]));
  if (ancestors.has(revisionId)) {
    throw new ReplicaError("REVISION_COLLISION", "新修改必须使用新的版本编号。");
  }
  if (ancestors.size > MAX_ANCESTORS) {
    throw new ReplicaError("LIMIT_EXCEEDED", "版本历史已达到上限，未截断或覆盖已有历史。");
  }
  return replicaHeadSchema.parse({
    revision_id: revisionId, ancestors: [...ancestors].sort(), payload: input.payload,
    author: input.author, changed_at: input.changedAt,
  });
}

/**
 * Display-only grouping. All underlying heads must remain in storage and be
 * included when resolving their shared payload. UUID order chooses a stable
 * representative inside a group, never the winning content of a conflict.
 */
export function distinctPayloadHeads(heads: readonly ReplicaHead[]): ReplicaHead[] {
  const groups = new Map<string, ReplicaHead>();
  for (const head of mergeHeads(heads, [])) {
    const key = payloadKey(head.payload);
    if (!groups.has(key)) groups.set(key, head);
  }
  return [...groups.values()];
}
