import assert from "node:assert/strict";
import { test } from "node:test";
import { readFileSync } from "node:fs";
import {
  MAX_ANCESTORS, MAX_HEADS, ReplicaError, createRevision, distinctPayloadHeads,
  mergeHeads, mergeReplica, replicaHeadSchema, replicaPayloadSchema, replicaRecordSchema,
  type ReplicaErrorCode, type ReplicaHead, type ReplicaPayload,
} from "../src/lib/replica";

const id = (value: number) => `${value.toString(16).padStart(8, "0")}-1234-4abc-8abc-123456789abc`;
const payload = (title = "本机安排", deleted = false): ReplicaPayload => ({
  title, start_local: "2030-10-28T09:00:00", end_local: "2030-10-28T10:00:00",
  timezone: "Asia/Shanghai", status: "planned", deleted,
});
const head = (value: number, baseHeads: ReplicaHead[] = [], title = `安排 ${value}`, deleted = false) => createRevision({
  baseHeads, revisionId: id(value), payload: payload(title, deleted), author: "测试设备", changedAt: 1_800_000_000_000 + value,
});
const isCode = (code: ReplicaErrorCode) => (error: unknown) => error instanceof ReplicaError && error.code === code;

test("the same protocol fixtures produce identical canonical heads in TypeScript and Android", () => {
  const fixtures = JSON.parse(readFileSync(new URL("../android/app/src/androidTest/assets/replica-v1.json", import.meta.url), "utf8")) as {
    valid: { name: string; local: ReplicaHead[]; incoming: ReplicaHead[]; expected: ReplicaHead[]; distinct: number }[];
    invalid: { name: string; local: ReplicaHead[]; incoming: ReplicaHead[]; code: ReplicaErrorCode }[];
  };
  for (const item of fixtures.valid) {
    const merged = mergeHeads(item.local, item.incoming);
    assert.deepEqual(merged, item.expected, item.name);
    assert.equal(distinctPayloadHeads(merged).length, item.distinct, item.name);
  }
  for (const item of fixtures.invalid) assert.throws(() => mergeHeads(item.local, item.incoming), isCode(item.code), item.name);
});

test("revision creation records the complete ancestor set without depending on wall clock order", () => {
  const a = head(1);
  const b = head(2, [a]);
  const c = createRevision({ baseHeads: [b], revisionId: id(3), payload: payload(), author: "时钟落后的设备", changedAt: -1 });
  assert.deepEqual(c.ancestors, [id(1), id(2)]);
  assert.deepEqual(mergeHeads([a, b], [c]), [c]);
  assert.equal(c.changed_at, -1);
});

test("merging valid histories is commutative, associative and idempotent", () => {
  const root = head(1);
  const a = head(2, [root]);
  const b = head(3, [root]);
  const c = head(4, [a]);
  const states = [[], [root], [a], [b], [c], [a, b], [b, c]];
  for (const first of states) {
    assert.deepEqual(mergeHeads(first, first), mergeHeads(first, []));
    for (const second of states) {
      assert.deepEqual(mergeHeads(first, second), mergeHeads(second, first));
      for (const third of states) {
        assert.deepEqual(
          mergeHeads(mergeHeads(first, second), third),
          mergeHeads(first, mergeHeads(second, third)),
        );
      }
    }
  }
});

test("generated branching histories converge regardless of packet grouping and arrival order", () => {
  const revisions: ReplicaHead[] = [head(1)];
  for (let index = 2; index <= 12; index++) {
    const bases = revisions.filter((_, position) => (position + index) % 3 === 0);
    revisions.push(head(index, bases));
  }
  const expected = mergeHeads(revisions, []);
  for (let offset = 0; offset < revisions.length; offset++) {
    const order = [...revisions.slice(offset), ...revisions.slice(0, offset)].reverse();
    let state: ReplicaHead[] = [];
    for (const revision of order) state = mergeHeads(state, [revision]);
    assert.deepEqual(state, expected);
  }
});

test("three devices exchange around a loop without losing concurrent edits or a deletion", () => {
  const root = head(1);
  let phoneA = [head(2, [root], "手机 A 修改")];
  let phoneB = [head(3, [root], "手机 B 修改")];
  let desktop = [head(4, [root], "原安排", true)];
  const expected = mergeHeads([...phoneA, ...phoneB], desktop);
  for (let round = 0; round < 3; round++) {
    phoneB = mergeHeads(phoneB, phoneA);
    desktop = mergeHeads(desktop, phoneB);
    phoneA = mergeHeads(phoneA, desktop);
  }
  assert.deepEqual(phoneA, expected);
  assert.deepEqual(phoneB, expected);
  assert.deepEqual(desktop, expected);
  assert.equal(expected.length, 3);
  assert.equal(expected.filter(item => item.payload.deleted).length, 1);
  assert.deepEqual(mergeHeads(expected, [root]), expected);
});

