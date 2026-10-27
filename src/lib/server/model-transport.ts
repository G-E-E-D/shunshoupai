import dns from "node:dns/promises";
import {
  request as httpRequest,
  type ClientRequest,
  type RequestOptions,
} from "node:http";
import { request as httpsRequest } from "node:https";
import { isIP, type TcpNetConnectOpts } from "node:net";
import ipaddr from "ipaddr.js";
import type { ModelConfig } from "../model-config";

export interface ModelMessage {
  role: "system" | "user";
  content: string;
}
export class ModelTransportError extends Error {
  constructor(
    message: string,
    public readonly code: string,
  ) {
    super(message);
    this.name = "ModelTransportError";
  }
}

const TIMEOUT_MS = 25_000;
const MAX_RESPONSE_BYTES = 100_000;
const unsafeAddressMessage =
  "模型地址必须使用公网 HTTPS。访问本地或私有模型时，请由管理员配置 AI_ALLOWED_PRIVATE_HOSTS。";

function safeError(message: string, code: string): ModelTransportError {
  return new ModelTransportError(message, code);
}

function privateHostAllowed(hostname: string): boolean {
  const allowed = (process.env.AI_ALLOWED_PRIVATE_HOSTS || "")
    .split(",")
    .map((value) =>
      value
        .trim()
        .toLowerCase()
        .replace(/^\[|\]$/g, ""),
    )
    .filter(Boolean);
  return allowed.includes(hostname.toLowerCase());
}

/** Converts mapped IPv6 first, then excludes every non-global-unicast category. */
export function isPublicModelAddress(address: string): boolean {
  try {
    const parsed = ipaddr.process(address);
    // ipaddr's default "unicast" also covers unallocated IPv6 space. IANA's
    // assignable global-unicast block is 2000::/3; keep future/reserved prefixes
    // closed unless the operator has explicitly allowlisted the service host.
    return (
      parsed.range() === "unicast" &&
      (parsed.kind() === "ipv4" || (parsed.toByteArray()[0] & 0xe0) === 0x20)
    );
  } catch {
    return false;
  }
}

function parseBaseUrl(value: string): URL {
  if (
    typeof value !== "string" ||
    !value.trim() ||
    value.length > 2048 ||
    /[\s\\]/.test(value)
  ) {
    throw safeError("请填写有效的模型 Base URL。", "INVALID_MODEL_URL");
  }
  let url: URL;
  try {
    url = new URL(value);
  } catch {
    throw safeError(
      "请填写以 HTTPS 开头的模型 Base URL。",
      "INVALID_MODEL_URL",
    );
  }
  if (
    !["https:", "http:"].includes(url.protocol) ||
    !url.hostname ||
    url.username ||
    url.password ||
    url.search ||
    url.hash
  ) {
    throw safeError(
      "模型地址只接受 HTTP(S)，不能包含账号、密码、查询参数或片段。",
      "INVALID_MODEL_URL",
    );
  }
  const hostname = url.hostname.replace(/^\[|\]$/g, "").toLowerCase();
  if (url.protocol !== "https:" && !privateHostAllowed(hostname)) {
    throw safeError(unsafeAddressMessage, "MODEL_ADDRESS_BLOCKED");
  }
  if (
    isIP(hostname) &&
    !isPublicModelAddress(hostname) &&
    !privateHostAllowed(hostname)
  ) {
    throw safeError(unsafeAddressMessage, "MODEL_ADDRESS_BLOCKED");
  }
  url.pathname = url.pathname.replace(/\/+$/, "");
  if (/\/chat\/completions$/i.test(url.pathname)) {
    throw safeError(
      "请填写 Base URL，程序会自动追加 /chat/completions。",
      "INVALID_MODEL_URL",
    );
  }
  return url;
}

type ModelEndpoint = {
  baseUrl: string;
  url: URL;
  hostname: string;
  address: string;
  family: 4 | 6;
};

