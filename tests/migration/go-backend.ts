import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { randomBytes, randomUUID, verify, createPublicKey } from "node:crypto";
import { mkdtempSync, writeFileSync } from "node:fs";
import { createServer } from "node:net";
import { resolve, join } from "node:path";
import { tmpdir } from "node:os";
import { setTimeout as delay } from "node:timers/promises";
import { LocalStore } from "../../src/lib/server/local-store";
import { encryptModelKey, decryptModelKey } from "../../src/lib/server/model-crypto";
import type { EventDraft } from "../../src/lib/types";

// Exercise an actual v2 Node database with the Go executable. No production
// data is opened; the fixture deliberately retains pending request receipts.
async function main() {
const directory = mkdtempSync(join(tmpdir(), "shunshoupai-go-migration-"));
const database = join(directory, "migration.sqlite");
const key = randomBytes(32);
writeFileSync(join(directory, "model-encryption.key"), key, { mode: 0o600 });
const legacy = new LocalStore(database);
const owner = legacy.devices.bootstrap("迁移测试", "test");
const identity = legacy.devices.context();
const publicKey = legacy.devices.publicKey();
const space = owner.grant.space_id;
const requestID = randomUUID();
const drafts: EventDraft[] = [{ title: "保留 <编号> & 版本", start_local: "2030-06-01T10:00:00", end_local: "2030-06-01T11:00:00", timezone: "Asia/Shanghai", time_source: "explicit", assumptions: [] }];
const created = legacy.commit(space, requestID, drafts).events[0];
const changed = legacy.mutate(space, created.id, created.version, { title: "旧端已修改" }).event;
const oldRecords = legacy.replicaPage(space);
const syncID = randomUUID();
const syncMutation = { mutation_id: syncID, id: randomUUID(), base_version: 0, deleted: false, event: { title: "旧离线日程", start_local: "2030-06-02T10:00:00", end_local: "2030-06-02T11:00:00", timezone: "Asia/Shanghai" as const, status: "planned" as const } };
const oldSync = legacy.sync(space, { account_id: space, mutations: [syncMutation] });
legacy.saveModelSetting(space, 0, { base_url: "http://127.0.0.1:9876", model: "fixture", api_key_ciphertext: encryptModelKey("fixture-secret-old", space, key), key_hint: "••••-old" });
legacy.close();

const port = await new Promise<number>((resolvePort, reject) => { const listener = createServer(); listener.on("error", reject); listener.listen(0, "127.0.0.1", () => { const address = listener.address(); if (!address || typeof address === "string") throw new Error("port"); listener.close(() => resolvePort(address.port)); }); });
const bootstrapKey = randomBytes(32).toString("base64url");
const child = spawn(resolve(`backend/bin/shunshoupai-server${process.platform === "win32" ? ".exe" : ""}`), ["--host", "127.0.0.1", "--port", String(port), "--db", database, "--web", resolve("out")], {
  windowsHide: true, stdio: ["ignore", "ignore", "inherit"], env: { ...process.env, APP_STORAGE: "local", APP_DEVICE_BOOTSTRAP_KEY: bootstrapKey, AI_API_KEY: "", MODEL_SETTINGS_ENCRYPTION_KEY: "", AI_ALLOWED_PRIVATE_HOSTS: "127.0.0.1", APP_ORIGIN: "", COOKIE_SECURE: "false" },
});
let spawnError: Error | undefined;
child.on("error", error => { spawnError = error; });
const origin = `http://127.0.0.1:${port}`;
async function api(path: string, method = "GET", body?: unknown, authorized = true) {
  const result = await fetch(origin + path, { method, headers: { "Content-Type": "application/json", ...(authorized ? { Authorization: `Bearer ${owner.token}` } : {}), "X-Device-Bootstrap": bootstrapKey }, ...(body !== undefined ? { body: JSON.stringify(body) } : {}), signal: AbortSignal.timeout(10_000) });
  return { status: result.status, data: await result.json() };
}
try {
  let ready = false;
  for (let i = 0; i < 100; i++) {
    if (spawnError) throw spawnError;
    if (child.exitCode !== null) throw new Error(`Go exited ${child.exitCode}`);
    try { if ((await fetch(origin + "/healthz")).ok) { ready = true; break; } } catch { /* startup */ }
    await delay(100);
  }
  assert.ok(ready, "Go service becomes ready");
  assert.equal((await api("/api/events?from=2030-06-01T00:00:00Z&to=2030-06-03T00:00:00Z", "GET", undefined, false)).status, 401);
  const session = await api("/api/devices/bootstrap", "POST", { name: "迁移测试", platform: "test" });
  assert.equal(session.status, 200); assert.equal(session.data.space_id, space); assert.equal(session.data.peer_id, identity.peer_id);
  const replay = await api("/api/events/commit", "POST", { request_id: requestID, events: drafts });
  assert.equal(replay.status, 200); assert.equal(replay.data.replayed, true); assert.equal(replay.data.events[0].id, created.id);
  const read = await api(`/api/events/${created.id}`);
  assert.equal(read.data.event.version, changed.version); assert.equal(read.data.event.title, changed.title);
  const replaySync = await api("/api/events/sync", "POST", { space_id: space, mutations: [syncMutation] });
  assert.equal(replaySync.status, 200); assert.deepEqual(replaySync.data.results, oldSync.results);
  const merge = await api("/api/replica/exchange", "POST", { device_id: identity.peer_id, records: oldRecords.records });
  assert.equal(merge.status, 200, JSON.stringify(merge.data));
  const challenge = randomBytes(32).toString("base64url"), client = randomUUID();
  const proof = await api(`/api/devices/identity?challenge=${challenge}&device_id=${client}`);
  assert.equal(proof.data.identity.public_key, publicKey);
  assert.ok(verify("sha256", Buffer.from(`${challenge}\n${client}\n${identity.peer_id}\n${space}`), createPublicKey({ key: Buffer.from(publicKey, "base64"), format: "der", type: "spki" }), Buffer.from(proof.data.identity.signature, "base64")));
  const settings = await api("/api/model-settings"); assert.equal(settings.data.settings.version, 1); assert.equal(settings.data.settings.configured, true);
  assert.ok(!JSON.stringify(settings.data).includes("fixture-secret-old"));
  const update = await api("/api/model-settings", "PUT", { base_version: 1, base_url: "http://127.0.0.1:9876", model: "go-updated", api_key: "" });
  assert.equal(update.status, 200, JSON.stringify(update.data));
  const change = await api(`/api/events/${created.id}`, "PATCH", { base_version: changed.version, title: "Go 保存" });
  assert.equal(change.status, 200, JSON.stringify(change.data)); assert.equal(change.data.event.version, changed.version + 1);
  assert.equal((await api(`/api/events/${created.id}`, "PATCH", { base_version: changed.version, title: "过期编辑" })).status, 409);
} finally {
  child.kill();
  if (child.exitCode === null) await new Promise<void>(resolveExit => child.once("exit", () => resolveExit()));
}
const reopened = new LocalStore(database);
try {
  assert.equal(reopened.event(space, created.id).title, "Go 保存");
  assert.equal(decryptModelKey(reopened.modelSetting(space)!.api_key_ciphertext!, space, key), "fixture-secret-old");
  assert.equal(reopened.devices.context().peer_id, identity.peer_id);
  assert.equal(reopened.commit(space, requestID, drafts).replayed, true);
  reopened.replicaPage(space);
} finally { reopened.close(); }
console.log("PASS: Node → Go → Node SQLite, receipts, P-256 identity, encrypted model key and revisions");

}
main().catch(error => { console.error(error); process.exitCode = 1; });

