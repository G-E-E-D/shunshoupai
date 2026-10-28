import { expect, test } from "@playwright/test";

test("the dedicated Android entry renders an interactive workbench without an app User-Agent", async ({ page }) => {
  await page.goto("/mobile");
  await expect(page.locator(".app-shell")).toBeVisible();
  await expect(page.locator("html")).toHaveAttribute("data-shunshoupai-ready", "true");
  await page.locator("header").getByRole("button", { name: "配对说明", exact: true }).click();
  await expect(page.getByRole("dialog")).toBeVisible();
  await expect(page.getByText("扫码或粘贴配对链接", { exact: true })).toBeVisible();
  await expect(page.getByRole("textbox", { name: "邮箱", exact: true })).toHaveCount(0);
  await expect(page.getByRole("button", { name: /登录|注册/ })).toHaveCount(0);
});

test("a pairing link requires propagation consent and declining never redeems its code", async ({ page }) => {
  let exchanges = 0;
  await page.route("**/api/pairing/redeem", route => { exchanges++; return route.abort(); });
  const token = "a".repeat(43);
  await page.goto(`/mobile#pair=${token}`);
  await expect(page.getByRole("dialog")).toContainText("本机全部日程及未来修改");
  await expect(page.getByRole("dialog")).toContainText("无法撤回已传播的副本");
  expect(new URL(page.url()).hash).toBe("");
  expect(exchanges).toBe(0);
  await page.getByRole("button", { name: "暂不配对", exact: true }).click();
  await expect(page.getByRole("dialog")).not.toBeVisible();
  expect(exchanges).toBe(0);
  expect(await page.evaluate(() => JSON.stringify({ local: { ...localStorage }, session: { ...sessionStorage } }))).not.toContain(token);
});

test("a failed session request shows usable recovery UI instead of an empty loading screen", async ({ page }) => {
  await page.route("**/api/auth/session", route => route.fulfill({
    status: 503,
    contentType: "application/json",
    body: JSON.stringify({ error: { code: "AUTH_UNAVAILABLE", message: "设备暂时不可用，恢复连接后自动重试。" } }),
  }));
  await page.goto("/mobile");
  await expect(page.locator("html")).toHaveAttribute("data-shunshoupai-ready", "true");
  await expect(page.locator(".app-shell")).toBeVisible();
  await expect(page.getByText("设备暂时不可用，恢复连接后自动重试。", { exact: true })).toBeVisible();
  await expect(page.locator("header").getByRole("button", { name: "配对说明", exact: true })).toBeEnabled();
});
