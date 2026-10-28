import { expect, test, type Page } from "@playwright/test";
import { createServer, type Server } from "node:http";
import type { AddressInfo } from "node:net";
import { randomUUID } from "node:crypto";
import { mkdir } from "node:fs/promises";
import type { ModelSettingsInfo } from "../../src/lib/types";
import { addDays } from "../../src/lib/time";
import { createRevision } from "../../src/lib/replica";
import { pairDevice, resetTestDevice } from "./device-fixtures";

type ProviderRequest = {
  authorization: string;
  body: {
    model?: string;
    messages?: { role: string; content: string }[];
    response_format?: { type: string };
  };
};

let provider: Server;
let providerBase = "";
const requests: ProviderRequest[] = [];
const key = "test-only-personal-model-key-A123";

test.beforeAll(async () => {
  provider = createServer(async (request, response) => {
    if (request.method !== "POST" || request.url !== "/v1/chat/completions") {
      response.writeHead(404).end();
      return;
    }
    const chunks: Buffer[] = [];
    for await (const chunk of request) chunks.push(Buffer.from(chunk));
    const body = JSON.parse(
      Buffer.concat(chunks).toString("utf8"),
    ) as ProviderRequest["body"];
    requests.push({ authorization: request.headers.authorization || "", body });
    response.setHeader("Content-Type", "application/json");
    if (body.model === "failing-model") {
      // A malicious upstream error must never be reflected into the settings UI.
      response
        .writeHead(401)
        .end(JSON.stringify({ error: `rejected credential ${key}` }));
      return;
    }
    const system =
      body.messages?.find((message) => message.role === "system")?.content ||
      "";
    let result: object;
    if (system.includes("顺手排连接测试")) {
      result = { ok: true };
    } else {
      const user = JSON.parse(
        body.messages?.find((message) => message.role === "user")?.content ||
          "{}",
      ) as { reference_time: string };
      const tomorrow = addDays(user.reference_time.slice(0, 10), 1);
      result = {
        intent: "create_events",
        needs_clarification: false,
        questions: [],
        events: [
          {
            title: "个人模型面试",
            start_local: `${tomorrow}T15:00:00`,
            end_local: `${tomorrow}T16:00:00`,
            timezone: "Asia/Shanghai",
            time_source: "explicit",
            assumptions: [],
          },
        ],
      };
    }
    response.end(
      JSON.stringify({
        choices: [
          {
            message: { content: JSON.stringify(result) },
            finish_reason: "stop",
          },
        ],
      }),
    );
  });
  await new Promise<void>((resolve) =>
    provider.listen(0, "127.0.0.1", resolve),
  );
  providerBase = `http://127.0.0.1:${(provider.address() as AddressInfo).port}/v1`;
});

test.afterAll(async () => {
  provider.closeAllConnections();
  await new Promise<void>((resolve, reject) =>
    provider.close((error) => (error ? reject(error) : resolve())),
  );
});

test.beforeEach(async ({ page }) => {
  requests.length = 0;
  await resetTestDevice(page.request);
});

async function loadPage(page: Page) {
  const loaded = page.waitForResponse((response) =>
    response.url().endsWith("/api/auth/session"),
  );
  await page.goto("/");
  await loaded;
  await expect(page.locator(".sync-badge")).toHaveText("连接正常");
}

async function openModelSettings(page: Page) {
  await page
    .locator("header")
    .getByRole("button", { name: "打开设备与同步", exact: true })
    .click();
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "模型设置", exact: true })
    .click();
  const dialog = page.getByRole("dialog", { name: "模型设置", exact: true });
  await expect(dialog.getByLabel("Base URL", { exact: true })).toBeVisible();
  return dialog;
}

async function settings(page: Page): Promise<ModelSettingsInfo> {
  const response = await page.request.get("/api/model-settings");
  expect(response.status()).toBe(200);
  expect(response.headers()["cache-control"]).toContain("no-store");
  const body = await response.json();
  expect(JSON.stringify(body)).not.toContain(key);
  expect(JSON.stringify(body)).not.toContain("api_key_ciphertext");
  return body.settings as ModelSettingsInfo;
}

