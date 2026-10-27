import { defineConfig, globalIgnores } from "eslint/config";
import nextVitals from "eslint-config-next/core-web-vitals";
import nextTs from "eslint-config-next/typescript";

export default defineConfig([
  ...nextVitals,
  ...nextTs,
  globalIgnores([
    ".next/**",
    ".next-e2e/**",
    "node_modules/**",
    "data/**",
    "artifacts/**",
    "desktop/**",
    "android/**/build/**",
    "android/.gradle/**",
    "playwright-report/**",
    "test-results/**",
    "**/*_files/**",
    "next-env.d.ts",
  ]),
]);
