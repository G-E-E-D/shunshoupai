import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { mkdtempSync, readdirSync, rmSync, rmdirSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";
import type { EventDraft } from "../src/lib/types";
import { LocalStore } from "../src/lib/server/local-store";
import { AppError } from "../src/lib/server/errors";
import { rateLimit, sameOrigin, readJson, validateWindow } from "../src/lib/server/http";

const from = "2026-10-26T16:00:00.000Z";
const to = "2026-11-01T16:00:00.000Z";
const draft = (title = "面试", start = "2026-10-28T15:00:00", end = "2026-10-28T16:00:00"): EventDraft => ({
  title, start_local: start, end_local: end, timezone: "Asia/Shanghai", time_source: "explicit", assumptions: [],
});
function expectCode(code: string) {
  return (error: unknown) => error instanceof AppError && error.code === code;
}
function withStore(run: (store: LocalStore) => void) {
  const store = new LocalStore(":memory:");
  try { run(store); } finally { store.close(); }
}

test("accounts authenticate with salted passwords and opaque revocable sessions", () => withStore((store) => {
  const user = store.register("Person@Example.com", "sufficient-password");
  assert.equal(user.email, "person@example.com");
  assert.deepEqual(store.login("PERSON@example.com", "sufficient-password"), user);
  assert.throws(() => store.login(user.email, "wrong-password"), expectCode("INVALID_CREDENTIALS"));
  assert.throws(() => store.login("missing@example.com", "wrong-password"), expectCode("INVALID_CREDENTIALS"));
  assert.throws(() => store.register(user.email, "another-password"), expectCode("EMAIL_EXISTS"));
  const token = store.createSession(user.id);
  assert.equal(token.length, 43);
  assert.deepEqual(store.sessionUser(token), user);
  assert.equal(store.sessionUser(`${token}tampered`), null);
  store.deleteSession(token);
  assert.equal(store.sessionUser(token), null);
}));

test("idempotent batch save returns the original result and rejects changed payload", () => withStore((store) => {
  const user = store.register("idem@example.com", "sufficient-password");
  const requestId = randomUUID();
  const first = store.commit(user.id, requestId, [draft()]);
  assert.equal(first.replayed, false);
  assert.equal(first.events[0].start_at, "2026-10-28T07:00:00.000Z");
  const replay = store.commit(user.id, requestId, [draft()]);
  assert.equal(replay.replayed, true);
  assert.deepEqual(replay.events, first.events);
  assert.equal(store.list(user.id, from, to).length, 1);
  assert.throws(() => store.commit(user.id, requestId, [draft("改变了内容")]), expectCode("IDEMPOTENCY_CONFLICT"));
}));

test("overlap rolls back the whole transaction, adjacent events are accepted", () => withStore((store) => {
  const user = store.register("atomic@example.com", "sufficient-password");
  const requestId = randomUUID();
  assert.throws(() => store.commit(user.id, requestId, [draft(), draft("重叠", "2026-10-28T15:30:00", "2026-10-28T16:30:00")]), expectCode("TIME_CONFLICT"));
  assert.equal(store.list(user.id, from, to).length, 0);
  const saved = store.commit(user.id, requestId, [draft(), draft("相邻", "2026-10-28T16:00:00", "2026-10-28T17:00:00")]);
  assert.equal(saved.events.length, 2);
  assert.equal(saved.replayed, false);
}));

test("users cannot read, update or delete another user's data, but can share a time slot", () => withStore((store) => {
  const a = store.register("a@example.com", "sufficient-password");
  const b = store.register("b@example.com", "sufficient-password");
  const event = store.commit(a.id, randomUUID(), [draft()]).events[0];
  assert.equal(store.list(b.id, from, to).length, 0);
  assert.throws(() => store.mutate(b.id, event.id, 1, { title: "越权" }), expectCode("NOT_FOUND"));
  assert.throws(() => store.mutate(b.id, event.id, 1, {}, true), expectCode("NOT_FOUND"));
  assert.equal(store.commit(b.id, randomUUID(), [draft()]).events.length, 1);
  assert.equal(store.list(a.id, from, to)[0].title, "面试");
}));

test("optimistic versions stop stale overwrites and stale deletes", () => withStore((store) => {
  const user = store.register("version@example.com", "sufficient-password");
  const original = store.commit(user.id, randomUUID(), [draft()]).events[0];
  const updated = store.mutate(user.id, original.id, 1, { title: "新面试" }).event;
  assert.equal(updated.version, 2);
  assert.throws(() => store.mutate(user.id, original.id, 1, { title: "旧端覆盖" }), expectCode("VERSION_CONFLICT"));
  assert.throws(() => store.mutate(user.id, original.id, 1, {}, true), expectCode("VERSION_CONFLICT"));
  assert.equal(store.list(user.id, from, to)[0].title, "新面试");
  const deleted = store.mutate(user.id, original.id, 2, {}, true).event;
  assert.equal(deleted.version, 3);
  assert.ok(deleted.deleted_at);
  assert.equal(store.list(user.id, from, to).length, 0);
  assert.deepEqual(store.mutate(user.id, original.id, 2, {}, true).event, deleted);
  assert.throws(() => store.mutate(user.id, original.id, 3, { title: "复活" }), expectCode("NOT_FOUND"));
}));

test("completion releases busy time and reopening must recheck conflicts", () => withStore((store) => {
  const user = store.register("complete@example.com", "sufficient-password");
  const event = store.commit(user.id, randomUUID(), [draft()]).events[0];
  store.mutate(user.id, event.id, 1, { status: "completed" });
  store.commit(user.id, randomUUID(), [draft("替代日程")]);
  assert.throws(() => store.mutate(user.id, event.id, 2, { status: "planned" }), expectCode("TIME_CONFLICT"));
  assert.equal(store.list(user.id, from, to).find((e) => e.id === event.id)?.status, "completed");
}));

test("merged partial time edits are validated and cannot create an invalid interval", () => withStore((store) => {
  const user = store.register("times@example.com", "sufficient-password");
  const event = store.commit(user.id, randomUUID(), [draft()]).events[0];
  assert.throws(() => store.mutate(user.id, event.id, 1, { start_local: "2026-10-28T17:00:00" }), expectCode("VALIDATION_ERROR"));
  assert.throws(() => store.mutate(user.id, event.id, 1, { end_local: "2026-10-30T17:00:00" }), expectCode("VALIDATION_ERROR"));
  assert.equal(store.list(user.id, from, to)[0].version, 1);
}));

test("parse daily quotas are isolated per account and persist with the database", () => {
  const directory = mkdtempSync(join(tmpdir(), "shunshoupai-store-test-"));
  const filename = join(directory, "app.sqlite");
  let store: LocalStore | undefined = new LocalStore(filename);
  try {
    const user = store.register("quota@example.com", "sufficient-password");
    const second = store.register("quota2@example.com", "sufficient-password");
    store.commit(user.id, randomUUID(), [draft()]);
    assert.equal(store.consumeAiQuota(user.id, 1), true);
    assert.equal(store.consumeAiQuota(user.id, 1), false);
    assert.equal(store.consumeAiQuota(second.id, 1), true);
    store.close();
    store = new LocalStore(filename);
    assert.equal(store.list(user.id, from, to).length, 1);
    assert.equal(store.consumeAiQuota(user.id, 1), false);
  } finally {
    store?.close();
    // Delete only files in this test's fresh, explicit temp directory, without recursion.
    for (const file of readdirSync(directory)) rmSync(join(directory, file));
    rmdirSync(directory);
  }
});

test("API guards enforce origins, body bounds, query limits and throttles", async () => {
  const allowed = new Request("http://localhost:3000/api/events", { headers: { origin: "http://localhost:3000", host: "localhost:3000" } });
  assert.doesNotThrow(() => sameOrigin(allowed));
  assert.throws(() => sameOrigin(new Request("http://localhost:3000/api/events", { headers: { origin: "https://evil.example", host: "localhost:3000" } })), expectCode("INVALID_ORIGIN"));
  assert.doesNotThrow(() => sameOrigin(new Request("http://localhost:3000/api/events")));
  assert.throws(() => validateWindow(new Request("http://localhost/api/events?from=2026-01-01&to=2027-01-01")), expectCode("INVALID_WINDOW"));
  assert.throws(() => validateWindow(new Request("http://localhost/api/events?from=oops&to=2027-01-01")), expectCode("INVALID_WINDOW"));
  assert.equal(validateWindow(new Request("http://localhost/api/events?from=2026-10-01&to=2026-11-01")).from, "2026-10-01T00:00:00.000Z");
  await assert.rejects(() => readJson(new Request("http://localhost/api", { method: "POST", body: "{}" })), expectCode("INVALID_CONTENT_TYPE"));
  await assert.rejects(() => readJson(new Request("http://localhost/api", { method: "POST", headers: { "content-type": "application/json" }, body: "{" })), expectCode("INVALID_JSON"));
  await assert.rejects(() => readJson(new Request("http://localhost/api", { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ x: "x".repeat(40_000) }) })), expectCode("BODY_TOO_LARGE"));
  const key = `test:${randomUUID()}`;
  rateLimit(key, 1, 60_000);
  assert.throws(() => rateLimit(key, 1, 60_000), expectCode("RATE_LIMITED"));
});
