import { expect, test } from "@playwright/test";
import { randomBytes, randomUUID, verify } from "node:crypto";
import { bootstrapDevice, pairDevice, resetTestDevice } from "./device-fixtures";

test.beforeEach(async ({ request }) => { await resetTestDevice(request); });

test("account-free paired devices merge offline edits, retries and tombstones until explicitly unpaired", async ({ playwright, baseURL }) => {
  const desktop = await playwright.request.newContext({ baseURL });
  const phone = await playwright.request.newContext({ baseURL });
  try {
    const session = await bootstrapDevice(desktop);
    expect(session.role).toBe("owner");
    const paired = await pairDevice(desktop, phone);
    expect(paired.role).toBe("peer");
    expect(paired.space_id).toBe(session.space_id);
    const event = { title: "电脑会议", start_local: "2030-10-28T09:00:00", end_local: "2030-10-28T10:00:00", timezone: "Asia/Shanghai", status: "planned" };
    const commit = await desktop.post("/api/events/commit", { data: { request_id: randomUUID(), events: [{ title: event.title, start_local: event.start_local, end_local: event.end_local, timezone: event.timezone, time_source: "explicit", assumptions: [] }] } });
    expect(commit.status()).toBe(200);
    const mutation = { mutation_id: randomUUID(), id: randomUUID(), base_version: 0, deleted: false, event: { ...event, title: "手机离线安排" } };
    const payload = { space_id: session.space_id, mutations: [mutation] };
    const merged = await phone.post("/api/events/sync", { data: payload });
    expect(merged.status()).toBe(200);
    const result = await merged.json();
    expect(result.peer_id).toBe(session.device!.id);
    expect(result.space_id).toBe(session.space_id);
    expect(result.events.filter((item: { deleted_at: string | null }) => !item.deleted_at)).toHaveLength(2);
    expect(result.results[0].status).toBe("applied");
    expect(await (await phone.post("/api/events/sync", { data: payload })).json()).toEqual(result);
    expect((await phone.post("/api/pairing/issue", { data: {} })).status()).toBe(403);
    expect((await desktop.patch(`/api/events/${mutation.id}`, { data: { base_version: 1, title: "电脑已改" } })).status()).toBe(200);
    const conflict = await (await phone.post("/api/events/sync", { data: { space_id: session.space_id, mutations: [{ ...mutation, mutation_id: randomUUID(), base_version: 1 }] } })).json();
    expect(conflict.results[0].code).toBe("VERSION_CONFLICT");
    expect(conflict.results[0].event.title).toBe("电脑已改");
    const deletion = await phone.post("/api/events/sync", { data: { space_id: session.space_id, mutations: [{ id: mutation.id, mutation_id: randomUUID(), base_version: 2, deleted: true }] } });
    expect(deletion.status()).toBe(200);
    expect((await (await desktop.get(`/api/events/${mutation.id}`)).json()).event.deleted_at).toBeTruthy();
    const resumed = await playwright.request.newContext({ baseURL, storageState: await phone.storageState() });
    try { expect((await (await resumed.get("/api/devices/info")).json()).space_id).toBe(session.space_id); }
    finally { await resumed.dispose(); }
    expect((await desktop.delete(`/api/devices/${paired.peer!.id}`)).status()).toBe(200);
    expect((await phone.post("/api/events/sync", { data: payload })).status()).toBe(401);
    expect((await desktop.get(`/api/events/${mutation.id}`)).status()).toBe(200);
  } finally { await desktop.dispose(); await phone.dispose(); }
});

test("unpaired LAN requests cannot bootstrap, register, access schedules, or impersonate local ownership", async ({ playwright, baseURL, request }) => {
  const owner = await bootstrapDevice(request);
  const outsider = await playwright.request.newContext({ baseURL });
  try {
    for (const action of ["login", "register", "logout"]) expect((await outsider.post(`/api/auth/${action}`, { data: { email: "unused@example.com", password: "unused-password" } })).status()).toBe(410);
    const denied = await outsider.post("/api/devices/bootstrap", {
      headers: { "X-Device-Bootstrap": "wrong", "X-Forwarded-For": "127.0.0.1", "User-Agent": "ShunshoupaiQt/1.5.0" }, data: { name: "伪装本机", platform: "windows" },
    });
    expect(denied.status()).toBe(403);
    expect((await outsider.post("/api/events/sync", { data: { space_id: owner.space_id, mutations: [] } })).status()).toBe(401);
    expect((await outsider.get("/api/devices")).status()).toBe(401);
    expect((await (await outsider.get("/api/auth/session")).json()).user).toBeNull();
    expect((await request.post("/api/events/sync", { data: { space_id: owner.space_id, mutations: [] }, headers: { Origin: "https://foreign.example" } })).status()).toBe(403);
    const wrongSpace = await request.post("/api/events/sync", { data: { space_id: randomUUID(), mutations: [] } });
    expect(wrongSpace.status()).toBe(409);
    expect((await wrongSpace.json()).error.code).toBe("SPACE_MISMATCH");
  } finally { await outsider.dispose(); }
});

test("native pairing receives a revocable bearer and a signed identity bound to the client challenge", async ({ playwright, baseURL, request }) => {
  const owner = await bootstrapDevice(request);
  const peer = await playwright.request.newContext({ baseURL });
  try {
    const ticket = await (await request.post("/api/pairing/issue", { data: {} })).json();
    const clientId = randomUUID();
    const challenge = randomBytes(32).toString("base64url");
    const redeemed = await peer.post("/api/pairing/redeem", { data: { token: ticket.token, device_id: clientId, device_name: "原生手机", platform: "android", challenge } });
    expect(redeemed.status()).toBe(200);
    expect(redeemed.headers()["set-cookie"]).toContain("HttpOnly");
    const data = await redeemed.json();
    expect(data.peer_id).toBe(owner.device!.id);
    const content = `${challenge}\n${clientId}\n${data.peer_id}\n${data.space_id}`;
    expect(verify("sha256", Buffer.from(content), { key: Buffer.from(data.identity.public_key, "base64"), format: "der", type: "spki" }, Buffer.from(data.identity.signature, "base64"))).toBe(true);
    const native = await playwright.request.newContext({ baseURL, extraHTTPHeaders: { Authorization: `Bearer ${data.device_token}` } });
    try {
      const denied = await native.post("/api/devices/web-session", { data: {} });
      expect(denied.status()).toBe(403);
      expect((await denied.json()).error.code).toBe("SYNC_ONLY_DEVICE");
      expect((await (await native.get("/api/devices/info")).json()).space_id).toBe(owner.space_id);
      expect((await native.delete(`/api/devices/${data.pairing_id}`)).status()).toBe(200);
      expect((await native.get("/api/devices/info")).status()).toBe(401);
    } finally { await native.dispose(); }
  } finally { await peer.dispose(); }
});
