import assert from "node:assert/strict";
import { randomBytes, randomUUID } from "node:crypto";
import { mkdtempSync, readFileSync, readdirSync, rmSync, rmdirSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";
import { LocalStore } from "../src/lib/server/local-store";
import { AppError } from "../src/lib/server/errors";
import { encryptModelKey } from "../src/lib/server/model-crypto";
import { decodePairedSupabaseCookie, decryptPairingAccess, encodePairedSupabaseCookie, encryptPairingAccess, supabaseTokenDetails } from "../src/lib/server/paired-session";
import { pairingIssueSchema, pairingRedeemSchema, pairingTokenHash } from "../src/lib/server/pairing";
import { supabaseTokenClient } from "../src/lib/server/auth";

const isCode = (code: string) => (error: unknown) => error instanceof AppError && error.code === code;
function withStore(run: (store: LocalStore) => void) {
  const store = new LocalStore(":memory:");
  try { run(store); } finally { store.close(); }
}
function setup(store: LocalStore) {
  const user = store.register("pairing@example.com", "test-only-password");
  return { user, source: store.createSession(user.id) };
}
function jwt(userId: string, expiresIn = 3600) {
  return [
    Buffer.from(JSON.stringify({ alg: "HS256", typ: "JWT" })).toString("base64url"),
    Buffer.from(JSON.stringify({ sub: userId, exp: Math.floor(Date.now() / 1000) + expiresIn, session_id: randomUUID() })).toString("base64url"),
    "unverified-test-signature",
  ].join(".");
}

test("pairing tickets are high entropy, last at most 90 seconds, and require a valid source session", () => withStore(store => {
  const { user, source } = setup(store);
  const start = Date.now();
  const ticket = store.issuePairing(user.id, source, start);
  assert.match(ticket.token, /^[A-Za-z0-9_-]{43}$/);
  assert.equal(Date.parse(ticket.expires_at), start + 90_000);
  assert.equal(pairingTokenHash(ticket.token).length, 64);
  assert.throws(() => store.issuePairing(user.id, "not-a-real-session"), isCode("UNAUTHORIZED"));
  const other = store.register("other-pairing@example.com", "test-only-password");
  assert.throws(() => store.issuePairing(other.id, source), isCode("UNAUTHORIZED"));
}));

test("redeeming a ticket creates an independent session for exactly the source account and is single-use", () => withStore(store => {
  const { user, source } = setup(store);
  const ticket = store.issuePairing(user.id, source);
  const result = store.redeemPairing(ticket.token);
  assert.deepEqual(result.user, user);
  assert.notEqual(result.session_token, source);
  assert.deepEqual(store.sessionUser(result.session_token), user);
  assert.ok(Date.parse(result.session_expires_at) > Date.now() + 6 * 24 * 60 * 60 * 1000);
  assert.throws(() => store.redeemPairing(ticket.token), isCode("PAIRING_INVALID"));
  store.deleteSession(source);
  assert.deepEqual(store.sessionUser(result.session_token), user, "a redeemed local-device login is independent from the issuing device");
}));

test("source logout, expiry, malformed tokens and expired tickets fail without creating a session", () => withStore(store => {
  const { user, source } = setup(store);
  const now = Date.now();
  const ticket = store.issuePairing(user.id, source, now);
  assert.throws(() => store.redeemPairing(ticket.token, now + 90_000), isCode("PAIRING_INVALID"));
  assert.throws(() => store.redeemPairing(randomBytes(32).toString("base64url")), isCode("PAIRING_INVALID"));
  assert.throws(() => store.redeemPairing("invalid"), isCode("PAIRING_INVALID"));
  store.deleteSession(source);
  assert.throws(() => store.redeemPairing(ticket.token, now + 1000), isCode("PAIRING_INVALID"));
  const replacement = store.createSession(user.id);
  assert.throws(() => store.issuePairing(user.id, replacement, now + 31 * 24 * 60 * 60 * 1000), isCode("UNAUTHORIZED"));
}));

test("regenerating invalidates only unused tickets from that source browser", () => withStore(store => {
  const { user, source } = setup(store);
  const otherSource = store.createSession(user.id);
  const first = store.issuePairing(user.id, source);
  const fromOtherBrowser = store.issuePairing(user.id, otherSource);
  const replacement = store.issuePairing(user.id, source);
  assert.notEqual(first.token, replacement.token);
  assert.throws(() => store.redeemPairing(first.token), isCode("PAIRING_INVALID"));
  assert.deepEqual(store.redeemPairing(fromOtherBrowser.token).user, user);
  assert.deepEqual(store.redeemPairing(replacement.token).user, user);
}));

test("ticket and source-session plaintext never enter SQLite and redemption survives restart", () => {
  const directory = mkdtempSync(join(tmpdir(), "shunshoupai-pairing-"));
  const filename = join(directory, "test.sqlite");
  let store: LocalStore | undefined = new LocalStore(filename);
  try {
    const { user, source } = setup(store);
    const ticket = store.issuePairing(user.id, source);
    store.close();
    store = undefined;
    const data = readFileSync(filename);
    assert.equal(data.includes(Buffer.from(ticket.token)), false);
    assert.equal(data.includes(Buffer.from(source)), false);
    assert.equal(data.includes(Buffer.from(pairingTokenHash(ticket.token))), true);
    store = new LocalStore(filename);
    assert.deepEqual(store.redeemPairing(ticket.token).user, user);
    const restarted = store;
    assert.throws(() => restarted.redeemPairing(ticket.token), isCode("PAIRING_INVALID"));
  } finally {
    store?.close();
    for (const file of readdirSync(directory)) rmSync(join(directory, file));
    rmdirSync(directory);
  }
});

test("pairing API schemas never accept caller-specified owners, sessions or token lifetimes", () => {
  assert.equal(pairingIssueSchema.safeParse({}).success, true);
  assert.equal(pairingIssueSchema.safeParse({ user_id: randomUUID() }).success, false);
  assert.equal(pairingIssueSchema.safeParse({ expires_at: "2099-01-01" }).success, false);
  assert.equal(pairingRedeemSchema.safeParse({ token: randomBytes(32).toString("base64url"), device_id: randomUUID(), device_name: "测试手机", platform: "test" }).success, true);
  assert.equal(pairingRedeemSchema.safeParse({ token: "short" }).success, false);
  assert.equal(pairingRedeemSchema.safeParse({ token: randomBytes(32).toString("base64url"), user_id: randomUUID() }).success, false);
});

test("Supabase grants contain only an expiring access token and cannot reuse model-key ciphertext", () => {
  const owner = randomUUID();
  const token = jwt(owner);
  const key = randomBytes(32);
  const encrypted = encryptPairingAccess(token, owner, key);
  assert.equal(encrypted.includes(token), false);
  assert.equal(decryptPairingAccess(encrypted, owner, key), token);
  assert.throws(() => decryptPairingAccess(encrypted, randomUUID(), key));
  assert.throws(() => decryptPairingAccess(encryptModelKey(token, owner, key), owner, key), isCode("MODEL_ENCRYPTION_UNAVAILABLE"));
  assert.throws(() => supabaseTokenDetails(jwt(owner, -1), owner), isCode("PAIRING_INVALID"));
  assert.throws(() => supabaseTokenDetails(token, randomUUID()), isCode("PAIRING_INVALID"));
});

test("sealed temporary cookies authenticate the owner and the narrower source-session expiration", () => {
  const owner = randomUUID();
  const token = jwt(owner);
  const key = randomBytes(32);
  const shorterExpiry = new Date(Date.now() + 60_000).toISOString();
  const sealed = encodePairedSupabaseCookie(owner, token, shorterExpiry, key);
  assert.equal(sealed.includes(token), false);
  assert.deepEqual(decodePairedSupabaseCookie(sealed, key), { userId: owner, accessToken: token, expiresAt: shorterExpiry });
  const pieces = sealed.split("~");
  pieces[1] = String(Number(pieces[1]) + 60_000);
  assert.throws(() => decodePairedSupabaseCookie(pieces.join("~"), key), isCode("MODEL_ENCRYPTION_UNAVAILABLE"));
  const wrongOwner = sealed.replace(owner, randomUUID());
  assert.throws(() => decodePairedSupabaseCookie(wrongOwner, key), isCode("MODEL_ENCRYPTION_UNAVAILABLE"));
  assert.throws(() => encodePairedSupabaseCookie(owner, token, new Date(Date.now() - 1000).toISOString(), key), isCode("PAIRING_INVALID"));
  assert.throws(() => encodePairedSupabaseCookie(owner, token, new Date(Date.now() + 7200_000).toISOString(), key), isCode("PAIRING_INVALID"));
});

test("paired Supabase data clients use the validated access JWT without storing or rotating a refresh token", async () => {
  const previousUrl = process.env.NEXT_PUBLIC_SUPABASE_URL;
  const previousKey = process.env.NEXT_PUBLIC_SUPABASE_ANON_KEY;
  const originalFetch = globalThis.fetch;
  const token = jwt(randomUUID());
  const requests: Headers[] = [];
  process.env.NEXT_PUBLIC_SUPABASE_URL = "https://fixture.supabase.co";
  process.env.NEXT_PUBLIC_SUPABASE_ANON_KEY = "public-test-key";
  globalThis.fetch = async (_input, init) => {
    requests.push(new Headers(init?.headers));
    return new Response("{}", { status: 200, headers: { "content-type": "application/json" } });
  };
  try {
    const client = supabaseTokenClient(token);
    const result = await client.rpc("test_only_rpc");
    assert.equal(result.error, null);
    assert.equal(requests.length, 1);
    assert.equal(requests[0].get("authorization"), `Bearer ${token}`);
    assert.equal((await client.auth.getSession()).data.session, null);
  } finally {
    globalThis.fetch = originalFetch;
    if (previousUrl === undefined) delete process.env.NEXT_PUBLIC_SUPABASE_URL; else process.env.NEXT_PUBLIC_SUPABASE_URL = previousUrl;
    if (previousKey === undefined) delete process.env.NEXT_PUBLIC_SUPABASE_ANON_KEY; else process.env.NEXT_PUBLIC_SUPABASE_ANON_KEY = previousKey;
  }
});
