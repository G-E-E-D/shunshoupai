import { createServerClient } from "@supabase/ssr";
import { createClient, type SupabaseClient } from "@supabase/supabase-js";
import { cookies } from "next/headers";
import type { SessionInfo } from "../types";
import { AppError } from "./errors";
import { LOCAL_SESSION_MAX_AGE, localStore } from "./local-store";
import { clearPairedSupabaseSession, pairedSupabaseCredential } from "./paired-session";

export const SESSION_COOKIE = "shunshoupai_session";
type User = NonNullable<SessionInfo["user"]>;
export type AuthContext = { user: User; mode: "local" | "supabase"; supabase?: SupabaseClient; pairedAccessToken?: string };

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

export function supabaseTokenClient(accessToken?: string) {
  const url = process.env.NEXT_PUBLIC_SUPABASE_URL || process.env.SUPABASE_URL;
  const key = process.env.NEXT_PUBLIC_SUPABASE_ANON_KEY || process.env.SUPABASE_ANON_KEY;
  if (!url || !key) throw new AppError("CONFIG_ERROR", "请配置 Supabase 地址与公开项目密钥。", 503);
  return createClient(url, key, {
    auth: { persistSession: false, autoRefreshToken: false, detectSessionInUrl: false },
    global: {
      ...(accessToken ? { headers: { Authorization: `Bearer ${accessToken}` } } : {}),
      fetch: (input, init) => fetch(input, { ...init, signal: init?.signal ?? AbortSignal.timeout(15_000) }),
    },
  });
}

export async function clearNormalSupabaseCookies() {
  const url = process.env.NEXT_PUBLIC_SUPABASE_URL || process.env.SUPABASE_URL;
  if (!url) return;
  const storageKey = `sb-${new URL(url).hostname.split(".")[0]}-auth-token`;
  const jar = await cookies();
  for (const cookie of jar.getAll()) {
    if (cookie.name === storageKey || cookie.name.startsWith(`${storageKey}.`)) jar.delete(cookie.name);
  }
}

export async function currentSession(): Promise<{ info: SessionInfo; supabase?: SupabaseClient; pairedAccessToken?: string }> {
  const mode = storageMode();
  if (mode === "supabase") {
    const client = await supabaseClient();
    const { data, error } = await client.auth.getUser();
    if (error && error.name !== "AuthSessionMissingError") {
      // Invalid or expired credentials are a signed-out state; network failures must be visible.
      if (!error.status || error.status >= 500) throw new AppError("AUTH_UNAVAILABLE", "登录服务暂时不可用，请稍后重试。", 503);
    }
    if (!data.user) {
      const paired = await pairedSupabaseCredential();
      if (paired) {
        const delegatedClient = supabaseTokenClient(paired.accessToken);
        const checked = await delegatedClient.auth.getUser(paired.accessToken);
        if (checked.error && (!checked.error.status || checked.error.status >= 500)) {
          throw new AppError("AUTH_UNAVAILABLE", "登录服务暂时不可用，请稍后重试。", 503);
        }
        if (checked.data.user?.id === paired.userId) return {
          info: {
            user: { id: checked.data.user.id, email: checked.data.user.email ?? "" }, mode, ai_provider: aiProvider(),
            pairing_limited: true, session_expires_at: paired.expiresAt,
          },
          supabase: delegatedClient,
          pairedAccessToken: paired.accessToken,
        };
        await clearPairedSupabaseSession();
      }
    }
    return {
      info: { user: data.user ? { id: data.user.id, email: data.user.email ?? "" } : null, mode, ai_provider: aiProvider() },
      supabase: client,
    };
  }
  const jar = await cookies();
  const token = jar.get(SESSION_COOKIE)?.value;
  const session = localStore().refreshSession(token);
  if (session?.renewed) await setLocalSessionToken(token!, session.expires_at);
  return { info: { user: session?.user ?? null, mode, ai_provider: aiProvider(), session_expires_at: session?.expires_at ?? null } };
}

export async function requireAuth(): Promise<AuthContext> {
  const { info, supabase, pairedAccessToken } = await currentSession();
  if (!info.user) throw new AppError("UNAUTHORIZED", "请先登录，再保存自己的日程。", 401);
  return { user: info.user, mode: info.mode, supabase, pairedAccessToken };
}

export async function setLocalSession(userId: string) {
  const token = localStore().createSession(userId);
  await setLocalSessionToken(token);
}

export async function setLocalSessionToken(token: string, expiresAt?: string) {
  const jar = await cookies();
  jar.set(SESSION_COOKIE, token, {
    httpOnly: true, sameSite: "lax", secure: process.env.COOKIE_SECURE === "true",
    path: "/", maxAge: expiresAt ? Math.max(1, Math.floor((Date.parse(expiresAt) - Date.now()) / 1000)) : LOCAL_SESSION_MAX_AGE,
  });
}

export async function endLocalSession() {
  const jar = await cookies();
  localStore().deleteSession(jar.get(SESSION_COOKIE)?.value);
  jar.delete(SESSION_COOKIE);
}
