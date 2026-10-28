import {
  expect,
  test,
  type APIRequestContext,
} from "@playwright/test";
import { randomUUID } from "node:crypto";
import { mkdir } from "node:fs/promises";
import type { EventDraft, ScheduleEvent } from "../../src/lib/types";
import { addDays, localToIso, shanghaiDate } from "../../src/lib/time";
import { dayLabel } from "../../src/lib/client";
import { loadWorkbench, pairDevice, resetTestDevice } from "./device-fixtures";

const example =
  "明天下午三点到四点面试，今天晚上七点到八点改简历，八点到九点准备面试。";
const today = shanghaiDate(new Date());
const query = new URLSearchParams({
  from: localToIso(`${today}T00:00:00`),
  to: localToIso(`${addDays(today, 7)}T00:00:00`),
});

test.beforeEach(async ({ page }) => { await resetTestDevice(page.request); });

async function list(request: APIRequestContext): Promise<ScheduleEvent[]> {
  const response = await request.get(`/api/events?${query}`);
  expect(response.status()).toBe(200);
  return (await response.json()).events;
}

function draft(title: string, start = "09:00", end = "10:00"): EventDraft {
  return {
    title,
    start_local: `${today}T${start}:00`,
    end_local: `${today}T${end}:00`,
    timezone: "Asia/Shanghai",
    time_source: "explicit",
    assumptions: [],
  };
}

test("the owner parses locally while a paired device views and edits only schedules", async ({
  page,
  browser,
}, testInfo) => {
  await page.setViewportSize({ width: 1440, height: 1200 });
  await loadWorkbench(page);
  const mobileContext = await browser.newContext({
    baseURL: "http://localhost:3001",
    viewport: { width: 360, height: 800 },
    isMobile: true,
    hasTouch: true,
  });
  const mobile = await mobileContext.newPage();
  try {
    await pairDevice(page.request, mobile.request);
    await loadWorkbench(mobile);
    await expect(mobile.getByLabel("描述你的日程安排")).toHaveCount(0);
    await expect(mobile.getByRole("button", { name: "一句话安排", exact: true })).toHaveCount(0);
    await expect(mobile.getByText("配对仅同步日程，请在本机应用使用智能整理。", { exact: true })).toBeVisible();
    await mobile.getByRole("button", { name: "七天", exact: true }).click();
    await page.getByLabel("描述你的日程安排").fill(example);
    await page.getByRole("button", { name: "帮我整理一下" }).click();
    await expect(page.getByText("3 项草稿，等你确认")).toBeVisible();
    expect(await list(page.request)).toHaveLength(0);
    await expect(page.getByLabel("草稿 1 标题")).toHaveValue("面试");
    await page.getByLabel("草稿 1 标题").fill("产品实习面试");
    await mkdir("artifacts/screenshots", { recursive: true });
    const dismissNotice = page.getByRole("button", {
      name: "关闭提示",
      exact: true,
    });
    if (await dismissNotice.isVisible()) await dismissNotice.click();
    await page.evaluate(() => window.scrollTo(0, 0));
    await page.screenshot({
      path: "artifacts/screenshots/drafts.png",
      fullPage: true,
    });
    const savedResponse = page.waitForResponse(
      (response) =>
        response.url().endsWith("/api/events/commit") &&
        response.request().method() === "POST",
    );
    await page
      .getByRole("button", { name: "确认保存 3 项", exact: true })
      .click();
    expect((await savedResponse).status()).toBe(200);
    const saveTime = Date.now();
    await expect(
      mobile.getByRole("heading", { name: "产品实习面试", exact: true }),
    ).toBeVisible();
    await expect(
      mobile.getByRole("heading", { name: "改简历", exact: true }),
    ).toBeVisible();
    const syncMs = Date.now() - saveTime;
    console.info(
      `Measured cross-context save visibility: ${syncMs} ms (8-second foreground polling).`,
    );
    await testInfo.attach("cross-device-sync.json", {
      body: JSON.stringify({
        measuredMs: syncMs,
        mode: "8s foreground polling",
        browserContexts: 2,
      }),
      contentType: "application/json",
    });
    expect(await list(page.request)).toHaveLength(3);

    await page
      .getByRole("button", { name: "编辑日程：产品实习面试", exact: true })
      .click();
    await page
      .getByRole("dialog")
      .getByLabel("日程标题", { exact: true })
      .fill("产品实习面试 · 已确认");
    await page.getByRole("button", { name: "保存日程", exact: true }).click();
    await expect(page.getByRole("dialog")).not.toBeVisible();
    await expect(
      mobile.getByRole("heading", {
        name: "产品实习面试 · 已确认",
        exact: true,
      }),
    ).toBeVisible();
    await page.getByRole("button", { name: "回今天", exact: true }).click();
    await page.getByRole("button", { name: "七天", exact: true }).click();
    await expect(
      page.getByRole("heading", { name: "准备面试", exact: true }),
    ).toBeVisible();

    await page.evaluate(() => window.scrollTo(0, 0));
    await page.screenshot({
      path: "artifacts/screenshots/desktop.png",
      fullPage: true,
    });
    await mobile.evaluate(() => window.scrollTo(0, 0));
    await mobile.screenshot({
      path: "artifacts/screenshots/mobile.png",
      fullPage: true,
    });
    const dimensions = await mobile.evaluate(() => ({
      viewport: innerWidth,
      page: document.documentElement.scrollWidth,
    }));
    expect(dimensions.page).toBeLessThanOrEqual(dimensions.viewport);

    await mobile
      .getByRole("button", { name: "完成日程：改简历", exact: true })
      .click();
    await expect(
      page.getByRole("button", { name: "恢复日程：改简历", exact: true }),
    ).toBeVisible();
    await page
      .getByRole("button", { name: "删除日程：准备面试", exact: true })
      .click();
    await page.getByRole("button", { name: "确认删除", exact: true }).click();
    await expect(
      mobile.getByRole("heading", { name: "准备面试", exact: true }),
    ).not.toBeVisible();
    expect(await list(page.request)).toHaveLength(2);
  } finally {
    await mobileContext.close();
  }
});

