import { createClient, type SupabaseClient } from "@supabase/supabase-js";
import { cookies, headers } from "next/headers";
import type { SessionInfo } from "../types";
import { AppError } from "./errors";
import { localStore } from "./local-store";
import type { DeviceGrant } from "./device-store";
import { startPeerWorker } from "./peers";

export const SESSION_COOKIE = "shunshoupai_device";
export const LEGACY_SESSION_COOKIE = "shunshoupai_session";
export const DEVICE_COOKIE_MAX_AGE = 365 * 24 * 60 * 60;
type User = NonNullable<SessionInfo["user"]>;
export type AuthContext = { user: User; mode: "local" | "supabase"; supabase?: SupabaseClient; pairedAccessToken?: string; grant?: DeviceGrant };

export function storageMode(): "local" {
  if (process.env.APP_STORAGE && process.env.APP_STORAGE !== "local") {
    throw new AppError("LOCAL_STORAGE_REQUIRED", "设备直连版使用本机数据。旧云端数据保持原样，请先导出后迁移到本机。", 503);
  }
  return "local";
}
export function aiProvider(): "model" | "rules" { return process.env.AI_API_KEY ? "model" : "rules"; }

// Legacy export/crypto tooling only; account authentication is no longer used.
export function supabaseTokenClient(accessToken?: string) {
  const url = process.env.NEXT_PUBLIC_SUPABASE_URL || process.env.SUPABASE_URL;
  const key = process.env.NEXT_PUBLIC_SUPABASE_ANON_KEY || process.env.SUPABASE_ANON_KEY;
  if (!url || !key) throw new AppError("CONFIG_ERROR", "请配置旧版 Supabase 导出连接。", 503);
  return createClient(url, key, {
    auth: { persistSession: false, autoRefreshToken: false, detectSessionInUrl: false },
    global: {
      ...(accessToken ? { headers: { Authorization: `Bearer ${accessToken}` } } : {}),
      fetch: (input, init) => fetch(input, { ...init, signal: init?.signal ?? AbortSignal.timeout(15_000) }),
    },
  });
}
export async function deviceCredential() {
  const authorization = (await headers()).get("authorization");
  if (authorization !== null) return authorization.startsWith("Bearer ") ? authorization.slice(7) : undefined;
  return (await cookies()).get(SESSION_COOKIE)?.value;
}
export function deviceSession(grant: DeviceGrant): SessionInfo {
  return {
    user: { id: grant.space_id, email: "" }, mode: "local", ai_provider: grant.role === "owner" ? aiProvider() : "rules", local_first: true,
    ...localStore().devices.context(), role: grant.role,
    peer: { id: grant.id, name: grant.name, platform: grant.platform }, pairing_limited: false, session_expires_at: null,
  };
}
export async function currentSession(): Promise<{ info: SessionInfo; supabase?: SupabaseClient; grant?: DeviceGrant }> {
  storageMode();
  startPeerWorker();
  const grant = localStore().devices.authenticate(await deviceCredential());
  return grant ? { info: deviceSession(grant), grant } : { info: { user: null, mode: "local", ai_provider: "rules", local_first: true } };
}
export async function requireAuth(): Promise<AuthContext> {
  const { info, grant } = await currentSession();
  if (!info.user || !grant) throw new AppError("UNAUTHORIZED", "请先扫描本机配对码，允许这台设备访问日程。", 401);
  return { user: info.user, mode: "local", grant };
}
// Pairing grants schedule access only. Model credentials and model calls stay local.
export async function requireLocalFeature(): Promise<AuthContext> {
  const auth = await requireAuth();
  if (auth.grant?.role !== "owner") {
    throw new AppError("SYNC_ONLY_DEVICE", "配对仅同步日程，请在本机应用配置模型和使用智能整理。", 403);
  }
  assertAuthActive(auth);
  return auth;
}
export function assertAuthActive(auth: AuthContext) {
  if (!auth.grant) throw new AppError("UNAUTHORIZED", "设备授权已解除。", 401);
  localStore().devices.assertActive(auth.grant);
}
export async function setDeviceSession(token: string) {
  (await cookies()).set(SESSION_COOKIE, token, {
    httpOnly: true, sameSite: "lax", secure: process.env.COOKIE_SECURE === "true", path: "/", maxAge: DEVICE_COOKIE_MAX_AGE,
  });
}
export async function clearDeviceSession() { (await cookies()).delete(SESSION_COOKIE); }