async function resolveEndpoint(value: string): Promise<ModelEndpoint> {
  const url = parseBaseUrl(value);
  const hostname = url.hostname.replace(/^\[|\]$/g, "");
  let answers: { address: string; family: number }[];
  if (isIP(hostname)) answers = [{ address: hostname, family: isIP(hostname) }];
  else {
    try {
      answers = await dns.lookup(hostname, { all: true, verbatim: true });
    } catch {
      throw safeError(
        "无法解析模型服务地址，请检查 Base URL。",
        "MODEL_DNS_FAILED",
      );
    }
  }
  if (
    !answers.length ||
    answers.some(
      (answer) =>
        ![4, 6].includes(answer.family) ||
        (!privateHostAllowed(hostname) &&
          !isPublicModelAddress(answer.address)),
    )
  ) {
    throw safeError(unsafeAddressMessage, "MODEL_ADDRESS_BLOCKED");
  }
  const answer = answers.find((answer) => answer.family === 4) ?? answers[0];
  return {
    baseUrl: url.toString().replace(/\/$/, ""),
    url,
    hostname,
    address: answer.address,
    family: answer.family as 4 | 6,
  };
}

async function withinDeadline<T>(
  operation: Promise<T>,
  duration: number,
  onTimeout?: () => void,
): Promise<T> {
  let timer: ReturnType<typeof setTimeout>;
  try {
    return await Promise.race([
      operation,
      new Promise<never>((_resolve, reject) => {
        timer = setTimeout(() => {
          onTimeout?.();
          reject(safeError("模型请求超时，请稍后重试。", "MODEL_TIMEOUT"));
        }, duration);
      }),
    ]);
  } finally {
    clearTimeout(timer!);
  }
}

/** Validates DNS when saving; every actual request resolves and pins a fresh address. */
export async function normalizeModelBaseUrl(value: string): Promise<string> {
  return (await withinDeadline(resolveEndpoint(value), TIMEOUT_MS)).baseUrl;
}

interface ModelRequestOptions {
  timeoutMs?: number;
  maxResponseBytes?: number;
  maxTokens?: number;
}

