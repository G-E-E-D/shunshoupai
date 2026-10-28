import { expect, type APIRequestContext, type Page } from "@playwright/test";
import { randomUUID } from "node:crypto";
import type { ModelSettingsInfo, ScheduleEvent, SessionInfo } from "../../src/lib/types";

const bootstrapKey = process.env.APP_DEVICE_BOOTSTRAP_KEY || "test-only-device-bootstrap-key-2026";

export async function bootstrapDevice(request: APIRequestContext): Promise<SessionInfo> {
  const response = await request.post("/api/devices/bootstrap", {
    headers: { "X-Device-Bootstrap": bootstrapKey },
    data: { name: "测试电脑", platform: "test" },
  });
  expect(response.status()).toBe(200);
  return response.json();
}

export async function pairDevice(owner: APIRequestContext, device: APIRequestContext, name = "测试手机") {
  const ticketResponse = await owner.post("/api/pairing/issue", { data: {} });
  expect(ticketResponse.status()).toBe(200);
  const ticket = await ticketResponse.json();
  const response = await device.post("/api/pairing/redeem", {
    data: { token: ticket.token, device_id: randomUUID(), device_name: name, platform: "test" },
  });
  expect(response.status()).toBe(200);
  return response.json() as Promise<SessionInfo>;
}

export async function loadWorkbench(page: Page) {
  const loaded = page.waitForResponse(response => response.url().endsWith("/api/auth/session"));
  await page.goto("/");
  await loaded;
  await expect(page.locator(".sync-badge")).toHaveText("连接正常");
}

// The dedicated e2e-device.sqlite service has one local space, just like the
// installed app. Playwright runs these scenarios serially (workers: 1). Clear
// only this isolated test service through its public API; retain tombstones.
export async function resetTestDevice(request: APIRequestContext) {
  const session = await bootstrapDevice(request);
  expect(session.user).not.toBeNull();
  const events: ScheduleEvent[] = [];
  let after: string | undefined;
  do {
    const response = await request.post("/api/events/sync", {
      data: { space_id: session.space_id ?? session.user!.id, mutations: [], ...(after ? { after } : {}) },
    });
    expect(response.status()).toBe(200);
    const result = await response.json();
    events.push(...result.events);
    after = result.has_more ? result.next_cursor : undefined;
  } while (after);
  for (const event of events) {
    if (event.deleted_at) continue;
    const read = await request.get(`/api/events/${event.id}`);
    expect(read.status()).toBe(200);
    let latest: ScheduleEvent = (await read.json()).event;
    if ((latest.replica_conflicts?.length ?? 0) > 1) {
      // This is explicit cleanup of the serial, disposable e2e service. The
      // application UI itself must never default to one conflict candidate.
      const choice = latest.replica_conflicts!.find(head => head.payload.deleted) ?? latest.replica_conflicts![0];
      const resolved = await request.post(`/api/events/${event.id}/resolve`, { data: { base_version: latest.version, revision_id: choice.revision_id } });
      expect(resolved.status()).toBe(200);
      latest = (await resolved.json()).event;
    }
    if (latest.deleted_at) continue;
    const response = await request.delete(`/api/events/${event.id}`, { data: { base_version: latest.version } });
    expect(response.status()).toBe(200);
  }
  const response = await request.get("/api/model-settings");
  expect(response.status()).toBe(200);
  const { settings } = await response.json() as { settings: ModelSettingsInfo };
  if (settings.configured) {
    expect((await request.delete("/api/model-settings", { data: { base_version: settings.version } })).status()).toBe(200);
  }
}