test("HTTP mutations enforce idempotency, isolation, optimistic versions and soft deletion", async ({
  page,
  browser,
}) => {
  await loadWorkbench(page);
  const otherContext = await browser.newContext({
    baseURL: "http://localhost:3001",
  });
  const other = await otherContext.newPage();
  try {
    const payload = {
      request_id: randomUUID(),
      events: [draft("接口验证日程")],
    };
    const first = await page.request.post("/api/events/commit", {
      data: payload,
    });
    expect(first.status()).toBe(200);
    expect(first.headers()["cache-control"]).toContain("no-store");
    const event: ScheduleEvent = (await first.json()).events[0];
    const replay = await page.request.post("/api/events/commit", {
      data: payload,
    });
    expect(replay.status()).toBe(200);
    expect((await replay.json()).replayed).toBe(true);
    expect(await list(page.request)).toHaveLength(1);
    const changed = await page.request.post("/api/events/commit", {
      data: { ...payload, events: [draft("改变内容")] },
    });
    expect(changed.status()).toBe(409);
    expect((await changed.json()).error.code).toBe("IDEMPOTENCY_CONFLICT");
    expect(
      (
        await page.request.post("/api/events/commit", {
          data: { request_id: randomUUID(), events: [draft("伪造来源")] },
          headers: { origin: "https://untrusted.example" },
        })
      ).status(),
    ).toBe(403);
    expect((await other.request.get(`/api/events?${query}`)).status()).toBe(401);
    expect(
      (
        await other.request.patch(`/api/events/${event.id}`, {
          data: { base_version: 1, title: "越权修改" },
        })
      ).status(),
    ).toBe(401);
    expect(
      (
        await other.request.delete(`/api/events/${event.id}`, {
          data: { base_version: 1 },
        })
      ).status(),
    ).toBe(401);
    expect(
      (
        await page.request.patch(`/api/events/${event.id}`, {
          data: { base_version: 1, title: "更新后的日程" },
        })
      ).status(),
    ).toBe(200);
    const stale = await page.request.patch(`/api/events/${event.id}`, {
      data: { base_version: 1, title: "旧端覆盖" },
    });
    expect(stale.status()).toBe(409);
    expect((await stale.json()).error.code).toBe("VERSION_CONFLICT");
    const deleted = await page.request.delete(`/api/events/${event.id}`, {
      data: { base_version: 2 },
    });
    expect(deleted.status()).toBe(200);
    expect((await deleted.json()).event.deleted_at).toBeTruthy();
    expect(await list(page.request)).toHaveLength(0);
    expect(
      (
        await page.request.delete(`/api/events/${event.id}`, {
          data: { base_version: 2 },
        })
      ).status(),
    ).toBe(200);
  } finally {
    await otherContext.close();
  }
});

