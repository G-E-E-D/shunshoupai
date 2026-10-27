import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { mkdtempSync, readdirSync, rmSync, rmdirSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";
import { syncRequestSchema, type SyncMutation } from "../src/lib/sync";
import { AppError } from "../src/lib/server/errors";
import { LOCAL_SESSION_MAX_AGE, LocalStore } from "../src/lib/server/local-store";

const day = 86_400_000;
const create = (title = "手机日程"): SyncMutation => ({
  mutation_id: randomUUID(), id: randomUUID(), base_version: 0, deleted: false,
  event: { title, start_local: "2026-10-28T09:00:00", end_local: "2026-10-28T10:00:00", timezone: "Asia/Shanghai", status: "planned" },
});
const isCode = (code: string) => (error: unknown) => error instanceof AppError && error.code === code;
function setup(run: (store: LocalStore, id: string) => void) {
  const store = new LocalStore(":memory:");
  try { run(store, store.register("sync@example.com", "sufficient-password").id); }
  finally { store.close(); }
}

test("local sessions renew daily while active, survive idle gaps, and never revive after expiry or logout", () => setup((store, id) => {
  const now = Date.now();
  assert.equal(LOCAL_SESSION_MAX_AGE, 30 * 86400);
  const token = store.createSession(id, now);
  assert.equal(store.refreshSession(token, now + 1000)?.renewed, false);
  assert.equal(store.refreshSession(token, now + 25 * day)?.renewed, true);
  assert.equal(store.sessionUser(token, now + 40 * day)?.id, id);
  assert.equal(store.refreshSession(token, now + 55 * day), null);
  const active = store.createSession(id, now);
  store.deleteSession(active);
  assert.equal(store.refreshSession(active, now + day), null);
}));

test("independent offline and desktop schedules merge including overlaps without duplication", () => setup((store, id) => {
  const phone = create();
  const desktop = store.commit(id, randomUUID(), [{ ...phone.event!, title: "电脑日程", time_source: "explicit", assumptions: [] }]).events[0];
  const request = { account_id: id, mutations: [phone] };
  const merged = store.sync(id, request);
  assert.equal(merged.results[0].status, "applied");
  assert.equal(merged.events.length, 2);
  assert.deepEqual(store.sync(id, request), merged);
  // An imported overlap does not block unrelated title edits on the desktop.
  assert.equal(store.mutate(id, desktop.id, 1, { title: "电脑改标题" }).event.title, "电脑改标题");
}));

test("two-device edits and delete-versus-edit conflicts preserve server truth and return the current version", () => setup((store, id) => {
  const original = create();
  store.sync(id, { account_id: id, mutations: [original] });
  const server = store.mutate(id, original.id, 1, { title: "电脑已修改" }).event;
  const stale = { ...original, mutation_id: randomUUID(), base_version: 1 };
  const conflict = store.sync(id, { account_id: id, mutations: [stale] });
  assert.equal(conflict.results[0].code, "VERSION_CONFLICT");
  assert.deepEqual(conflict.results[0].event, server);
  assert.equal(conflict.events[0].title, "电脑已修改");
  const deleted = store.mutate(id, original.id, 2, {}, true).event;
  assert.deepEqual(store.event(id, original.id), deleted);
  const tombstone = store.sync(id, { account_id: id, mutations: [{ ...stale, mutation_id: randomUUID(), base_version: 2 }] });
  assert.equal(tombstone.results[0].code, "VERSION_CONFLICT");
  assert.deepEqual(tombstone.events[0], deleted);
}));

test("offline completion, edit and delete are replay-safe and tombstones remain available", () => setup((store, id) => {
  const original = create();
  store.sync(id, { account_id: id, mutations: [original] });
  const edit: SyncMutation = { ...original, mutation_id: randomUUID(), base_version: 1, event: { ...original.event!, title: "已完成", status: "completed" } };
  const edited = store.sync(id, { account_id: id, mutations: [edit] });
  assert.equal(edited.events[0].version, 2);
  assert.equal(edited.events[0].status, "completed");
  const deletion: SyncMutation = { id: original.id, mutation_id: randomUUID(), base_version: 2, deleted: true };
  const result = store.sync(id, { account_id: id, mutations: [deletion] });
  assert.equal(result.events[0].version, 3);
  assert.ok(result.events[0].deleted_at);
  assert.deepEqual(store.sync(id, { account_id: id, mutations: [deletion] }), result);
  assert.equal(store.list(id, "2026-10-27T00:00:00Z", "2026-10-29T00:00:00Z").length, 0);
}));

test("an unresolved conflict does not prevent other phone changes from syncing", () => setup((store, id) => {
  const original = create();
  store.sync(id, { account_id: id, mutations: [original] });
  store.mutate(id, original.id, 1, { title: "电脑版" });
  const independent = create("另一件事");
  const response = store.sync(id, { account_id: id, mutations: [{ ...original, mutation_id: randomUUID(), base_version: 1 }, independent] });
  assert.deepEqual(response.results.map((item) => item.status), ["conflict", "applied"]);
  assert.equal(response.events.length, 2);
}));

test("account switches and forged IDs cannot disclose or mutate another account's schedules", () => setup((store, id) => {
  const other = store.register("other@example.com", "sufficient-password").id;
  const original = create();
  store.sync(id, { account_id: id, mutations: [original] });
  assert.throws(() => store.sync(other, { account_id: id, mutations: [] }), isCode("ACCOUNT_MISMATCH"));
  const collision = store.sync(other, { account_id: other, mutations: [original] });
  assert.equal(collision.results[0].code, "ID_CONFLICT");
  assert.equal(collision.results[0].event, undefined);
  assert.equal(collision.events.length, 0);
  assert.throws(() => store.event(other, original.id), isCode("NOT_FOUND"));
  const forged = store.sync(other, { account_id: other, mutations: [{ id: original.id, mutation_id: randomUUID(), base_version: 1, deleted: true }] });
  assert.equal(forged.results[0].code, "NOT_FOUND");
  assert.equal(forged.results[0].event, undefined);
}));

test("changed idempotency payload is rejected atomically; malformed inputs never partly apply", () => setup((store, id) => {
  const original = create();
  store.sync(id, { account_id: id, mutations: [original] });
  assert.throws(() => store.sync(id, { account_id: id, mutations: [create(), { ...original, event: { ...original.event!, title: "被改过" } }] }), isCode("IDEMPOTENCY_CONFLICT"));
  assert.equal(store.sync(id, { account_id: id, mutations: [] }).events.length, 1);
  assert.equal(syncRequestSchema.safeParse({ account_id: id, mutations: [{ ...original, event: { ...original.event!, start_local: "bad-date" } }] }).success, false);
  assert.equal(syncRequestSchema.safeParse({ account_id: id, mutations: [original, original] }).success, false);
  assert.equal(syncRequestSchema.safeParse({ account_id: id, mutations: Array.from({ length: 21 }, () => create()) }).success, false);
}));

test("snapshot pagination includes all history and tombstones without accidental deletions", () => setup((store, id) => {
  for (let n = 0; n < 11; n++) store.sync(id, { account_id: id, mutations: Array.from({ length: 20 }, () => create()) });
  const first = store.sync(id, { account_id: id, mutations: [] });
  assert.equal(first.events.length, 200);
  assert.equal(first.has_more, true);
  assert.ok(first.next_cursor);
  const second = store.sync(id, { account_id: id, mutations: [], after: first.next_cursor });
  assert.equal(second.events.length, 20);
  assert.equal(second.has_more, false);
  assert.equal(second.next_cursor, null);
  assert.equal(new Set([...first.events, ...second.events].map((event) => event.id)).size, 220);
}));

test("a lost sync response can be retried after a server restart", () => {
  const directory = mkdtempSync(join(tmpdir(), "shunshoupai-sync-"));
  const filename = join(directory, "sync.sqlite");
  let store = new LocalStore(filename);
  try {
    const user = store.register("restart@example.com", "sufficient-password");
    const request = { account_id: user.id, mutations: [create()] };
    const first = store.sync(user.id, request);
    store.close();
    store = new LocalStore(filename);
    assert.deepEqual(store.sync(user.id, request), first);
  } finally {
    store.close();
    for (const file of readdirSync(directory)) rmSync(join(directory, file));
    rmdirSync(directory);
  }
});
