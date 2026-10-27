import {
  expect,
  test,
  type Page,
  type APIRequestContext,
} from "@playwright/test";
import { randomUUID } from "node:crypto";
import { mkdir } from "node:fs/promises";
import type { EventDraft, ScheduleEvent } from "../../src/lib/types";
import { addDays, localToIso, shanghaiDate } from "../../src/lib/time";

const password = "Synthetic-demo-password-2026";
const example =
  "明天下午三点到四点面试，今天晚上七点到八点改简历，八点到九点准备面试。";
const today = shanghaiDate(new Date());
const query = new URLSearchParams({
  from: localToIso(`${today}T00:00:00`),
  to: localToIso(`${addDays(today, 7)}T00:00:00`),
});

async function authenticate(page: Page, email: string, register = true) {
  const sessionLoaded = page.waitForResponse(
    (response) =>
      response.url().endsWith("/api/auth/session") &&
      response.request().method() === "GET",
  );
  await page.goto("/");
  await sessionLoaded;
  await page
    .locator("header")
    .getByRole("button", { name: "登录账号", exact: true })
    .click();
  const dialog = page.getByRole("dialog");
  if (register)
    await dialog.getByRole("button", { name: "注册账号", exact: true }).click();
  await dialog.getByLabel("邮箱", { exact: true }).fill(email);
  await dialog.getByLabel("密码", { exact: true }).fill(password);
  await dialog
    .getByRole("button", {
      name: register ? "创建账号" : "登录，继续安排",
      exact: true,
    })
    .click();
  await expect(dialog).not.toBeVisible();
  await expect(page.locator(".sync-badge")).toHaveText("已同步");
}

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

test("two independent devices parse, review, save, synchronize edits, complete and delete", async ({
  page,
  browser,
}, testInfo) => {
  const email = `demo-${randomUUID()}@example.com`;
  await page.setViewportSize({ width: 1440, height: 1200 });
  await authenticate(page, email);
  const mobileContext = await browser.newContext({
    baseURL: "http://localhost:3001",
    viewport: { width: 360, height: 800 },
    isMobile: true,
    hasTouch: true,
  });
  const mobile = await mobileContext.newPage();
  try {
    await authenticate(mobile, email, false);
    await mobile.getByRole("button", { name: "七天", exact: true }).click();
    await page.getByLabel("描述你的日程安排").fill(example);
    await page.getByRole("button", { name: "帮我整理一下" }).click();
    await expect(page.getByText("3 项草稿，等你确认")).toBeVisible();
    expect(await list(page.request)).toHaveLength(0);
    await expect(page.getByLabel("草稿 1 标题")).toHaveValue("面试");
    await page.getByLabel("草稿 1 标题").fill("产品实习面试");
    await mkdir("docs/screenshots", { recursive: true });
    const dismissNotice = page.getByRole("button", {
      name: "关闭提示",
      exact: true,
    });
    if (await dismissNotice.isVisible()) await dismissNotice.click();
    await page.evaluate(() => window.scrollTo(0, 0));
    await page.screenshot({
      path: "docs/screenshots/drafts.png",
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
      path: "docs/screenshots/desktop.png",
      fullPage: true,
    });
    await mobile.evaluate(() => window.scrollTo(0, 0));
    await mobile.screenshot({
      path: "docs/screenshots/mobile.png",
      fullPage: true,
    });
    const dimensions = await mobile.evaluate(() => ({
      viewport: innerWidth,
      page: document.documentElement.scrollWidth,
    }));
    expect(dimensions.page).toBeLessThanOrEqual(dimensions.viewport);

    await page
      .getByRole("button", { name: "完成日程：改简历", exact: true })
      .click();
    await expect(
      mobile.getByRole("button", { name: "恢复日程：改简历", exact: true }),
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
  await authenticate(page, `api-a-${randomUUID()}@example.com`);
  const otherContext = await browser.newContext({
    baseURL: "http://localhost:3001",
  });
  const other = await otherContext.newPage();
  try {
    await authenticate(other, `api-b-${randomUUID()}@example.com`);
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
    expect(await list(other.request)).toHaveLength(0);
    expect(
      (
        await other.request.patch(`/api/events/${event.id}`, {
          data: { base_version: 1, title: "越权修改" },
        })
      ).status(),
    ).toBe(404);
    expect(
      (
        await other.request.delete(`/api/events/${event.id}`, {
          data: { base_version: 1 },
        })
      ).status(),
    ).toBe(404);
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
  await authenticate(page, `offline-${randomUUID()}@example.com`);
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
  await authenticate(page, `clarify-${randomUUID()}@example.com`);
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
  await authenticate(page, `uncertain-${randomUUID()}@example.com`);
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
