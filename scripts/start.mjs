import { spawn } from "node:child_process";
import { resolve } from "node:path";

const file = resolve(`backend/bin/shunshoupai-server${process.platform === "win32" ? ".exe" : ""}`);
const child = spawn(file, ["--web", resolve("out"), ...process.argv.slice(2)], { stdio: "inherit", windowsHide: true });
child.on("error", () => { console.error("Go 服务无法启动，请先运行 npm run build。"); process.exitCode = 1; });
child.on("exit", code => { process.exitCode = code ?? 1; });
for (const signal of ["SIGINT", "SIGTERM"]) process.on(signal, () => child.kill(signal));
