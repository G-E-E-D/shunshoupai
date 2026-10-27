import assert from "node:assert/strict";
import dns from "node:dns/promises";
import { createServer, type IncomingMessage, type ServerResponse } from "node:http";
import { test } from "node:test";
import { checkModelConnection, isPublicModelAddress, ModelTransportError, normalizeModelBaseUrl, requestModel } from "../src/lib/server/model-transport";
import type { ModelConfig } from "../src/lib/model-config";

const secret = "test-only-model-secret-not-a-real-key";
const messages = [{ role: "system" as const, content: "Return JSON." }, { role: "user" as const, content: "Test." }];
const envelope = (content: unknown) => JSON.stringify({ choices: [{ message: { content } }] });

async function withProvider(run: (baseUrl: string) => Promise<void>, handler: (request: IncomingMessage, response: ServerResponse) => void | Promise<void>) {
  const server = createServer(handler);
  await new Promise<void>(resolve => server.listen(0, "127.0.0.1", resolve));
  const port = (server.address() as { port: number }).port;
  const oldAllowed = process.env.AI_ALLOWED_PRIVATE_HOSTS;
  process.env.AI_ALLOWED_PRIVATE_HOSTS = "127.0.0.1,localhost,model.local.invalid";
  try { await run(`http://127.0.0.1:${port}`); }
  finally {
    server.closeAllConnections();
    await new Promise<void>(resolve => server.close(() => resolve()));
    if (oldAllowed === undefined) delete process.env.AI_ALLOWED_PRIVATE_HOSTS; else process.env.AI_ALLOWED_PRIVATE_HOSTS = oldAllowed;
  }
}

function config(baseUrl: string): ModelConfig { return { baseUrl, apiKey: secret, model: "fixture-model" }; }

test("model endpoints reject metadata, private, reserved and disguised IP addresses by default", async () => {
  const oldAllowed = process.env.AI_ALLOWED_PRIVATE_HOSTS;
  delete process.env.AI_ALLOWED_PRIVATE_HOSTS;
  try {
    for (const host of ["127.0.0.1", "0.0.0.0", "10.1.2.3", "172.20.1.1", "192.168.0.1", "169.254.169.254", "100.100.100.200", "198.18.0.1", "192.0.2.1", "224.0.0.1", "[::1]", "[fe80::1]", "[fc00::1]", "[::ffff:127.0.0.1]", "[2001:db8::1]", "[2002:7f00:1::]", "[4000::1]", "[8000::1]", "2130706433", "0x7f000001", "127.1"]) {
      await assert.rejects(normalizeModelBaseUrl(`https://${host}`), (error: unknown) => error instanceof ModelTransportError && error.code === "MODEL_ADDRESS_BLOCKED", host);
    }
    assert.equal(isPublicModelAddress("1.1.1.1"), true);
    assert.equal(isPublicModelAddress("2606:4700:4700::1111"), true);
  } finally { if (oldAllowed !== undefined) process.env.AI_ALLOWED_PRIVATE_HOSTS = oldAllowed; }
});

test("model URLs disallow credentials, queries, fragments, full endpoints and unsafe schemes", async () => {
  for (const url of ["file:///etc/passwd", "javascript:alert(1)", "https://key@example.com", "https://example.com?api_key=secret", "https://example.com#secret", "https://example.com/v1/chat/completions", "https://example.com\\@127.0.0.1", "https://example.com/\r\nheader", "http://1.1.1.1"]) {
    await assert.rejects(normalizeModelBaseUrl(url), ModelTransportError, url);
  }
});

test("all DNS answers are checked, including a private second answer", async context => {
  const oldAllowed = process.env.AI_ALLOWED_PRIVATE_HOSTS;
  delete process.env.AI_ALLOWED_PRIVATE_HOSTS;
  const lookup = context.mock.method(dns, "lookup", (async () => [
    { address: "1.1.1.1", family: 4 }, { address: "127.0.0.1", family: 4 },
  ]) as unknown as typeof dns.lookup);
  try {
    await assert.rejects(normalizeModelBaseUrl("https://model.example.invalid/v1"), (error: unknown) => error instanceof ModelTransportError && error.code === "MODEL_ADDRESS_BLOCKED");
    lookup.mock.mockImplementation((async () => [{ address: "1.1.1.1", family: 4 }]) as unknown as typeof dns.lookup);
    assert.equal(await normalizeModelBaseUrl("https://model.example.invalid/v1/"), "https://model.example.invalid/v1");
  } finally { if (oldAllowed !== undefined) process.env.AI_ALLOWED_PRIVATE_HOSTS = oldAllowed; }
});

test("actual socket is pinned to the single checked DNS result instead of resolving a second time", async context => {
  let dnsCalls = 0;
  let hits = 0;
  const lookup = context.mock.method(dns, "lookup", (async () => [{ address: ++dnsCalls === 1 ? "127.0.0.1" : "192.0.2.1", family: 4 }]) as unknown as typeof dns.lookup);
  await withProvider(async baseUrl => {
    const port = new URL(baseUrl).port;
    const response = await requestModel(config(`http://model.local.invalid:${port}/v1`), messages);
    assert.equal(response, '{"ok":true}');
    assert.equal(dnsCalls, 1);
    assert.equal(hits, 1);
  }, (request, response) => {
    hits++;
    assert.equal(request.url, "/v1/chat/completions");
    assert.equal(request.headers.authorization, `Bearer ${secret}`);
    assert.match(request.headers.host || "", /^model\.local\.invalid:/);
    response.end(envelope('{"ok":true}'));
  });
  lookup.mock.restore();
});

