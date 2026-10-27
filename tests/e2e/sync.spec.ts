import { expect, test } from "@playwright/test";
import { randomUUID } from "node:crypto";

test("paired devices persist login and merge offline changes, retries and deletions through the real API", async ({ playwright, baseURL }) => {
  const desktop = await playwright.request.newContext({ baseURL });
  const phone = await playwright.request.newContext({ baseURL });
  try {
    const registration = await desktop.post("/api/auth/register", { data: { email: `sync-${randomUUID()}@example.com`, password: "test-sync-password" } });
    expect(registration.status()).toBe(200);
    expect(registration.headers()["set-cookie"]).toContain("Max-Age=2592000");
    const { user } = await registration.json();
    const ticket = await (await desktop.post("/api/pairing/issue", { data: {} })).json();
    const redeemed = await phone.post("/api/pairing/redeem", { data: { token: ticket.token } });
    expect(redeemed.status()).toBe(200);
    expect((await redeemed.json()).user.id).toBe(user.id);
    const session = await (await phone.get("/api/auth/session")).json();
    expect(Date.parse(session.session_expires_at)).toBeGreaterThan(Date.now() + 29 * 86400_000);

    const event = { title: "电脑会议", start_local: "2030-10-28T09:00:00", end_local: "2030-10-28T10:00:00", timezone: "Asia/Shanghai", status: "planned" };
    const commit = await desktop.post("/api/events/commit", { data: { request_id: randomUUID(), events: [{ title: event.title, start_local: event.start_local, end_local: event.end_local, timezone: event.timezone, time_source: "explicit", assumptions: [] }] } });
    expect(commit.status()).toBe(200);
    const mutation = { mutation_id: randomUUID(), id: randomUUID(), base_version: 0, deleted: false, event: { ...event, title: "手机离线安排" } };
    const payload = { account_id: user.id, mutations: [mutation] };
    const merged = await phone.post("/api/events/sync", { data: payload });
    expect(merged.status()).toBe(200);
    const result = await merged.json();
    expect(result.events).toHaveLength(2);
    expect(result.results[0].status).toBe("applied");
    expect(await (await phone.post("/api/events/sync", { data: payload })).json()).toEqual(result);

    const updated = await desktop.patch(`/api/events/${mutation.id}`, { data: { base_version: 1, title: "电脑已改" } });
    expect(updated.status()).toBe(200);
    const current = await phone.get(`/api/events/${mutation.id}`);
    expect(current.status()).toBe(200);
    expect((await current.json()).event.title).toBe("电脑已改");
    const conflict = await (await phone.post("/api/events/sync", { data: { account_id: user.id, mutations: [{ ...mutation, mutation_id: randomUUID(), base_version: 1 }] } })).json();
    expect(conflict.results[0].code).toBe("VERSION_CONFLICT");
    expect(conflict.results[0].event.title).toBe("电脑已改");
    const deletion = await phone.post("/api/events/sync", { data: { account_id: user.id, mutations: [{ id: mutation.id, mutation_id: randomUUID(), base_version: 2, deleted: true }] } });
    expect(deletion.status()).toBe(200);
    expect((await deletion.json()).events.find((item: { id: string }) => item.id === mutation.id).deleted_at).toBeTruthy();
    expect((await (await desktop.get(`/api/events/${mutation.id}`)).json()).event.deleted_at).toBeTruthy();

    const resumed = await playwright.request.newContext({ baseURL, storageState: await phone.storageState() });
    try { expect((await (await resumed.get("/api/auth/session")).json()).user.id).toBe(user.id); }
    finally { await resumed.dispose(); }
    await desktop.post("/api/auth/logout");
    expect((await (await phone.get("/api/auth/session")).json()).user.id).toBe(user.id);
    await phone.post("/api/auth/logout");
    expect((await phone.post("/api/events/sync", { data: payload })).status()).toBe(401);
  } finally { await desktop.dispose(); await phone.dispose(); }
});

test("sync API rejects foreign origins, changed accounts and invalid batches before writing", async ({ request }) => {
  const registered = await (await request.post("/api/auth/register", { data: { email: `sync-guard-${randomUUID()}@example.com`, password: "test-sync-password" } })).json();
  const payload = { account_id: registered.user.id, mutations: [] };
  expect((await request.post("/api/events/sync", { data: payload, headers: { Origin: "https://foreign.example" } })).status()).toBe(403);
  const wrongAccount = await request.post("/api/events/sync", { data: { ...payload, account_id: randomUUID() } });
  expect(wrongAccount.status()).toBe(409);
  expect((await wrongAccount.json()).error.code).toBe("ACCOUNT_MISMATCH");
  expect((await request.post("/api/events/sync", { data: { ...payload, mutations: [{ id: "invalid" }] } })).status()).toBe(400);
  expect((await (await request.post("/api/events/sync", { data: payload })).json()).events).toEqual([]);
});
