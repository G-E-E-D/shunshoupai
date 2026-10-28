import { defineConfig, devices } from "@playwright/test";

export default defineConfig({
  testDir: "./tests/e2e",
  timeout: 60_000,
  expect: { timeout: 15_000 },
  fullyParallel: false,
  workers: 1,
  retries: process.env.CI ? 1 : 0,
  reporter: [["list"], ["html", { open: "never" }]],
  use: {
    baseURL: "http://localhost:3001",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  projects: [
    {
      name: "chromium",
      use: {
        ...devices["Desktop Chrome"],
        channel:
          process.env.PLAYWRIGHT_CHANNEL ||
          (process.platform === "win32" ? "msedge" : undefined),
      },
    },
  ],
  webServer: {
    command: "npm start -- --port 3001",
    url: "http://localhost:3001/api/auth/session",
    timeout: 120_000,
    reuseExistingServer: false,
    env: {
      APP_STORAGE: "local",
      APP_MOBILE_UI_TEST: "true",
      APP_DB_PATH: "test-results/e2e-device.sqlite",
      APP_DEVICE_BOOTSTRAP_KEY: "test-only-device-bootstrap-key-2026",
      NEXT_DIST_DIR: ".next-e2e",
      AI_API_KEY: "",
      AI_ALLOWED_PRIVATE_HOSTS: "127.0.0.1,localhost",
      MODEL_SETTINGS_ENCRYPTION_KEY: Buffer.alloc(32, 7).toString("base64"),
      COOKIE_SECURE: "false",
      APP_ORIGIN: "",
    },
  },
});
