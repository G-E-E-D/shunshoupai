import { NextResponse } from "next/server";
import { ZodError } from "zod";
import { AppError } from "./errors";
import { ReplicaError } from "../replica";

const privateHeaders = { "Cache-Control": "private, no-store, max-age=0", "Vary": "Cookie, Authorization" };

export function json(data: unknown, status = 200) {
  return NextResponse.json(data, { status, headers: privateHeaders });
}

export async function handle(operation: () => unknown | Promise<unknown>): Promise<NextResponse> {
  try {
    return json(await operation());
  } catch (error) {
    if (error instanceof ReplicaError) return json({ error: { code: error.code, message: error.message } }, 409);
    if (error instanceof AppError) return json({ error: { code: error.code, message: error.message, details: error.details } }, error.status);
    if (error instanceof ZodError) {
      return json({ error: { code: "VALIDATION_ERROR", message: "请检查输入的字段与时间。", details: error.issues.map(({ path, message }) => ({ path, message })) } }, 400);
    }
    // Do not log request text, session cookies, secrets, or database error details.
    console.error("Request failed", error instanceof Error ? error.name : "UnknownError");
    return json({ error: { code: "INTERNAL_ERROR", message: "服务暂时无法完成操作，请稍后重试。" } }, 500);
  }
}

export function sameOrigin(request: Request) {
  const origin = request.headers.get("origin");
  if (!origin) return;
  const allowed = process.env.APP_ORIGIN;
  let valid = false;
  try {
    const parsed = new URL(origin);
    valid = ["https:", "http:"].includes(parsed.protocol) && (allowed ? parsed.origin === new URL(allowed).origin : parsed.host === request.headers.get("host"));
  } catch { /* Reject malformed Origin. */ }
  if (!valid) throw new AppError("INVALID_ORIGIN", "请求来源不受信任，请从应用页面重试。", 403);
}

export async function readJson(request: Request, maxBytes = 32_768): Promise<unknown> {
  sameOrigin(request);
  if (!request.headers.get("content-type")?.toLowerCase().startsWith("application/json")) {
    throw new AppError("INVALID_CONTENT_TYPE", "请求必须使用 JSON 格式。", 415);
  }
  const reader = request.body?.getReader();
  if (!reader) throw new AppError("INVALID_JSON", "请求内容不能为空。", 400);
  const chunks: Uint8Array[] = [];
  let size = 0;
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    size += value.byteLength;
    if (size > maxBytes) {
      await reader.cancel();
      throw new AppError("BODY_TOO_LARGE", "请求内容过长。", 413);
    }
    chunks.push(value);
  }
  try { return JSON.parse(Buffer.concat(chunks).toString("utf8")); }
  catch { throw new AppError("INVALID_JSON", "请求不是有效的 JSON。", 400); }
}

type Bucket = { count: number; expires: number };
const buckets = new Map<string, Bucket>();
export function rateLimit(key: string, limit: number, windowMs: number) {
  const now = Date.now();
  if (buckets.size > 10_000) {
    for (const [id, bucket] of buckets) if (bucket.expires <= now) buckets.delete(id);
    if (buckets.size > 10_000) throw new AppError("RATE_LIMITED", "服务正忙，请稍后再试。", 429);
  }
  const bucket = buckets.get(key);
  if (!bucket || bucket.expires <= now) buckets.set(key, { count: 1, expires: now + windowMs });
  else {
    if (bucket.count >= limit) throw new AppError("RATE_LIMITED", "操作过于频繁，请稍后再试。", 429);
    bucket.count++;
  }
}

export function validateWindow(request: Request): { from: string; to: string } {
  const url = new URL(request.url);
  const fromDate = new Date(url.searchParams.get("from") ?? "");
  const toDate = new Date(url.searchParams.get("to") ?? "");
  const delta = toDate.getTime() - fromDate.getTime();
  if (!Number.isFinite(delta) || delta <= 0 || delta > 93 * 24 * 60 * 60 * 1000) {
    throw new AppError("INVALID_WINDOW", "请选择不超过 93 天的有效时间范围。", 400);
  }
  return { from: fromDate.toISOString(), to: toDate.toISOString() };
}
