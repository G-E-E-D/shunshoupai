import assert from "node:assert/strict";
import { test } from "node:test";
import { buildConnectionInfo } from "../src/lib/server/connection-info";
import { AppError } from "../src/lib/server/errors";

const interfaces = {
  WiFi: [
    { address: "192.168.1.20", family: "IPv4", internal: false },
    { address: "169.254.2.3", family: "IPv4", internal: false },
    { address: "fe80::1", family: "IPv6", internal: false },
  ],
  Ethernet: [
    { address: "10.0.0.5", family: "IPv4", internal: false },
    { address: "192.168.1.20", family: "IPv4", internal: false },
    { address: "172.20.0.4", family: 4, internal: false },
    { address: "8.8.8.8", family: "IPv4", internal: false },
  ],
  Loopback: [{ address: "127.0.0.1", family: "IPv4", internal: true }],
  "vEthernet (WSL)": [{ address: "172.20.50.1", family: "IPv4", internal: false }],
  docker0: [{ address: "172.17.0.1", family: "IPv4", internal: false }],
};

test("desktop loopback suggests phone-reachable LAN roots in order and preserves the service port", () => {
  const info = buildConnectionInfo("http://localhost:3000/api/connection-info", { interfaces });
  assert.equal(info.current_origin, "http://localhost:3000");
  assert.deepEqual(info.candidates, ["http://192.168.1.20:3000", "http://10.0.0.5:3000", "http://172.20.0.4:3000"]);
  assert.ok(!info.candidates.some(value => value.includes("localhost") || value.includes("169.254")));
  assert.match(info.hint, /同一 Wi-Fi/);
});

test("IPv6 loopback and wildcard listening addresses are not encoded as phone targets", () => {
  for (const current of ["https://[::1]:3443/", "https://0.0.0.0:3443/", "https://[::]:3443/", "https://127.3.4.5:3443/"]) {
    const info = buildConnectionInfo(current, { interfaces });
    assert.equal(info.candidates[0], "https://192.168.1.20:3443");
  }
});

test("public host access never exposes the server network interfaces", () => {
  const info = buildConnectionInfo("https://schedule.example/api/connection-info?token=must-not-copy", { interfaces });
  assert.equal(info.current_origin, "https://schedule.example");
  assert.deepEqual(info.candidates, ["https://schedule.example"]);
  assert.equal(JSON.stringify(info).includes("192.168"), false);
  assert.equal(JSON.stringify(info).includes("must-not-copy"), false);
});

test("an explicit APP_ORIGIN takes precedence over internal server URLs and host headers", () => {
  const info = buildConnectionInfo("http://localhost:3000/api/connection-info", {
    interfaces, appOrigin: "https://schedule.example/", requestHost: "127.0.0.1:3000",
  });
  assert.equal(info.current_origin, "https://schedule.example");
  assert.deepEqual(info.candidates, ["https://schedule.example"]);
});

test("fixed loopback APP_ORIGIN does not suggest an address that the origin guard would reject", () => {
  const info = buildConnectionInfo("http://localhost:3000/api/connection-info", { interfaces, appOrigin: "http://localhost:3000" });
  assert.equal(info.candidates.length, 0);
  assert.match(info.hint, /APP_ORIGIN/);
});

test("the normal Host header can supply the externally requested authority", () => {
  const info = buildConnectionInfo("http://localhost:3000/api/connection-info", { interfaces, requestHost: "schedule.example:8080" });
  assert.deepEqual(info.candidates, ["http://schedule.example:8080"]);
  const lan = buildConnectionInfo("http://localhost:3000/api/connection-info", { interfaces, requestHost: "192.168.1.20:3000" });
  assert.deepEqual(lan.candidates, ["http://192.168.1.20:3000"]);
});

test("missing LAN interfaces and cloud-local development provide instructions instead of a broken localhost QR", () => {
  const noLan = buildConnectionInfo("http://localhost:3000/api/connection-info", { interfaces: {} });
  assert.deepEqual(noLan.candidates, []);
  assert.match(noLan.hint, /手动填写/);
  const cloud = buildConnectionInfo("http://localhost:3000/api/connection-info", { interfaces, storageMode: "supabase" });
  assert.deepEqual(cloud.candidates, []);
  assert.match(cloud.hint, /电脑本机/);
});

test("service origins reject credentials and malformed host header input", () => {
  for (const appOrigin of ["javascript:alert(1)", "https://name:secret@schedule.example", "https://schedule.example?token=secret", "https://schedule.example/nested"]) {
    assert.throws(() => buildConnectionInfo("http://localhost:3000/", { appOrigin, interfaces }), error => error instanceof AppError && error.code === "CONFIG_ERROR");
  }
  for (const requestHost of ["schedule.example/path", "name:password@schedule.example", "example.com?token=secret"]) {
    assert.throws(() => buildConnectionInfo("http://localhost:3000/", { requestHost, interfaces }), error => error instanceof AppError && error.code === "INVALID_ORIGIN");
  }
});