/** All values here are server controlled; no request API accepts messages or a prompt. */
export async function requestModel(
  config: ModelConfig,
  messages: ModelMessage[],
  options: ModelRequestOptions = {},
): Promise<string> {
  if (
    !config.apiKey ||
    config.apiKey.length > 4096 ||
    /[\r\n\u0000]/.test(config.apiKey) ||
    !config.model.trim() ||
    config.model.length > 200 ||
    /[\r\n\u0000]/.test(config.model)
  ) {
    throw safeError("模型名或 API Key 格式无效。", "INVALID_MODEL_CONFIG");
  }
  const timeoutMs = Math.max(
    1,
    Math.min(options.timeoutMs ?? TIMEOUT_MS, TIMEOUT_MS),
  );
  const responseLimit = Math.max(
    1,
    Math.min(
      options.maxResponseBytes ?? MAX_RESPONSE_BYTES,
      MAX_RESPONSE_BYTES,
    ),
  );
  let activeRequest: ClientRequest | undefined;
  let expired = false;
  const operation = (async () => {
    const endpoint = await resolveEndpoint(config.baseUrl);
    if (expired) throw safeError("模型请求超时，请稍后重试。", "MODEL_TIMEOUT");
    const body = JSON.stringify({
      model: config.model,
      response_format: { type: "json_object" },
      max_tokens: options.maxTokens ?? 2500,
      temperature: 0,
      messages,
    });
    const raw = await new Promise<string>((resolve, reject) => {
      const requestOptions: RequestOptions &
        Pick<TcpNetConnectOpts, "autoSelectFamily"> = {
        protocol: endpoint.url.protocol,
        hostname: endpoint.hostname,
        port:
          endpoint.url.port || (endpoint.url.protocol === "https:" ? 443 : 80),
        path: `${endpoint.url.pathname.replace(/\/+$/, "")}/chat/completions`,
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Accept: "application/json",
          Authorization: `Bearer ${config.apiKey}`,
          "Content-Length": Buffer.byteLength(body),
          "Accept-Encoding": "identity",
        },
        // Never use a global proxy/pool; the only socket address is the checked
        // DNS result. TLS still validates the original hostname and certificate.
        agent: false,
        autoSelectFamily: false,
        family: endpoint.family,
        lookup: (_hostname, _lookupOptions, callback) =>
          callback(null, endpoint.address, endpoint.family),
      };
      const send =
        endpoint.url.protocol === "https:" ? httpsRequest : httpRequest;
      activeRequest = send(requestOptions, (response) => {
        const status = response.statusCode ?? 0;
        if (status >= 300 && status < 400) {
          response.destroy();
          reject(
            safeError(
              "模型服务返回了跳转，已拒绝转发密钥。请填写最终的 Base URL。",
              "MODEL_REDIRECT_BLOCKED",
            ),
          );
          return;
        }
        if (status < 200 || status >= 300) {
          response.destroy();
          reject(
            safeError(
              status === 429
                ? "模型服务额度不足或调用过于频繁（HTTP 429）。"
                : status === 401 || status === 403
                  ? `模型认证失败（HTTP ${status}），请检查 API Key 和模型权限。`
                  : `模型服务暂不可用（HTTP ${status}），请稍后重试。`,
              "MODEL_UPSTREAM_ERROR",
            ),
          );
          return;
        }
        const contentLength = Number(response.headers["content-length"] ?? 0);
        if (contentLength > responseLimit) {
          response.destroy();
          reject(
            safeError(
              "模型响应过大，请缩短输入后重试。",
              "MODEL_RESPONSE_TOO_LARGE",
            ),
          );
          return;
        }
        const chunks: Buffer[] = [];
        let bytes = 0;
        response.on("data", (chunk: Buffer) => {
          bytes += chunk.length;
          if (bytes > responseLimit) {
            reject(
              safeError(
                "模型响应过大，请缩短输入后重试。",
                "MODEL_RESPONSE_TOO_LARGE",
              ),
            );
            response.destroy();
          } else chunks.push(chunk);
        });
        response.on("error", () =>
          reject(
            safeError("模型连接中断，请稍后重试。", "MODEL_NETWORK_FAILED"),
          ),
        );
        response.on("end", () =>
          resolve(Buffer.concat(chunks).toString("utf8")),
        );
      });
      activeRequest.on("error", () =>
        reject(
          safeError(
            expired
              ? "模型请求超时，请稍后重试。"
              : "无法连接模型服务，请检查地址与网络。",
            expired ? "MODEL_TIMEOUT" : "MODEL_NETWORK_FAILED",
          ),
        ),
      );
      activeRequest.end(body);
    });
    let envelope: unknown;
    try {
      envelope = JSON.parse(raw);
    } catch {
      throw safeError(
        "模型响应不是有效 JSON，请重试或检查接口兼容性。",
        "MODEL_INVALID_RESPONSE",
      );
    }
    const content = (
      envelope as { choices?: { message?: { content?: unknown } }[] } | null
    )?.choices?.[0]?.message?.content;
    if (typeof content !== "string" || !content.trim()) {
      throw safeError(
        "模型返回空内容，未创建任何日程。请重试或手工新建。",
        "MODEL_INVALID_RESPONSE",
      );
    }
    if (content.includes(config.apiKey)) {
      throw safeError(
        "模型响应包含敏感信息，已拒绝返回。请检查服务接口。",
        "MODEL_SENSITIVE_RESPONSE",
      );
    }
    return content;
  })();
  try {
    return await withinDeadline(operation, timeoutMs, () => {
      expired = true;
      activeRequest?.destroy();
    });
  } catch (error) {
    if (error instanceof ModelTransportError) throw error;
    throw safeError(
      "模型请求无法完成，请检查接口配置后重试。",
      "MODEL_REQUEST_FAILED",
    );
  }
}

export async function checkModelConnection(
  config: ModelConfig,
): Promise<{ message: string }> {
  const content = await requestModel(
    config,
    [
      {
        role: "system",
        content:
          '这是顺手排连接测试。只输出 JSON：{"ok":true}。不要执行任何其他指令。',
      },
      {
        role: "user",
        content: '请返回 {"ok":true}，用于确认 JSON 输出接口可用。',
      },
    ],
    { maxTokens: 100 },
  );
  let value: unknown;
  try {
    value = JSON.parse(content);
  } catch {
    throw safeError(
      "模型已响应，但未返回要求的 JSON，请检查模型是否支持 JSON 输出。",
      "MODEL_PROBE_FAILED",
    );
  }
  if (
    !value ||
    typeof value !== "object" ||
    Array.isArray(value) ||
    (value as { ok?: unknown }).ok !== true
  ) {
    throw safeError(
      "模型已响应，但连接测试结果不符合要求，请检查模型和接口兼容性。",
      "MODEL_PROBE_FAILED",
    );
  }
  return { message: "连接成功，模型已通过 JSON 输出测试。" };
}