test("explicit resolution supersedes only the revisions the user actually observed", () => {
  const root = head(1);
  const a = head(2, [root], "手机稿");
  const b = head(3, [root], "电脑稿");
  const resolved = head(4, [a, b], "用户确认的安排");
  assert.deepEqual(mergeHeads([a, b], [resolved]), [resolved]);
  const late = head(5, [b], "确认期间对方又改过");
  assert.deepEqual(mergeHeads([resolved], [late]), [resolved, late]);
});

test("restoring an old backup and writing offline creates a concurrent head instead of losing the new edit", () => {
  const backup = head(1);
  const newer = head(2, [backup]);
  const latest = head(3, [newer]);
  const restoredEdit = createRevision({
    baseHeads: [backup], revisionId: id(4), payload: payload("恢复备份后的新改动"),
    author: "同一设备", changedAt: backup.changed_at,
  });
  assert.deepEqual(mergeHeads([latest], [restoredEdit]), [latest, restoredEdit]);
});

test("deletion and concurrent modification remain distinct until explicitly resolved", () => {
  const original = head(1, [], "会议");
  const deletion = head(2, [original], "会议", true);
  const edit = head(3, [original], "会议改期");
  assert.equal(distinctPayloadHeads(mergeHeads([deletion], [edit])).length, 2);
  const chosenDelete = head(4, [deletion, edit], "会议", true);
  assert.deepEqual(mergeHeads([deletion, edit], [chosenDelete]), [chosenDelete]);
  assert.deepEqual(mergeHeads([chosenDelete], [original]), [chosenDelete]);
});

test("equal payloads keep all causal heads while the display groups them deterministically", () => {
  const a = head(1, [], "一样的安排");
  const b = { ...head(2, [], "一样的安排"), author: "另一台手机", changed_at: 0 };
  const all = mergeHeads([b], [a]);
  assert.equal(all.length, 2);
  assert.deepEqual(distinctPayloadHeads([b, a]), [a]);
  const resolved = head(3, all, "用户接着修改");
  assert.deepEqual(mergeHeads(all, [resolved]), [resolved]);
  // Choosing only the visible representative is intentionally not a resolution
  // of the hidden branch; callers must supply the complete observed head set.
  const incomplete = head(4, distinctPayloadHeads(all), "只观察一个分支");
  assert.deepEqual(mergeHeads(all, [incomplete]), [b, incomplete]);
});

test("canonical UUID casing and ancestor order do not create false revision collisions", () => {
  const a = head(1);
  const b = head(2, [a]);
  const c = head(3, [b]);
  const differentlyEncoded = { ...c, revision_id: c.revision_id.toUpperCase(), ancestors: [id(2).toUpperCase(), id(1), id(1)] };
  assert.deepEqual(mergeHeads([c], [differentlyEncoded]), [c]);
});

test("revision IDs cannot be reused with changed content, ancestry or display metadata", () => {
  const a = head(1);
  const variants = [
    { ...a, payload: payload("篡改内容") },
    { ...a, ancestors: [id(99)] },
    { ...a, author: "改了来源" },
    { ...a, changed_at: a.changed_at + 1 },
  ];
  for (const variant of variants) assert.throws(() => mergeHeads([a], [variant]), isCode("REVISION_COLLISION"));
  const descendant = head(2, [a]);
  assert.throws(() => mergeHeads([a, descendant], [variants[0]]), isCode("REVISION_COLLISION"));
});

test("known ancestor relations must be transitive before any head is pruned", () => {
  const a = head(1);
  const b = head(2, [a]);
  const incomplete = { ...head(3), ancestors: [b.revision_id] };
  assert.throws(() => mergeHeads([b], [incomplete]), isCode("INVALID_ANCESTRY"));
  // A packet need not include every historical revision body.
  assert.equal(mergeHeads([], [head(3, [b])]).length, 1);
});

test("cycles and self ancestry are rejected without choosing an arbitrary UUID winner", () => {
  const a = { ...head(1), ancestors: [id(2)] };
  const b = { ...head(2), ancestors: [id(3)] };
  const c = { ...head(3), ancestors: [id(1)] };
  assert.throws(() => mergeHeads([a, b], [c]), isCode("CAUSAL_CYCLE"));
  assert.equal(replicaHeadSchema.safeParse({ ...head(1), ancestors: [id(1)] }).success, false);
  assert.throws(() => createRevision({ baseHeads: [head(1)], revisionId: id(1), payload: payload(), author: "设备", changedAt: 0 }), isCode("REVISION_COLLISION"));
});