test("the local owner uses its own model while paired devices receive only schedule access", async ({
  page,
  browser,
  playwright,
  baseURL,
}) => {
  await page.setViewportSize({ width: 1440, height: 1200 });
  expect((await page.request.post("/api/devices/web-session", { data: {} })).status()).toBe(200);
  await loadPage(page);
  let dialog = await openModelSettings(page);
  await dialog.getByLabel("Base URL", { exact: true }).fill(providerBase);
  await dialog.getByLabel("模型名称", { exact: true }).fill("personal-model");
  await dialog.getByLabel("API Key", { exact: true }).fill(key);
  const probe = page.waitForResponse((response) =>
    response.url().endsWith("/api/model-settings/test"),
  );
  await dialog.getByRole("button", { name: "测试连接", exact: true }).click();
  expect((await probe).status()).toBe(200);
  expect((await settings(page)).configured).toBe(false);
  expect(requests).toHaveLength(1);
  expect(requests[0].authorization).toBe(`Bearer ${key}`);
  expect(requests[0].body.messages?.[0].content).toContain("顺手排连接测试");
  expect(requests[0].body.response_format?.type).toBe("json_object");

  const savedResponse = page.waitForResponse(
    (response) =>
      response.url().endsWith("/api/model-settings") &&
      response.request().method() === "PUT",
  );
  await dialog.getByRole("button", { name: "保存配置", exact: true }).click();
  expect((await savedResponse).status()).toBe(200);
  const saved = await settings(page);
  expect(saved.configured).toBe(true);
  expect(saved.effective_source).toBe("personal");
  expect(saved.base_url.replace(/\/$/, "")).toBe(providerBase);
  expect(saved.model).toBe("personal-model");
  expect(saved.key_hint).toContain(key.slice(-4));
  expect(saved.has_api_key).toBe(true);
  await expect(dialog.getByLabel("API Key", { exact: true })).toHaveValue("");
  await mkdir("artifacts/screenshots", { recursive: true });
  await page.screenshot({
    path: "artifacts/screenshots/model-settings.png",
    fullPage: true,
  });

  // Calendar pairing never grants access to this device's model or credential.
  const secondContext = await browser.newContext({
    baseURL: "http://localhost:3001",
    viewport: { width: 360, height: 800 },
    isMobile: true,
    hasTouch: true,
  });
  const second = await secondContext.newPage();
  try {
    const ticket = await (await page.request.post("/api/pairing/issue", { data: {} })).json();
    const clientId = randomUUID();
    const paired = await second.request.post("/api/pairing/redeem", { data: { token: ticket.token, device_id: clientId, device_name: "独立手机", platform: "android" } });
    expect(paired.status()).toBe(200);
    const credential = await paired.json();
    const modelPolls: string[] = [];
    second.on("request", request => { if (request.url().includes("/api/model-settings")) modelPolls.push(request.url()); });
    await loadPage(second);
    await expect(second.getByText("配对仅同步日程，请在本机应用使用智能整理。", { exact: true })).toBeVisible();
    await expect(second.getByLabel("描述你的日程安排")).toHaveCount(0);
    await expect(second.getByRole("button", { name: "一句话安排", exact: true })).toHaveCount(0);
    await second.locator("header").getByRole("button", { name: "打开设备与同步", exact: true }).click();
    await expect(second.getByRole("button", { name: "模型设置", exact: true })).toHaveCount(0);
    await second.getByRole("dialog").getByRole("button", { name: "关闭弹窗", exact: true }).click();
    await second.evaluate(() => window.dispatchEvent(new Event("focus")));
    await expect(second.locator(".sync-badge")).toHaveText("连接正常");
    expect(modelPolls).toHaveLength(0);
    const native = await playwright.request.newContext({ baseURL, extraHTTPHeaders: { Authorization: `Bearer ${credential.device_token}` } });
    try {
      expect((await native.storageState()).cookies).toHaveLength(0);
      expect((await (await page.request.get("/api/auth/session")).json()).ai_provider).toBe("model");
      for (const client of [second.request, native]) {
        for (const path of ["/api/auth/session", "/api/devices/info"]) {
          const session = await (await client.get(path)).json();
          expect(session.role).toBe("peer");
          // This compatibility field stays constant; it reveals no host model state.
          expect(session.ai_provider).toBe("rules");
          expect(session.capabilities).toEqual({ replica: 1, web: false, ai: false });
          expect(session.device.platform).toBe("windows");
          expect(JSON.stringify(session)).not.toContain(providerBase);
          expect(JSON.stringify(session)).not.toContain("personal-model");
          expect(JSON.stringify(session)).not.toContain(key);
        }
        const modelInput = { base_version: saved.version, base_url: providerBase, model: "peer-attempt", api_key: key };
        for (const [path, method, data] of [
          ["/api/model-settings", "GET", undefined],
          ["/api/model-settings", "PUT", modelInput],
          ["/api/model-settings", "DELETE", { base_version: saved.version }],
          ["/api/model-settings/test", "POST", modelInput],
          ["/api/ai/parse", "POST", { text: "明天下午三点到四点面试" }],
          ["/api/devices/web-session", "POST", {}],
        ] as const) {
          const forbidden = await client.fetch(path, { method, ...(data === undefined ? {} : { data }) });
          expect(forbidden.status(), `${method} ${path}`).toBe(403);
          const body = await forbidden.json();
          expect(body.error.code).toBe("SYNC_ONLY_DEVICE");
          expect(JSON.stringify(body)).not.toContain(key);
          expect(JSON.stringify(body)).not.toContain(providerBase);
          expect(forbidden.headers()["set-cookie"]).toBeUndefined();
        }
      }
      expect(await settings(page)).toEqual(saved);
      const id = randomUUID();
      const head = createRevision({ baseHeads: [], revisionId: randomUUID(), author: "手机", changedAt: 1, payload: {
        title: "手机独立整理后同步的日程", start_local: "2034-06-01T09:00:00", end_local: "2034-06-01T10:00:00", timezone: "Asia/Shanghai", status: "planned", deleted: false,
      } });
      const pushed = await native.post("/api/replica/exchange", { data: { device_id: clientId, records: [{ id, heads: [head] }] } });
      expect(pushed.status()).toBe(200);
      const event = (await (await second.request.get(`/api/events/${id}`)).json()).event;
      expect(event.title).toBe(head.payload.title);
      expect((await second.request.patch(`/api/events/${id}`, { data: { base_version: event.version, status: "completed" } })).status()).toBe(200);
      const pulled = await second.request.post("/api/replica/exchange", { data: { device_id: clientId, records: [] } });
      expect(pulled.status()).toBe(200);
      for (const exchanged of [pushed, pulled]) {
        const body = await exchanged.text();
        expect(body).not.toContain(key);
        expect(body).not.toContain(providerBase);
        expect(body).not.toContain("personal-model");
      }
      expect((await (await native.get(`/api/events/${id}`)).json()).event.status).toBe("completed");
    } finally { await native.dispose(); }
    expect(requests).toHaveLength(1);
    expect(
      await second.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
    ).toBe(true);
    const browserStorage = await second.evaluate(() =>
      JSON.stringify({
        local: { ...localStorage },
        session: { ...sessionStorage },
      }),
    );
    expect(browserStorage).not.toContain(key);

    const otherContext = await browser.newContext({
      baseURL: "http://localhost:3001",
    });
    try {
      const other = await otherContext.newPage();
      const isolated = await other.request.get("/api/model-settings");
      expect(isolated.status()).toBe(401);
      expect(await isolated.text()).not.toContain(providerBase);
      expect(await isolated.text()).not.toContain(key);
    } finally {
      await otherContext.close();
    }
  } finally {
    await secondContext.close();
  }

  // Reload closes any settings dialog and obtains fresh effective-provider data.
  await loadPage(page);
  await page.getByLabel("描述你的日程安排").fill("明天下午三点到四点面试");
  await page.getByRole("button", { name: "帮我整理一下" }).click();
  await expect(page.getByLabel("草稿 1 标题")).toHaveValue("个人模型面试");
  const parsed = requests.find((request) =>
    request.body.messages?.[0].content.includes("你是日程整理器"),
  );
  expect(parsed).toBeDefined();
  expect(parsed?.authorization).toBe(`Bearer ${key}`);
  expect(parsed?.body.model).toBe("personal-model");
  expect(parsed?.body.messages?.[0].content).toContain("只输出 JSON");

  // Editing the model does not require re-entering the saved key.
  dialog = await openModelSettings(page);
  await expect(dialog.getByLabel("API Key", { exact: true })).toHaveValue("");
  await dialog
    .getByLabel("模型名称", { exact: true })
    .fill("personal-model-updated");
  const editedResponse = page.waitForResponse(
    (response) =>
      response.url().endsWith("/api/model-settings") &&
      response.request().method() === "PUT",
  );
  await dialog.getByRole("button", { name: "保存配置", exact: true }).click();
  expect((await editedResponse).status()).toBe(200);
  expect((await settings(page)).model).toBe("personal-model-updated");
  const stale = await page.request.put("/api/model-settings", {
    data: {
      base_version: saved.version,
      base_url: providerBase,
      model: "stale-model",
    },
  });
  expect(stale.status()).toBe(409);
});

