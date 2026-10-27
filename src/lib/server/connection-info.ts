import { networkInterfaces } from "node:os";
import { isIP } from "node:net";
import ipaddr from "ipaddr.js";
import { AppError } from "./errors";

export interface ConnectionInfo {
  current_origin: string;
  candidates: string[];
  hint: string;
}

type InterfaceAddress = { address: string; family: string | number; internal: boolean };
export type ConnectionInfoOptions = {
  appOrigin?: string;
  storageMode?: "local" | "supabase";
  requestHost?: string | null;
  interfaces?: Record<string, readonly InterfaceAddress[] | undefined>;
};

function parseHttpUrl(value: string, configured = false): URL {
  try {
    const url = new URL(value);
    if (!["http:", "https:"].includes(url.protocol) || !url.hostname || url.username || url.password) throw new Error("Invalid service origin");
    if (configured && (url.search || url.hash || (url.pathname && url.pathname !== "/"))) throw new Error("APP_ORIGIN must be an origin");
    return url;
  } catch {
    throw new AppError(configured ? "CONFIG_ERROR" : "INVALID_ORIGIN", configured ? "APP_ORIGIN 需要填写完整的 HTTP(S) 服务来源，不能包含路径、账号或参数。" : "无法识别当前服务地址，请从正常的应用页面打开。", configured ? 503 : 400);
  }
}

function localOnlyHost(hostname: string): boolean {
  const host = hostname.replace(/^\[|\]$/g, "").replace(/\.$/, "").toLowerCase();
  if (host === "localhost" || host.endsWith(".localhost")) return true;
  try { return ["loopback", "unspecified"].includes(ipaddr.process(host).range()); }
  catch { return false; }
}

function privateV4Rank(address: string): number {
  if (isIP(address) !== 4) return -1;
  const [a, b] = address.split(".").map(Number);
  if (a === 192 && b === 168) return 0;
  if (a === 10) return 1;
  if (a === 172 && b >= 16 && b <= 31) return 2;
  return -1;
}

function isVirtualInterface(name: string): boolean {
  return /loopback|docker|veth|^br-|vbox|virtualbox|vmware|vethernet|hyper-v|wsl|tailscale|zerotier|\btun\d*\b|\btap\d*\b|\bwg\d+\b/i.test(name);
}

/** A QR payload contains only an ordinary service root, never authentication. */
export function buildConnectionInfo(requestUrl: string, options: ConnectionInfoOptions = {}): ConnectionInfo {
  const configured = options.appOrigin?.trim();
  if (configured) {
    const url = parseHttpUrl(configured, true);
    if (localOnlyHost(url.hostname)) return {
      current_origin: url.origin,
      candidates: [],
      hint: "APP_ORIGIN 配置为本机地址，手机无法通过它连接。请管理员改为可访问的服务地址，或在局域网演示中留空。",
    };
    return { current_origin: url.origin, candidates: [url.origin], hint: "使用已配置的服务地址。二维码只包含地址，扫码后仍需登录同一账号。" };
  }
  let current = parseHttpUrl(requestUrl);
  if (options.requestHost) {
    // Next.js can retain its listening hostname in request.url. The actual Host
    // supplies the user-visible authority; untrusted forwarded headers are ignored.
    if (/[\s/\\?#@]/.test(options.requestHost)) throw new AppError("INVALID_ORIGIN", "请求的服务地址格式无效。", 400);
    current = parseHttpUrl(`${current.protocol}//${options.requestHost}/`);
  }
  const currentOrigin = current.origin;
  if (!localOnlyHost(current.hostname)) return {
    current_origin: currentOrigin,
    candidates: [currentOrigin],
    hint: privateV4Rank(current.hostname) >= 0 ? "手机与电脑连接同一局域网后扫码，进入后登录同一账号。" : "二维码只包含当前服务地址，扫码后仍需登录同一账号。",
  };
  if ((options.storageMode ?? "local") !== "local") return {
    current_origin: currentOrigin,
    candidates: [],
    hint: "当前地址指向电脑本机。请填写手机可以访问的局域网地址或已部署的 HTTPS 服务地址。",
  };
  const interfaces = options.interfaces ?? networkInterfaces();
  const addresses = new Set<string>();
  for (const [name, entries] of Object.entries(interfaces)) {
    if (isVirtualInterface(name)) continue;
    for (const entry of entries ?? []) {
      if (entry.internal || !["IPv4", 4].includes(entry.family) || privateV4Rank(entry.address) < 0) continue;
      addresses.add(entry.address);
    }
  }
  const sorted = [...addresses].sort((first, second) => {
    const rank = privateV4Rank(first) - privateV4Rank(second);
    if (rank) return rank;
    const firstParts = first.split(".").map(Number);
    const secondParts = second.split(".").map(Number);
    for (let index = 0; index < 4; index++) {
      if (firstParts[index] !== secondParts[index]) return firstParts[index] - secondParts[index];
    }
    return 0;
  });
  const candidates = sorted.slice(0, 8).map(address => `${current.protocol}//${address}${current.port ? `:${current.port}` : ""}`);
  return {
    current_origin: currentOrigin,
    candidates,
    hint: candidates.length ? "已找到电脑的局域网地址。手机与电脑连接同一 Wi-Fi 后扫码；多张网卡时请选择手机可访问的地址。" : "当前地址只指向电脑本机，未找到可用局域网 IPv4。请手动填写电脑的局域网地址或已部署网址。",
  };
}
