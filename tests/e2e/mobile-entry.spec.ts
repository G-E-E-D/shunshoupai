import { expect, test } from "@playwright/test";

test("the dedicated Android entry renders an interactive workbench without an app User-Agent", async ({ page }) => {
  await page.goto("/mobile");
  await expect(page.locator(".app-shell")).toBeVisible();
  await expect(page.locator("html")).toHaveAttribute("data-shunshoupai-ready", "true");
  await page.locator("header").getByRole("button", { name: "登录账号", exact: true }).click();
  await expect(page.getByRole("dialog")).toBeVisible();
  await expect(page.getByLabel("邮箱", { exact: true })).toBeVisible();
});

test("a failed session request shows usable recovery UI instead of an empty loading screen", async ({ page }) => {
  await page.route("**/api/auth/session", route => route.fulfill({
    status: 503,
    contentType: "application/json",
    body: JSON.stringify({ error: { code: "AUTH_UNAVAILABLE", message: "登录服务暂时不可用，请稍后重试。" } }),
  }));
  await page.goto("/mobile");
  await expect(page.locator("html")).toHaveAttribute("data-shunshoupai-ready", "true");
  await expect(page.locator(".app-shell")).toBeVisible();
  await expect(page.getByText("登录服务暂时不可用，请稍后重试。", { exact: true })).toBeVisible();
  await expect(page.locator("header").getByRole("button", { name: "登录账号", exact: true })).toBeEnabled();
});
