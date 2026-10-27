import { createServerClient } from "@supabase/ssr";
import type { SupabaseClient } from "@supabase/supabase-js";
import { cookies } from "next/headers";
import type { SessionInfo } from "../types";
import { AppError } from "./errors";
import { localStore } from "./local-store";

export const SESSION_COOKIE = "shunshoupai_session";
type User = NonNullable<SessionInfo["user"]>;
export type AuthContext = { user: User; mode: "local" | "supabase"; supabase?: SupabaseClient };

export function storageMode(): "local" | "supabase" {
  const mode = process.env.APP_STORAGE || "local";
  if (mode !== "local" && mode !== "supabase") throw new AppError("CONFIG_ERROR", "APP_STORAGE 必须为 local 或 supabase。", 503);
  return mode;
}

export function aiProvider(): "model" | "rules" {
  return process.env.AI_API_KEY ? "model" : "rules";
}

export async function supabaseClient() {
  const url = process.env.NEXT_PUBLIC_SUPABASE_URL || process.env.SUPABASE_URL;
  const key = process.env.NEXT_PUBLIC_SUPABASE_ANON_KEY || process.env.SUPABASE_ANON_KEY;
  if (!url || !key) throw new AppError("CONFIG_ERROR", "请配置 Supabase 地址与公开项目密钥。", 503);
  const jar = await cookies();
  return createServerClient(url, key, {
    cookieOptions: { httpOnly: true, sameSite: "lax", secure: process.env.COOKIE_SECURE === "true" },
    cookies: {
      getAll() { return jar.getAll(); },
      setAll(values) { for (const { name, value, options } of values) jar.set(name, value, options); },
    },
    global: { fetch: (input, init) => fetch(input, { ...init, signal: init?.signal ?? AbortSignal.timeout(15_000) }) },
  });
}

export async function currentSession(): Promise<{ info: SessionInfo; supabase?: SupabaseClient }> {
  const mode = storageMode();
  if (mode === "supabase") {
    const client = await supabaseClient();
    const { data, error } = await client.auth.getUser();
    if (error && error.name !== "AuthSessionMissingError") {
      // Invalid or expired credentials are a signed-out state; network failures must be visible.
      if (!error.status || error.status >= 500) throw new AppError("AUTH_UNAVAILABLE", "登录服务暂时不可用，请稍后重试。", 503);
    }
    return {
      info: { user: data.user ? { id: data.user.id, email: data.user.email ?? "" } : null, mode, ai_provider: aiProvider() },
      supabase: client,
    };
  }
  const jar = await cookies();
  const user = localStore().sessionUser(jar.get(SESSION_COOKIE)?.value);
  return { info: { user, mode, ai_provider: aiProvider() } };
}

export async function requireAuth(): Promise<AuthContext> {
  const { info, supabase } = await currentSession();
  if (!info.user) throw new AppError("UNAUTHORIZED", "请先登录，再保存自己的日程。", 401);
  return { user: info.user, mode: info.mode, supabase };
}

export async function setLocalSession(userId: string) {
  const token = localStore().createSession(userId);
  const jar = await cookies();
  jar.set(SESSION_COOKIE, token, {
    httpOnly: true, sameSite: "lax", secure: process.env.COOKIE_SECURE === "true",
    path: "/", maxAge: 7 * 24 * 60 * 60,
  });
}

export async function endLocalSession() {
  const jar = await cookies();
  localStore().deleteSession(jar.get(SESSION_COOKIE)?.value);
  jar.delete(SESSION_COOKIE);
}