test("offline save keeps the draft unsaved and reconnect can save it exactly once", async ({
  page,
  context,
}) => {
  await loadWorkbench(page);
  await page.getByLabel("描述你的日程安排").fill("今天上午九点到十点复盘");
  await page.getByRole("button", { name: "帮我整理一下" }).click();
  await expect(page.getByText("1 项草稿，等你确认")).toBeVisible();
  await context.setOffline(true);
  await expect(page.locator(".sync-badge")).toHaveText("当前离线");
  await page
    .getByRole("button", { name: "确认保存 1 项", exact: true })
    .click();
  await expect(
    page.getByRole("alert").filter({ hasText: "尚未发出保存请求" }),
  ).toBeVisible();
  await expect(page.getByLabel("草稿 1 标题")).toHaveValue("复盘");
  await context.setOffline(false);
  expect(await list(page.request)).toHaveLength(0);
  await page
    .getByRole("button", { name: "确认保存 1 项", exact: true })
    .click();
  await expect(
    page.getByRole("heading", { name: "复盘", exact: true }),
  ).toBeVisible();
  expect(await list(page.request)).toHaveLength(1);
});

test("ambiguous input asks for a correction without persisting guesses", async ({
  page,
}) => {
  await loadWorkbench(page);
  await page.getByLabel("描述你的日程安排").fill("周五三点面试");
  await page.getByRole("button", { name: "帮我整理一下" }).click();
  await expect(page.getByText("还需要一点信息")).toBeVisible();
  await expect(page.getByText(/请说明上午或下午/)).toBeVisible();
  await expect(page.getByRole("button", { name: /确认保存/ })).toHaveCount(0);
  expect(await list(page.request)).toHaveLength(0);
});

test("a lost successful save response keeps its request id and retries without duplication", async ({
  page,
}) => {
  await loadWorkbench(page);
  await page
    .getByLabel("描述你的日程安排")
    .fill("今天下午两点到三点梳理作品集");
  await page.getByRole("button", { name: "帮我整理一下" }).click();
  await expect(page.getByText("1 项草稿，等你确认")).toBeVisible();
  const requestIds: string[] = [];
  let releaseResponse!: () => void;
  let confirmServerCommit!: () => void;
  const responseGate = new Promise<void>((resolve) => {
    releaseResponse = resolve;
  });
  const serverCommitted = new Promise<void>((resolve) => {
    confirmServerCommit = resolve;
  });
  await page.route("**/api/events/commit", async (route) => {
    const payload = route.request().postDataJSON();
    requestIds.push(payload.request_id);
    const response = await route.fetch();
    expect(response.status()).toBe(200);
    if (requestIds.length === 1) {
      confirmServerCommit();
      await responseGate;
      await route.abort("failed");
    } else await route.fulfill({ response });
  });
  await page
    .getByRole("button", { name: "确认保存 1 项", exact: true })
    .click();
  await serverCommitted;
  await expect(page.getByLabel("描述你的日程安排")).toBeDisabled();
  await expect(
    page.getByRole("button", { name: "帮我整理一下" }),
  ).toBeDisabled();
  releaseResponse();
  await expect(
    page.getByRole("alert").filter({ hasText: "保存状态待确认" }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "核实并重试", exact: true }),
  ).toBeEnabled();
  expect(await list(page.request)).toHaveLength(1);
  await page.getByRole("button", { name: "核实并重试", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "梳理作品集", exact: true }),
  ).toBeVisible();
  expect(requestIds).toHaveLength(2);
  expect(requestIds[0]).toBe(requestIds[1]);
  expect(await list(page.request)).toHaveLength(1);
});

