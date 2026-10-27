import type { ApiErrorBody } from "./types";
import type { ScheduleEvent } from "./types";

export class ApiError extends Error {
  constructor(
    public code: string,
    message: string,
    public status: number,
  ) {
    super(message);
    this.name = "ApiError";
  }
}

export async function api<T>(url: string, init?: RequestInit): Promise<T> {
  const controller = new AbortController();
  const timer = setTimeout(
    () => controller.abort(),
    url.includes("/ai/") ? 35000 : 20000,
  );
  try {
    const response = await fetch(url, {
      ...init,
      headers: { "Content-Type": "application/json", ...init?.headers },
      cache: "no-store",
      signal: controller.signal,
    });
    const data = await response.json();
    if (!response.ok) {
      const error = (data as ApiErrorBody).error;
      throw new ApiError(
        error?.code || "REQUEST_FAILED",
        error?.message || "请求失败，请稍后重试。",
        response.status,
      );
    }
    return data as T;
  } finally {
    clearTimeout(timer);
  }
}

// randomUUID is unavailable on plain HTTP LAN origins; getRandomValues is supported there.
export function requestId(): string {
  if (typeof globalThis.crypto?.randomUUID === "function")
    return crypto.randomUUID();
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = Array.from(bytes, (byte) =>
    byte.toString(16).padStart(2, "0"),
  ).join("");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

export function shanghaiDay(date = new Date()): string {
  const parts = new Intl.DateTimeFormat("en-CA", {
    timeZone: "Asia/Shanghai",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).formatToParts(date);
  return `${parts.find((p) => p.type === "year")?.value}-${parts.find((p) => p.type === "month")?.value}-${parts.find((p) => p.type === "day")?.value}`;
}
export function shiftDay(day: string, offset: number): string {
  const d = new Date(`${day}T12:00:00+08:00`);
  d.setUTCDate(d.getUTCDate() + offset);
  return shanghaiDay(d);
}
export function touchesWindow(
  event: ScheduleEvent,
  fromDay: string,
  toDay = shiftDay(fromDay, 1),
): boolean {
  return (
    Date.parse(event.start_at) < Date.parse(`${toDay}T00:00:00+08:00`) &&
    Date.parse(event.end_at) > Date.parse(`${fromDay}T00:00:00+08:00`)
  );
}
export function localInput(iso: string): string {
  return `${shanghaiDay(new Date(iso))}T${clockTime(iso)}`;
}
export function clockTime(iso: string): string {
  return new Intl.DateTimeFormat("zh-CN", {
    timeZone: "Asia/Shanghai",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  }).format(new Date(iso));
}
export function dayLabel(day: string, long = false): string {
  return new Intl.DateTimeFormat("zh-CN", {
    timeZone: "Asia/Shanghai",
    month: "long",
    day: "numeric",
    ...(long ? { weekday: "long" } : {}),
  }).format(new Date(`${day}T12:00:00+08:00`));
}
export function errorMessage(error: unknown): string {
  if (error instanceof ApiError) return error.message;
  if (error instanceof Error && error.name === "AbortError")
    return "请求超时，请检查网络后重试。";
  return "连接失败，请检查网络。你的输入仍然保留。";
}
