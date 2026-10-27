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
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"], channel: process.platform === "win32" ? "msedge" : undefined } }],
  webServer: {
    command: "npm run dev -- --port 3001",
    url: "http://localhost:3001/api/auth/session",
    timeout: 120_000,
    reuseExistingServer: false,
    env: {
      APP_STORAGE: "local",
      APP_DB_PATH: "test-results/e2e.sqlite",
      NEXT_DIST_DIR: ".next-e2e",
      AI_API_KEY: "",
      COOKIE_SECURE: "false",
      APP_ORIGIN: "",
    },
  },
});