test("an open page follows Shanghai midnight without a hydration mismatch", async ({ page }) => {
  const hydrationErrors: string[] = [];
  page.on("pageerror", error => hydrationErrors.push(error.message));
  page.on("console", message => {
    if (message.type() === "error" && /hydrat|didn't match|server rendered|text content does not match/i.test(message.text())) {
      hydrationErrors.push(message.text());
    }
  });
  await page.clock.install({ time: new Date(`${today}T23:59:50+08:00`) });
  const sessionLoaded = page.waitForResponse(response => response.url().endsWith("/api/auth/session") && response.request().method() === "GET");
  await page.goto("/");
  await sessionLoaded;
  await expect(page.locator(".current-date strong")).toHaveText(dayLabel(today, true));
  await page.clock.fastForward(31_000);
  await expect(page.locator(".current-date strong")).toHaveText(dayLabel(addDays(today, 1), true));
  await expect(page.locator(".current-date")).toContainText("今天");
  expect(hydrationErrors).toEqual([]);
});

test("reviewed and manual drafts survive refresh without being submitted", async ({ page }) => {
  await loadWorkbench(page);
  await page.getByLabel("描述你的日程安排").fill("今天上午九点到十点复盘");
  await page.getByRole("button", { name: "帮我整理一下" }).click();
  await expect(page.getByLabel("草稿 1 标题")).toHaveValue("复盘");
  await page.getByLabel("草稿 1 标题").fill("还没写完的复盘");
  await page.getByLabel("草稿 1 开始时间").fill(`${today}T11:00`);
  await page.getByLabel("草稿 1 结束时间").fill(`${today}T12:00`);
  await page.reload();
  await expect(page.getByLabel("草稿 1 标题")).toHaveValue("还没写完的复盘");
  await expect(page.getByLabel("草稿 1 开始时间")).toHaveValue(`${today}T11:00`);
  await expect(page.getByRole("button", { name: "确认保存 1 项", exact: true })).toBeEnabled();
  expect(await list(page.request)).toHaveLength(0);

  await page.getByRole("button", { name: "手动添加", exact: true }).click();
  await page.getByRole("dialog").getByLabel("日程标题", { exact: true }).fill("手动输入的安排");
  await page.getByRole("dialog").getByLabel("开始时间", { exact: true }).fill(`${today}T14:00`);
  await expect(page.getByRole("dialog").getByLabel("结束时间", { exact: true })).toHaveValue(`${today}T15:00`);
  await page.getByRole("button", { name: "关闭弹窗", exact: true }).click();
  await page.reload();
  await expect(page.locator(".sync-badge")).toHaveText("连接正常");
  await page.getByRole("button", { name: "手动添加", exact: true }).click();
  await expect(page.getByRole("dialog").getByLabel("日程标题", { exact: true })).toHaveValue("手动输入的安排");
  await expect(page.getByRole("dialog").getByLabel("开始时间", { exact: true })).toHaveValue(`${today}T14:00`);
  await page.getByRole("button", { name: "保存日程", exact: true }).click();
  await expect(page.getByRole("dialog")).not.toBeVisible();
  expect(await list(page.request)).toHaveLength(1);
  await page.getByRole("button", { name: "手动添加", exact: true }).click();
  await expect(page.getByRole("dialog").getByLabel("日程标题", { exact: true })).toHaveValue("");
});

test("a failed startup session request automatically recovers the existing device connection", async ({ page }) => {
  await loadWorkbench(page);
  let attempts = 0;
  await page.route("**/api/auth/session", async route => {
    attempts++;
    if (attempts === 1) await route.abort("failed");
    else await route.continue();
  });
  await page.reload();
  await expect(page.locator(".sync-badge")).toHaveText("连接待恢复");
  await expect(page.locator(".sync-badge")).toHaveText("连接正常", { timeout: 20_000 });
  await expect(page.getByRole("button", { name: "打开设备与同步", exact: true })).toBeVisible();
  expect(attempts).toBeGreaterThanOrEqual(2);
});

test("background polling lets a slow refresh finish without discarding its result", async ({ page }) => {
  await loadWorkbench(page);
  await page.request.post("/api/events/commit", { data: { request_id: randomUUID(), events: [draft("慢网络仍能看到的新日程")] } });
  let attempts = 0;
  let release!: () => void;
  const gate = new Promise<void>(resolve => { release = resolve; });
  await page.route("**/api/events?**", async route => {
    attempts++;
    const response = await route.fetch();
    await gate;
    await route.fulfill({ response });
  });
  await page.clock.install();
  await page.getByRole("button", { name: "重新同步日程", exact: true }).click();
  await expect.poll(() => attempts).toBe(1);
  await page.clock.fastForward(9000);
  release();
  await expect(page.getByRole("heading", { name: "慢网络仍能看到的新日程", exact: true })).toBeVisible();
  expect(attempts).toBe(1);
});

