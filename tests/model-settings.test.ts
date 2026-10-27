import assert from "node:assert/strict";
import { randomBytes } from "node:crypto";
import { mkdtempSync, readFileSync, readdirSync, rmSync, rmdirSync, statSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";
import type { AuthContext } from "../src/lib/server/auth";
import { LocalStore } from "../src/lib/server/local-store";
import { AppError } from "../src/lib/server/errors";
import { decryptModelKey, encryptModelKey, localModelKeyPath, maskModelKey, modelEncryptionAvailable, modelEncryptionKey } from "../src/lib/server/model-crypto";
import { deleteModelSettings, getModelSettingsInfo, modelConfigForTest, resolveModelConfig, saveModelSettings, saveModelSettingsSchema, testModelSettingsSchema } from "../src/lib/server/model-settings";

const normalize = async (value: string) => value.replace(/\/+$/, "");
const input = { base_version: 0, base_url: "https://models.example/v1", model: "compatible-model", api_key: "test-secret-key-a1234" };
const isCode = (code: string) => (error: unknown) => error instanceof AppError && error.code === code;
function auth(store: LocalStore, email: string): AuthContext {
  return { user: store.register(email, "a-test-only-password"), mode: "local" };
}
async function fixture(run: (store: LocalStore, a: AuthContext, b: AuthContext, key: Buffer) => Promise<void>) {
  const store = new LocalStore(":memory:");
  try { await run(store, auth(store, "a@example.com"), auth(store, "b@example.com"), randomBytes(32)); }
  finally { store.close(); }
}
function temporaryDirectory() { return mkdtempSync(join(tmpdir(), "shunshoupai-model-settings-")); }
function removeOwnDirectory(directory: string) {
  for (const file of readdirSync(directory)) rmSync(join(directory, file));
  rmdirSync(directory);
}
function preserveEnv(names: string[]) {
  const previous = names.map(name => [name, process.env[name]] as const);
  return () => { for (const [name, value] of previous) { if (value === undefined) delete process.env[name]; else process.env[name] = value; } };
}

test("AES-GCM envelopes bind secrets to the owning account and detect tampering", () => {
  const key = randomBytes(32);
  const plain = "a-secret-model-key-1234";
  const first = encryptModelKey(plain, "owner-a", key);
  const second = encryptModelKey(plain, "owner-a", key);
  assert.notEqual(first, second, "fresh nonces protect repeated plaintexts");
  assert.ok(!first.includes(plain));
  assert.equal(decryptModelKey(first, "owner-a", key), plain);
  assert.throws(() => decryptModelKey(first, "owner-b", key), isCode("MODEL_ENCRYPTION_UNAVAILABLE"));
  assert.throws(() => decryptModelKey(first, "owner-a", randomBytes(32)), isCode("MODEL_ENCRYPTION_UNAVAILABLE"));
  const parts = first.split(".");
  parts[3] = (parts[3][0] === "A" ? "B" : "A") + parts[3].slice(1);
  assert.throws(() => decryptModelKey(parts.join("."), "owner-a", key), isCode("MODEL_ENCRYPTION_UNAVAILABLE"));
  assert.throws(() => decryptModelKey("v1.invalid.tag.ciphertext", "owner-a", key), isCode("MODEL_ENCRYPTION_UNAVAILABLE"));
});

test("masking never returns an entire short API key", () => {
  assert.equal(maskModelKey("1234"), "已保存");
  assert.equal(maskModelKey("secret-key-1234"), "••••1234");
});

test("personal settings persist encrypted and never expose the key or ciphertext in metadata", async () => fixture(async (store, a, b, key) => {
  const runtime = { store, normalize, encryptionKey: key };
  const result = await saveModelSettings(a, input, runtime);
  assert.equal(result.configured, true);
  assert.equal(result.effective_source, "personal");
  assert.equal(result.version, 1);
  assert.equal(result.key_hint, "••••1234");
  assert.equal(JSON.stringify(result).includes(input.api_key), false);
  assert.equal("api_key_ciphertext" in result, false);
  const record = store.modelSetting(a.user.id)!;
  assert.ok(record.api_key_ciphertext);
  assert.equal(record.api_key_ciphertext.includes(input.api_key), false);
  assert.deepEqual(await getModelSettingsInfo(a, runtime), result);
  assert.equal((await getModelSettingsInfo(b, runtime)).configured, false);
  assert.equal(store.modelSetting(b.user.id), null);
  assert.equal((await resolveModelConfig(a, runtime))?.apiKey, input.api_key);
}));

test("empty keys reuse the current account secret only on the same provider origin", async () => fixture(async (store, a, b, key) => {
  const runtime = { store, normalize, encryptionKey: key };
  await saveModelSettings(a, input, runtime);
  const updated = await saveModelSettings(a, { ...input, base_version: 1, model: "another-model", api_key: "" }, runtime);
  assert.equal(updated.version, 2);
  assert.equal((await resolveModelConfig(a, runtime))?.apiKey, input.api_key);
  const testConfig = await modelConfigForTest(a, { base_url: input.base_url, model: "probe-model", api_key: "" }, runtime);
  assert.equal(testConfig.apiKey, input.api_key);
  await assert.rejects(() => modelConfigForTest(b, { base_url: input.base_url, model: "probe-model" }, runtime), isCode("MODEL_KEY_REQUIRED"));
  await assert.rejects(() => modelConfigForTest(a, { base_url: "https://other.example/v1", model: "probe-model" }, runtime), isCode("MODEL_KEY_REQUIRED"));
  await assert.rejects(() => saveModelSettings(a, { ...input, base_version: 2, base_url: "https://other.example/v1", api_key: "" }, runtime), isCode("MODEL_KEY_REQUIRED"));
  assert.equal(store.modelSetting(a.user.id)?.version, 2);
}));

test("optimistic versions survive deletion and prevent stale recreate across devices", async () => fixture(async (store, a, _b, key) => {
  const runtime = { store, normalize, encryptionKey: key };
  await saveModelSettings(a, input, runtime);
  await assert.rejects(() => saveModelSettings(a, input, runtime), isCode("VERSION_CONFLICT"));
  const removed = await deleteModelSettings(a, 1, runtime);
  assert.equal(removed.configured, false);
  assert.equal(removed.version, 2);
  assert.equal(store.modelSetting(a.user.id)?.api_key_ciphertext, null);
  assert.deepEqual(await deleteModelSettings(a, 1, runtime), removed, "delete response loss can be retried");
  await assert.rejects(() => saveModelSettings(a, input, runtime), isCode("VERSION_CONFLICT"));
  const recreated = await saveModelSettings(a, { ...input, base_version: 2, api_key: "replacement-user-key-5678" }, runtime);
  assert.equal(recreated.version, 3);
  await assert.rejects(() => deleteModelSettings(a, 1, runtime), isCode("VERSION_CONFLICT"));
  assert.equal((await resolveModelConfig(a, runtime))?.apiKey, "replacement-user-key-5678");
}));

test("connection tests do not persist their draft or borrow the server/default account key", async () => fixture(async (store, a, _b, key) => {
  const restore = preserveEnv(["AI_API_KEY"]);
  process.env.AI_API_KEY = "server-only-secret-key";
  try {
    const runtime = { store, normalize, encryptionKey: key };
    await assert.rejects(() => modelConfigForTest(a, { base_url: input.base_url, model: input.model }, runtime), isCode("MODEL_KEY_REQUIRED"));
    const config = await modelConfigForTest(a, { base_url: input.base_url, model: input.model, api_key: input.api_key }, runtime);
    assert.equal(config.apiKey, input.api_key);
    assert.equal(store.modelSetting(a.user.id), null);
    assert.equal((await getModelSettingsInfo(a, runtime)).effective_source, "server");
    assert.equal((await resolveModelConfig(a, runtime))?.apiKey, "server-only-secret-key");
  } finally { restore(); }
}));

test("plaintext personal secrets do not appear in the SQLite file and survive service restart", async () => {
  const directory = temporaryDirectory();
  const database = join(directory, "app.sqlite");
  const key = randomBytes(32);
  let store: LocalStore | undefined = new LocalStore(database);
  try {
    const account = auth(store, "restart@example.com");
    await saveModelSettings(account, input, { store, encryptionKey: key, normalize });
    store.close();
    store = undefined;
    assert.equal(readFileSync(database).includes(Buffer.from(input.api_key)), false);
    store = new LocalStore(database);
    const resolved = await resolveModelConfig(account, { store, encryptionKey: key, normalize });
    assert.equal(resolved?.apiKey, input.api_key);
    assert.equal((await getModelSettingsInfo(account, { store, encryptionKey: key, normalize })).version, 1);
  } finally { store?.close(); removeOwnDirectory(directory); }
});

test("an unreadable master key fails closed rather than falling back to a different model", async () => fixture(async (store, a, _b, key) => {
  const runtime = { store, normalize, encryptionKey: key };
  await saveModelSettings(a, input, runtime);
  await assert.rejects(() => resolveModelConfig(a, { ...runtime, encryptionKey: randomBytes(32) }), isCode("MODEL_ENCRYPTION_UNAVAILABLE"));
  const repaired = await saveModelSettings(a, { ...input, base_version: 1, api_key: "reentered-model-key" }, { ...runtime, encryptionKey: randomBytes(32) });
  assert.equal(repaired.version, 2);
}));

test("personal model schemas reject prompt/owner injection and header-control key characters", () => {
  assert.equal(saveModelSettingsSchema.safeParse({ ...input, user_id: "another-owner" }).success, false);
  assert.equal(saveModelSettingsSchema.safeParse({ ...input, system_prompt: "replace the fixed prompt" }).success, false);
  assert.equal(testModelSettingsSchema.safeParse({ base_url: input.base_url, model: input.model, api_key: "secret\r\nX-Header: injected" }).success, false);
  assert.equal(saveModelSettingsSchema.safeParse({ ...input, base_version: -1 }).success, false);
  assert.equal(testModelSettingsSchema.safeParse({ base_url: input.base_url, model: input.model, messages: [] }).success, false);
});

test("local encryption keys are stable, while cloud mode requires an explicit master key", () => {
  const directory = temporaryDirectory();
  const restore = preserveEnv(["APP_DB_PATH", "MODEL_SETTINGS_ENCRYPTION_KEY"]);
  process.env.APP_DB_PATH = join(directory, "app.sqlite");
  delete process.env.MODEL_SETTINGS_ENCRYPTION_KEY;
  try {
    assert.equal(modelEncryptionAvailable("local"), true);
    assert.equal(modelEncryptionAvailable("supabase"), false);
    assert.throws(() => modelEncryptionKey("supabase", true), isCode("MODEL_ENCRYPTION_UNAVAILABLE"));
    assert.throws(() => modelEncryptionKey("local"), isCode("MODEL_ENCRYPTION_UNAVAILABLE"));
    const generated = modelEncryptionKey("local", true);
    assert.equal(generated.length, 32);
    assert.deepEqual(modelEncryptionKey("local", true), generated);
    assert.deepEqual(readFileSync(localModelKeyPath()), generated);
    if (process.platform !== "win32") assert.equal(statSync(localModelKeyPath()).mode & 0o077, 0, "the generated encryption key must not be readable by other users");
    assert.equal(readdirSync(directory).filter(file => file.endsWith(".tmp")).length, 0);
    const supplied = randomBytes(32);
    process.env.MODEL_SETTINGS_ENCRYPTION_KEY = supplied.toString("base64");
    assert.deepEqual(modelEncryptionKey("supabase"), supplied);
    process.env.MODEL_SETTINGS_ENCRYPTION_KEY = "not-a-valid-32-byte-key";
    assert.equal(modelEncryptionAvailable("local"), false);
    assert.throws(() => modelEncryptionKey("local", true), isCode("MODEL_ENCRYPTION_UNAVAILABLE"));
  } finally { restore(); removeOwnDirectory(directory); }
});