test("private endpoints require an exact administrator allowlist, never a wildcard", async () => {
  const oldAllowed = process.env.AI_ALLOWED_PRIVATE_HOSTS;
  try {
    process.env.AI_ALLOWED_PRIVATE_HOSTS = "*.local.invalid,127.0.0.1:9999";
    await assert.rejects(normalizeModelBaseUrl("http://model.local.invalid"), /公网 HTTPS/);
    await assert.rejects(normalizeModelBaseUrl("http://127.0.0.1:9999"), /公网 HTTPS/);
    process.env.AI_ALLOWED_PRIVATE_HOSTS = "127.0.0.1";
    assert.equal(await normalizeModelBaseUrl("http://127.0.0.1:9999/v1"), "http://127.0.0.1:9999/v1");
  } finally { if (oldAllowed === undefined) delete process.env.AI_ALLOWED_PRIVATE_HOSTS; else process.env.AI_ALLOWED_PRIVATE_HOSTS = oldAllowed; }
});

test("redirects never forward the API key to another server", async () => {
  let targetHits = 0;
  const target = createServer((_request, response) => { targetHits++; response.end(envelope('{"ok":true}')); });
  await new Promise<void>(resolve => target.listen(0, "127.0.0.1", resolve));
  try {
    await withProvider(async baseUrl => {
      await assert.rejects(requestModel(config(baseUrl), messages), (error: unknown) => error instanceof ModelTransportError && error.code === "MODEL_REDIRECT_BLOCKED");
      assert.equal(targetHits, 0);
    }, (_request, response) => {
      response.writeHead(302, { Location: `http://127.0.0.1:${(target.address() as { port: number }).port}/stolen` });
      response.end();
    });
  } finally { target.closeAllConnections(); await new Promise<void>(resolve => target.close(() => resolve())); }
});

test("upstream failures and sensitive successful content never echo API keys", async () => {
  let status = 401;
  await withProvider(async baseUrl => {
    await assert.rejects(requestModel(config(baseUrl), messages), (error: unknown) => error instanceof ModelTransportError && /401/.test(error.message) && !error.message.includes(secret));
    status = 200;
    await assert.rejects(requestModel(config(baseUrl), messages), (error: unknown) => error instanceof ModelTransportError && error.code === "MODEL_SENSITIVE_RESPONSE" && !error.message.includes(secret));
  }, (_request, response) => {
    response.writeHead(status);
    response.end(status === 401 ? `debug secret: ${secret}` : envelope(JSON.stringify({ api_key: secret })));
  });
});

test("streaming responses are bounded by bytes and slow requests time out", async () => {
  let mode = "large";
  await withProvider(async baseUrl => {
    await assert.rejects(requestModel(config(baseUrl), messages, { maxResponseBytes: 200 }), (error: unknown) => error instanceof ModelTransportError && error.code === "MODEL_RESPONSE_TOO_LARGE");
    mode = "slow";
    await assert.rejects(requestModel(config(baseUrl), messages, { timeoutMs: 25 }), (error: unknown) => error instanceof ModelTransportError && error.code === "MODEL_TIMEOUT");
  }, (_request, response) => {
    if (mode === "large") { response.writeHead(200); response.end(envelope("汉".repeat(200))); }
    // A slow fixture deliberately sends no headers or body; cleanup closes it.
  });
});

test("DNS timeout cannot send a delayed credential-bearing request after the caller has failed", async context => {
  let hits = 0;
  context.mock.method(dns, "lookup", (async () => {
    await new Promise(resolve => setTimeout(resolve, 70));
    return [{ address: "127.0.0.1", family: 4 }];
  }) as unknown as typeof dns.lookup);
  await withProvider(async baseUrl => {
    const url = `http://model.local.invalid:${new URL(baseUrl).port}`;
    await assert.rejects(requestModel(config(url), messages, { timeoutMs: 15 }), (error: unknown) => error instanceof ModelTransportError && error.code === "MODEL_TIMEOUT");
    await new Promise(resolve => setTimeout(resolve, 100));
    assert.equal(hits, 0);
  }, (_request, response) => { hits++; response.end(envelope('{"ok":true}')); });
});

test("connection test uses a fixed JSON probe and rejects incompatible output", async () => {
  let output = '{"ok":true}';
  let requestBody: { messages: { role: string; content: string }[]; response_format: { type: string } } | undefined;
  await withProvider(async baseUrl => {
    assert.deepEqual(await checkModelConnection(config(baseUrl)), { message: "连接成功，模型已通过 JSON 输出测试。" });
    assert.equal(requestBody?.response_format.type, "json_object");
    assert.equal(requestBody?.messages[0].role, "system");
    assert.ok(requestBody?.messages[0].content.includes("顺手排连接测试"));
    for (const invalid of ["not JSON", "{}", '{"ok":false}', "[]"]) {
      output = invalid;
      await assert.rejects(checkModelConnection(config(baseUrl)), (error: unknown) => error instanceof ModelTransportError && error.code === "MODEL_PROBE_FAILED");
    }
  }, async (request, response) => {
    const chunks: Buffer[] = [];
    for await (const chunk of request) chunks.push(chunk);
    requestBody = JSON.parse(Buffer.concat(chunks).toString());
    response.end(envelope(output));
  });
});