test("a restored edit keeps its original version until the user resolves a remote update", async ({ page }) => {
  await loadWorkbench(page);
  const created = await page.request.post("/api/events/commit", {
    data: { request_id: randomUUID(), events: [draft("原始安排")] },
  });
  const event: ScheduleEvent = (await created.json()).events[0];
  await page.getByRole("button", { name: "重新同步日程", exact: true }).click();
  await page.getByRole("button", { name: "编辑日程：原始安排", exact: true }).click();
  await page.getByRole("dialog").getByLabel("日程标题", { exact: true }).fill("手机未完成的编辑");
  const remote = await page.request.patch(`/api/events/${event.id}`, {
    data: { base_version: 1, title: "电脑更新的安排", start_local: `${today}T10:00:00`, end_local: `${today}T11:00:00` },
  });
  expect(remote.status()).toBe(200);
  const staleResponse = page.waitForResponse(response => response.request().method() === "PATCH" && response.url().endsWith(`/api/events/${event.id}`));
  await page.getByRole("button", { name: "保存日程", exact: true }).click();
  const rejected = await staleResponse;
  expect(rejected.request().postDataJSON().base_version).toBe(1);
  expect(rejected.status()).toBe(409);
  await expect(page.getByRole("dialog").getByText("另一端已更新这项日程，你的编辑草稿仍然保留。", { exact: true })).toBeVisible();
  await expect(page.getByRole("dialog").getByLabel("日程标题", { exact: true })).toHaveValue("手机未完成的编辑");
  await page.getByRole("button", { name: "关闭弹窗", exact: true }).click();
  await page.reload();
  await page.getByRole("button", { name: "编辑日程：电脑更新的安排", exact: true }).click();
  await expect(page.getByRole("dialog").getByLabel("日程标题", { exact: true })).toHaveValue("手机未完成的编辑");
  await expect(page.getByRole("button", { name: "保存日程", exact: true })).toBeDisabled();
  await expect(page.getByRole("dialog").getByText(new RegExp(`${today} 10:00`))).toBeVisible();
  // A second close/reopen must not silently adopt the newer base version.
  await page.getByRole("button", { name: "关闭弹窗", exact: true }).click();
  await page.getByRole("button", { name: "编辑日程：电脑更新的安排", exact: true }).click();
  await expect(page.getByRole("button", { name: "保存日程", exact: true })).toBeDisabled();
  expect((await list(page.request))[0].title).toBe("电脑更新的安排");
  const confirmed = page.waitForResponse(response => response.request().method() === "PATCH" && response.url().endsWith(`/api/events/${event.id}`));
  await page.getByRole("button", { name: "保留我的修改并保存", exact: true }).click();
  const accepted = await confirmed;
  expect(accepted.request().postDataJSON().base_version).toBe(2);
  expect(accepted.status()).toBe(200);
  await expect(page.getByRole("dialog")).not.toBeVisible();
  expect((await list(page.request))[0].title).toBe("手机未完成的编辑");

  await page.getByRole("button", { name: "编辑日程：手机未完成的编辑", exact: true }).click();
  await page.getByRole("dialog").getByLabel("日程标题", { exact: true }).fill("这份编辑将被放弃");
  await page.getByRole("button", { name: "关闭弹窗", exact: true }).click();
  const newer = await page.request.patch(`/api/events/${event.id}`, { data: { base_version: 3, title: "电脑最终版本" } });
  expect(newer.status()).toBe(200);
  await page.reload();
  await page.getByRole("button", { name: "编辑日程：电脑最终版本", exact: true }).click();
  await page.getByRole("button", { name: "使用另一端版本", exact: true }).click();
  await expect(page.getByRole("dialog").getByLabel("日程标题", { exact: true })).toHaveValue("电脑最终版本");
  await expect(page.getByRole("button", { name: "保存日程", exact: true })).toBeEnabled();
  await page.getByRole("button", { name: "关闭弹窗", exact: true }).click();
  await page.getByRole("button", { name: "编辑日程：电脑最终版本", exact: true }).click();
  await expect(page.getByRole("dialog").getByLabel("日程标题", { exact: true })).toHaveValue("电脑最终版本");
  await expect(page.getByRole("button", { name: "使用另一端版本", exact: true })).toHaveCount(0);
});
