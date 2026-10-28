import assert from "node:assert/strict";
import { randomBytes, randomUUID, verify } from "node:crypto";
import { mkdtempSync, readdirSync, rmSync, rmdirSync, readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";
import { LocalStore } from "../src/lib/server/local-store";
import { AppError } from "../src/lib/server/errors";

const draft = { title: "本机安排", start_local: "2031-01-01T09:00:00", end_local: "2031-01-01T10:00:00", timezone: "Asia/Shanghai" as const, time_source: "explicit" as const, assumptions: [] };
const code = (value: string) => (error: unknown) => error instanceof AppError && error.code === value;
function use(run: (store: LocalStore) => void) { const store = new LocalStore(":memory:"); try { run(store); } finally { store.close(); } }

test("first local bootstrap needs no account and retains its identity/calendar across reconnects", () => use((store) => {
  const first = store.devices.bootstrap("我的电脑", "windows");
  const context = store.devices.context();
  assert.equal(first.grant.role, "owner");
  assert.equal(first.grant.space_id, context.space_id);
  store.commit(context.space_id, randomUUID(), [draft]);
  assert.deepEqual(store.devices.bootstrap("我的电脑", "windows", first.token), first);
  const next = store.devices.bootstrap("我的电脑", "windows");
  assert.notEqual(next.token, first.token);
  assert.equal(store.devices.authenticate(first.token), null);
  assert.equal(next.grant.space_id, context.space_id);
  assert.deepEqual(store.devices.context(), context);
  assert.equal(store.event(context.space_id, store.sync(context.space_id, { space_id: context.space_id, mutations: [] }).events[0].id).title, draft.title);
}));

test("QR grants are one-use, expire, rotate per physical peer, and cannot grant further devices", () => use((store) => {
  const owner = store.devices.bootstrap("电脑", "windows");
  const client = randomUUID();
  const ticket = store.devices.issue(owner.grant);
  const phone = store.devices.redeem(ticket.token, client, "手机", "android");
  assert.equal(phone.grant.role, "peer");
  assert.throws(() => store.devices.redeem(ticket.token, randomUUID(), "重放", "android"), code("PAIRING_INVALID"));
  assert.throws(() => store.devices.issue(phone.grant), code("OWNER_REQUIRED"));
  const another = store.devices.redeem(store.devices.issue(owner.grant).token, randomUUID(), "平板", "android");
  const replacement = store.devices.redeem(store.devices.issue(owner.grant).token, client, "手机", "android");
  assert.equal(store.devices.authenticate(phone.token), null);
  assert.ok(store.devices.authenticate(replacement.token));
  assert.ok(store.devices.authenticate(another.token));
  const expired = store.devices.issue(owner.grant, 1000);
  assert.throws(() => store.devices.redeem(expired.token, randomUUID(), "过期", "android", 91_000), code("PAIRING_INVALID"));
}));

test("revoking a device removes only its authorization and preserves all calendar data", () => use((store) => {
  const owner = store.devices.bootstrap("电脑", "windows");
  const a = store.devices.redeem(store.devices.issue(owner.grant).token, randomUUID(), "A", "android");
  const b = store.devices.redeem(store.devices.issue(owner.grant).token, randomUUID(), "B", "android");
  const event = store.commit(owner.grant.space_id, randomUUID(), [draft]).events[0];
  assert.equal(store.devices.list(a.grant).devices.length, 1);
  assert.equal(store.devices.list(owner.grant).devices.length, 3);
  assert.throws(() => store.devices.revoke(a.grant, b.grant.id), code("NOT_FOUND"));
  assert.throws(() => store.devices.revoke(owner.grant, owner.grant.id), code("LOCAL_DEVICE"));
  store.devices.revoke(owner.grant, a.grant.id);
  assert.equal(store.devices.authenticate(a.token), null);
  assert.ok(store.devices.authenticate(b.token));
  assert.deepEqual(store.event(owner.grant.space_id, event.id), event);
}));

test("a grant snapshot cannot authorize a delayed mutation after revocation", () => use((store) => {
  const owner = store.devices.bootstrap("本机", "test");
  const peer = store.devices.redeem(store.devices.issue(owner.grant).token, randomUUID(), "手机", "android");
  store.devices.assertActive(peer.grant);
  store.devices.revoke(owner.grant, peer.grant.id);
  assert.throws(() => store.devices.assertActive(peer.grant), code("UNAUTHORIZED"));
}));

test("single legacy calendar migrates without changing event IDs, versions, or encrypted setting ownership", () => use((store) => {
  const old = store.register("legacy@example.com", "test-only-password");
  const event = store.commit(old.id, randomUUID(), [draft]).events[0];
  store.saveModelSetting(old.id, 0, { base_url: "https://model.example", model: "test", api_key_ciphertext: "legacy-ciphertext", key_hint: "••••test" });
  const owner = store.devices.bootstrap("电脑", "windows");
  assert.equal(owner.grant.space_id, old.id);
  assert.deepEqual(store.event(old.id, event.id), event);
  assert.equal(store.modelSetting(old.id)?.api_key_ciphertext, "legacy-ciphertext");
}));

test("multiple legacy calendars require local selection or a valid old session; no silent cross-person merge", () => use((store) => {
  const a = store.register("a@example.com", "test-only-password");
  const b = store.register("b@example.com", "test-only-password");
  const ea = store.commit(a.id, randomUUID(), [draft]).events[0];
  const eb = store.commit(b.id, randomUUID(), [draft]).events[0];
  assert.throws(() => store.devices.bootstrap("电脑", "windows"), code("MIGRATION_REQUIRED"));
  assert.throws(() => store.devices.bootstrap("电脑", "windows", undefined, undefined, randomUUID()), code("INVALID_SPACE"));
  const owner = store.devices.bootstrap("电脑", "windows", undefined, store.createSession(b.id));
  assert.equal(owner.grant.space_id, b.id);
  assert.throws(() => store.event(owner.grant.space_id, ea.id), code("NOT_FOUND"));
  assert.deepEqual(store.event(b.id, eb.id), eb);
}));

test("legacy profiles containing only client-side drafts retain their original space identity", () => use((store) => {
  const old = store.register("drafts-only@example.com", "test-only-password");
  assert.equal(store.devices.bootstrap("电脑", "windows").grant.space_id, old.id);
}));

test("stable signed identity pins the server key and binds every proof to a fresh client challenge", () => use((store) => {
  store.devices.bootstrap("电脑", "windows");
  const client = randomUUID();
  const challenge = randomBytes(32).toString("base64url");
  const proof = store.devices.proof(challenge, client);
  const message = `${challenge}\n${client}\n${proof.peer_id}\n${proof.space_id}`;
  const key = { key: Buffer.from(proof.identity.public_key, "base64"), format: "der" as const, type: "spki" as const };
  assert.ok(verify("sha256", Buffer.from(message), key, Buffer.from(proof.identity.signature, "base64")));
  assert.equal(verify("sha256", Buffer.from(message + "tampered"), key, Buffer.from(proof.identity.signature, "base64")), false);
  assert.equal(verify("sha256", Buffer.from(message.replace(client, randomUUID())), key, Buffer.from(proof.identity.signature, "base64")), false);
}));

test("trusted grants and the signed server identity survive restart; plaintext bearer tokens stay out of SQLite", () => {
  const directory = mkdtempSync(join(tmpdir(), "shunshoupai-devices-"));
  const file = join(directory, "local.sqlite");
  let store = new LocalStore(file);
  try {
    const owner = store.devices.bootstrap("电脑", "windows");
    const phone = store.devices.redeem(store.devices.issue(owner.grant).token, randomUUID(), "手机", "android");
    const proof = store.devices.proof(randomBytes(32).toString("base64url"), randomUUID());
    store.close(); store = new LocalStore(file);
    assert.ok(store.devices.authenticate(phone.token));
    assert.equal(store.devices.context().peer_id, proof.peer_id);
    assert.equal(store.devices.proof(randomBytes(32).toString("base64url"), randomUUID()).identity.public_key, proof.identity.public_key);
    assert.equal(readFileSync(file).includes(Buffer.from(phone.token)), false);
  } finally { store.close(); for (const entry of readdirSync(directory)) rmSync(join(directory, entry)); rmdirSync(directory); }
});