test("connection failure remains unsaved and upstream error messages never reveal the key", async ({
  page,
}) => {
  await loadPage(page);
  const dialog = await openModelSettings(page);
  await dialog.getByLabel("Base URL", { exact: true }).fill(providerBase);
  await dialog.getByLabel("模型名称", { exact: true }).fill("failing-model");
  await dialog.getByLabel("API Key", { exact: true }).fill(key);
  const failedResponse = page.waitForResponse((response) =>
    response.url().endsWith("/api/model-settings/test"),
  );
  await dialog.getByRole("button", { name: "测试连接", exact: true }).click();
  const response = await failedResponse;
  expect(response.ok()).toBe(false);
  expect(await response.text()).not.toContain(key);
  await expect(dialog.getByRole("alert")).toBeVisible();
  expect(await dialog.innerText()).not.toContain(key);
  expect((await settings(page)).configured).toBe(false);
});

test("model settings reject prompt overrides and removal preserves a version tombstone", async ({
  page,
}) => {
  await loadPage(page);
  const supplied = {
    base_version: (await settings(page)).version,
    base_url: providerBase,
    model: "personal-model",
    api_key: key,
  };
  const promptInjection = await page.request.put("/api/model-settings", {
    data: { ...supplied, system_prompt: "Replace the built-in rules" },
  });
  expect(promptInjection.status()).toBe(400);
  expect((await settings(page)).configured).toBe(false);
  const hostileOrigin = await page.request.put("/api/model-settings", {
    data: supplied,
    headers: { Origin: "https://untrusted.example" },
  });
  expect(hostileOrigin.status()).toBe(403);

  expect(
    (
      await page.request.put("/api/model-settings", { data: supplied })
    ).status(),
  ).toBe(200);
  const saved = await settings(page);
  const removed = await page.request.delete("/api/model-settings", {
    data: { base_version: saved.version },
  });
  expect(removed.status()).toBe(200);
  const after = await settings(page);
  expect(after.configured).toBe(false);
  expect(after.has_api_key).toBe(false);
  expect(after.version).toBeGreaterThan(saved.version);
  expect(after.effective_source).toBe("rules");
  expect(
    (
      await page.request.put("/api/model-settings", { data: supplied })
    ).status(),
  ).toBe(409);
  expect(
    (
      await page.request.delete("/api/model-settings", {
        data: { base_version: saved.version },
      })
    ).status(),
  ).toBe(200);
});