test("head limits reject excess concurrent state rather than truncating edits", () => {
  const local = Array.from({ length: MAX_HEADS }, (_, index) => head(index + 1));
  assert.equal(mergeHeads(local, []).length, MAX_HEADS);
  assert.throws(() => mergeHeads(local, [head(100)]), isCode("LIMIT_EXCEEDED"));
  const resolution = head(100, local);
  assert.deepEqual(mergeHeads(local, [resolution]), [resolution]);
  assert.equal(replicaRecordSchema.safeParse({ id: id(999), heads: [...local, head(101)] }).success, false);
});

test("ancestry limits stop writes without deleting history", () => {
  const ancestors = Array.from({ length: MAX_ANCESTORS }, (_, index) => id(index + 1));
  const full = { ...head(9000), ancestors };
  assert.equal(replicaHeadSchema.safeParse(full).success, true);
  assert.equal(replicaHeadSchema.safeParse({ ...full, ancestors: [...ancestors, id(8000)] }).success, false);
  assert.throws(() => head(9001, [full]), isCode("LIMIT_EXCEEDED"));
  const oneShort = { ...full, ancestors: ancestors.slice(1) };
  assert.equal(head(9001, [oneShort]).ancestors.length, MAX_ANCESTORS);
});

test("record IDs remain independent and empty records cannot impersonate deletion", () => {
  const record = { id: id(90), heads: [head(1)] };
  assert.deepEqual(mergeReplica(undefined, record), record);
  assert.deepEqual(mergeReplica(record, record), record);
  assert.throws(() => mergeReplica(record, { ...record, id: id(91) }), isCode("EVENT_ID_MISMATCH"));
  assert.equal(replicaRecordSchema.safeParse({ ...record, heads: [] }).success, false);
  assert.deepEqual(mergeHeads(record.heads, []), record.heads);
});

test("partial head packets merge without treating omissions as deleted branches", () => {
  const a = head(1);
  const b = head(2);
  const first = mergeReplica(undefined, { id: id(90), heads: [a] });
  const second = mergeReplica(first, { id: id(90), heads: [b] });
  assert.deepEqual(second.heads, [a, b]);
  assert.deepEqual(mergeReplica(second, first), second);
});

test("wire schema rejects malformed and oversized values but preserves intended whitespace", () => {
  const value = payload("  有意保留的文字  ");
  assert.equal(replicaPayloadSchema.parse(value).title, value.title);
  for (const invalid of [
    { ...value, title: " " },
    { ...value, title: "字".repeat(121) },
    { ...value, start_local: "2030-10-28T09:00" },
    { ...value, start_local: "2030-02-30T09:00:00" },
    { ...value, end_local: "2030-10-28T09:00:00" },
    { ...value, end_local: "2030-10-30T09:00:00" },
    { ...value, timezone: "UTC" },
    { ...value, deleted: "false" },
    { ...value, status: "unknown" },
    { ...value, unexpected: true },
  ]) assert.equal(replicaPayloadSchema.safeParse(invalid).success, false);
  for (const invalid of [
    { ...head(1), author: "x".repeat(81) },
    { ...head(1), changed_at: Infinity },
    { ...head(1), changed_at: 1.5 },
    { ...head(1), changed_at: Number.MAX_SAFE_INTEGER },
    { ...head(1), revision_id: "not-a-uuid" },
    { ...head(1), ancestors: ["not-a-uuid"] },
  ]) assert.equal(replicaHeadSchema.safeParse(invalid).success, false);
});

function freezeDeep(value: unknown) {
  if (value && typeof value === "object") {
    Object.freeze(value);
    for (const child of Object.values(value)) freezeDeep(child);
  }
}

test("merge and revision creation never mutate inputs or return their mutable references", () => {
  const a = head(1);
  const b = head(2, [a]);
  freezeDeep(a); freezeDeep(b);
  const original = JSON.stringify([a, b]);
  const result = mergeHeads([a], [b]);
  result[0].payload.title = "仅修改返回值";
  result[0].ancestors.push(id(99));
  const nextPayload = payload(); freezeDeep(nextPayload);
  const created = createRevision({ baseHeads: [b], revisionId: id(3), payload: nextPayload, author: "设备", changedAt: 0 });
  created.payload.title = "另一返回值";
  assert.equal(nextPayload.title, "本机安排");
  assert.equal(JSON.stringify([a, b]), original);
});
