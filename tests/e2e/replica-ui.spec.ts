import { expect, test, type APIRequestContext } from "@playwright/test";
import { randomUUID } from "node:crypto";
import { createRevision, type ReplicaRecord } from "../../src/lib/replica";
import type { ScheduleEvent } from "../../src/lib/types";
import { shanghaiDate } from "../../src/lib/time";
import { bootstrapDevice, loadWorkbench, resetTestDevice } from "./device-fixtures";

test.beforeEach(async ({ page }) => { await resetTestDevice(page.request); });

async function record(request: APIRequestContext, deviceId: string, eventId: string): Promise<ReplicaRecord> {
  let after: string | undefined;
  do {
    const response = await request.post("/api/replica/exchange", { data: { device_id: deviceId, records: [], ...(after ? { after } : {}) } });
    expect(response.status()).toBe(200);
    const result = await response.json();
    const found = result.records.find((item: ReplicaRecord) => item.id === eventId);
    if (found) return found;
    after = result.has_more ? result.next_cursor : undefined;
  } while (after);
  throw new Error("Expected replica record was not exported");
}

test("replica choices have no default and a new arriving version forces an explicit fresh choice", async ({ page }) => {
  const owner = await bootstrapDevice(page.request);
  const today = shanghaiDate(new Date());
  const saved = await page.request.post("/api/events/commit", { data: { request_id: randomUUID(), events: [{
    title: "多设备会议", start_local: `${today}T09:00:00`, end_local: `${today}T10:00:00`,
    timezone: "Asia/Shanghai", time_source: "explicit", assumptions: [],
  }] } });
  expect(saved.status()).toBe(200);
  const event: ScheduleEvent = (await saved.json()).events[0];
  const original = await record(page.request, owner.device!.id, event.id);
  expect((await page.request.patch(`/api/events/${event.id}`, { data: { base_version: event.version, title: "电脑 A 的安排" } })).status()).toBe(200);
  const mobile = createRevision({ baseHeads: original.heads, revisionId: randomUUID(), payload: { ...original.heads[0].payload, title: "手机 B 的安排" }, author: "手机 B", changedAt: Date.now() });
  expect((await page.request.post("/api/replica/exchange", { data: { device_id: owner.device!.id, records: [{ id: event.id, heads: [mobile] }] } })).status()).toBe(200);
  await loadWorkbench(page);
  await expect(page.getByRole("button", { name: /编辑日程：/ })).toBeDisabled();
  await page.getByRole("button", { name: /处理版本冲突：/ }).click();
  const dialog = page.getByRole("dialog", { name: "处理版本冲突", exact: true });
  await expect(dialog.getByRole("radio")).toHaveCount(2);
  await expect(dialog.locator('input[type="radio"]:checked')).toHaveCount(0);
  await expect(dialog.getByRole("button", { name: "使用所选版本", exact: true })).toBeDisabled();
  await dialog.getByRole("radio", { name: "选择版本：手机 B：手机 B 的安排", exact: true }).check();

  const deletion = createRevision({ baseHeads: original.heads, revisionId: randomUUID(), payload: { ...original.heads[0].payload, deleted: true }, author: "手机 C", changedAt: Date.now() });
  expect((await page.request.post("/api/replica/exchange", { data: { device_id: owner.device!.id, records: [{ id: event.id, heads: [deletion] }] } })).status()).toBe(200);
  await dialog.getByRole("button", { name: "使用所选版本", exact: true }).click();
  await expect(dialog.getByRole("alert")).toContainText("又收到了新的修改");
  await expect(dialog.getByRole("radio")).toHaveCount(3);
  await expect(dialog.locator('input[type="radio"]:checked')).toHaveCount(0);
  await expect(dialog.getByRole("button", { name: "使用所选版本", exact: true })).toBeDisabled();
  await dialog.getByRole("radio", { name: "选择版本：手机 C：多设备会议（删除）", exact: true }).check();
  await dialog.getByRole("button", { name: "使用所选版本", exact: true }).click();
  await expect(dialog).not.toBeVisible();
  const current = (await (await page.request.get(`/api/events/${event.id}`)).json()).event;
  expect(current.deleted_at).toBeTruthy();
  expect(current.replica_conflicts).toBeUndefined();
  expect((await page.request.post("/api/replica/exchange", { data: { device_id: owner.device!.id, records: [{ id: event.id, heads: [mobile] }] } })).status()).toBe(200);
  expect((await (await page.request.get(`/api/events/${event.id}`)).json()).event.deleted_at).toBeTruthy();
});

test("owner peer controls disclose transitive sharing and dispatch connect, sync and unpair", async ({ page }) => {
  const owner = await bootstrapDevice(page.request);
  const peerId = randomUUID();
  let peers = [{ id: peerId, name: "另一台手机", origin: "http://192.168.1.88:43127", connected: true, last_sync_at: null, error: null }];
  let connection = { state: "idle" };
  let connected: { url: string } | null = null;
  let syncs = 0;
  await page.route("**/api/devices", route => route.fulfill({ json: {
    devices: [{ id: owner.peer!.id, client_id: owner.device!.id, name: "测试电脑", platform: "test", current: true, local: true, created_at: new Date().toISOString(), last_seen_at: null }],
    peers, connection,
  } }));
  await page.route("**/api/peers/connect", route => {
    connected = route.request().postDataJSON(); connection = { state: "connecting" };
    return route.fulfill({ status: 202, json: { queued: true } });
  });
  await page.route("**/api/peers/sync", route => { syncs++; return route.fulfill({ status: 202, json: { queued: true } }); });
  await page.route(`**/api/peers/${peerId}`, route => { peers = []; return route.fulfill({ json: { revoked: true } }); });
  await loadWorkbench(page);
  await page.locator("header").getByRole("button", { name: "打开设备与同步", exact: true }).click();
  const dialog = page.getByRole("dialog", { name: "设备与同步", exact: true });
  await expect(dialog).toContainText("也可能经对方传到它已配对的其他设备");
  const link = `http://192.168.1.88:43127/#pair=${"a".repeat(43)}`;
  await dialog.getByLabel("另一台设备的配对链接", { exact: true }).fill(link);
  await dialog.getByRole("button", { name: "同意并连接设备", exact: true }).click();
  await expect(dialog.getByLabel("另一台设备的配对链接", { exact: true })).toHaveValue("");
  expect(connected).toMatchObject({ url: link });
  await expect(dialog).toContainText("正在连接设备…");
  await dialog.getByRole("button", { name: "立即同步已配对设备", exact: true }).click();
  await expect.poll(() => syncs).toBe(1);
  await dialog.getByRole("button", { name: "解除配对：另一台手机", exact: true }).click();
  await expect(dialog).toContainText("已经获得的日程副本不会被撤回");
  await dialog.getByRole("button", { name: "确认解除配对", exact: true }).click();
  await expect(dialog.locator(`[data-peer-id="${peerId}"]`)).toHaveCount(0);
});
