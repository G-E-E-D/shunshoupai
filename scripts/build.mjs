import { spawnSync } from "node:child_process";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";

const root = process.cwd();
const version = JSON.parse(readFileSync("package.json", "utf8")).version;
function run(command, args, cwd = root) {
  const result = spawnSync(command, args, { cwd, stdio: "inherit", windowsHide: true });
  if (result.error) throw result.error;
  if (result.status !== 0) process.exit(result.status ?? 1);
}
run(process.execPath, ["node_modules/next/dist/bin/next", "build"]);
mkdirSync("backend/bin", { recursive: true });
run(process.env.GO_BINARY || "go", ["build", "-trimpath", "-ldflags", `-s -w -X main.version=${version}`, "-o", `bin/shunshoupai-server${process.platform === "win32" ? ".exe" : ""}`, "./cmd/shunshoupai"], resolve(root, "backend"));
writeFileSync("backend/bin/package.json", JSON.stringify({ name: "shunshoupai-go", version }) + "\n");