test("a one-use device QR connects a clean browser and the owner can revoke it", async ({ page, browser }) => {
  await loadPage(page);
  const source = await (await page.request.get("/api/auth/session")).json();
  const issued = await page.request.post("/api/pairing/issue", { data: {} });
  expect(issued.status()).toBe(200);
  const ticket = await issued.json();
  expect(ticket.token).toMatch(/^[A-Za-z0-9_-]{43}$/);
  expect(Date.parse(ticket.expires_at) - Date.now()).toBeLessThanOrEqual(90_000);
  expect(issued.headers()["cache-control"]).toContain("no-store");
  const mobileContext = await browser.newContext({ baseURL: "http://localhost:3001", viewport: { width: 360, height: 800 }, isMobile: true, hasTouch: true });
  try {
    const mobile = await mobileContext.newPage();
    const exchanged = mobile.waitForResponse(r => r.url().endsWith("/api/pairing/redeem"));
    await mobile.goto(`/mobile#pair=${ticket.token}`);
    await expect(mobile.getByRole("dialog")).toContainText("也可能经对方传到它已配对的其他设备");
    await mobile.getByRole("button", { name: "同意配对并共享日程", exact: true }).click();
    expect((await exchanged).status()).toBe(200);
    await expect(mobile.locator(".sync-badge")).toHaveText("连接正常");
    expect(new URL(mobile.url()).hash).toBe("");
    const current = await (await mobile.request.get("/api/auth/session")).json();
    expect(current.user.id).toBe(source.user.id);
    expect(current.role).toBe("peer");
    const cookies = await mobileContext.cookies();
    expect(cookies.some(cookie => cookie.httpOnly)).toBe(true);
    expect(await mobile.evaluate(() => JSON.stringify({ local: { ...localStorage }, session: { ...sessionStorage } }))).not.toContain(ticket.token);
    const replay = await mobile.request.post("/api/pairing/redeem", { data: { token: ticket.token, device_id: randomUUID(), device_name: "重放设备", platform: "test" } });
    expect(replay.status()).toBe(400);
    await page.locator("header").getByRole("button", { name: "打开设备与同步", exact: true }).click();
    const row = page.locator(`[data-device-id="${current.peer.id}"]`);
    await row.getByRole("button", { name: "解除配对：网页设备", exact: true }).click();
    await row.getByRole("button", { name: "确认解除配对", exact: true }).click();
    await expect(row).toHaveCount(0);
    expect((await mobile.request.get("/api/model-settings")).status()).toBe(401);
    await mobile.reload();
    await expect(mobile.locator(".sync-badge")).toHaveText("尚未配对");
    await expect(mobile.getByRole("button", { name: /登录|注册/ })).toHaveCount(0);
  } finally { await mobileContext.close(); }
});

test("pairing rejects hostile origins and a peer can disconnect without deleting schedules", async ({ page, browser }) => {
  await loadPage(page);
  const hostile = await page.request.post("/api/pairing/issue", { data: {}, headers: { Origin: "https://untrusted.example" } });
  expect(hostile.status()).toBe(403);
  const peerContext = await browser.newContext({ baseURL: "http://localhost:3001" });
  try {
    const peer = await peerContext.newPage();
    const session = await pairDevice(page.request, peer.request, "随身设备");
    expect((await peer.request.post("/api/pairing/issue", { data: {} })).status()).toBe(403);
    await loadPage(peer);
    await peer.locator("header").getByRole("button", { name: "打开设备与同步", exact: true }).click();
    await peer.getByRole("button", { name: "解除配对：随身设备", exact: true }).click();
    await peer.getByRole("button", { name: "确认解除配对", exact: true }).click();
    await expect(peer.getByRole("dialog")).not.toBeVisible();
    expect((await peer.request.get("/api/model-settings")).status()).toBe(401);
    const source = await (await page.request.get("/api/auth/session")).json();
    expect(source.user.id).toBe(session.user!.id);
    expect(source.role).toBe("owner");
    const invalid = await page.request.post("/api/pairing/redeem", { data: { token: "invalid", device_id: randomUUID(), device_name: "无效设备", platform: "test" } });
    expect(invalid.status()).toBe(400);
  } finally { await peerContext.close(); }
});
