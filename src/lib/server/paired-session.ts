import { cookies } from "next/headers";
import { decryptModelKey, encryptModelKey, modelEncryptionKey } from "./model-crypto";
import { AppError } from "./errors";

export const PAIRED_SUPABASE_COOKIE = "shunshoupai_paired_supabase";
const CHUNK_SIZE = 3500;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** Metadata is inspected only after Supabase verifies the JWT in the caller. */
export function supabaseTokenDetails(accessToken: string, userId: string, now = Date.now()): { expires_at: string; expires_ms: number } {
  try {
    if (!UUID.test(userId) || accessToken.length > 4096) throw new Error("Invalid credential");
    const parts = accessToken.split(".");
    if (parts.length !== 3 || parts.some(part => !/^[A-Za-z0-9_-]+$/.test(part))) throw new Error("Invalid JWT");
    const claims = JSON.parse(Buffer.from(parts[1], "base64url").toString("utf8")) as { sub?: string; exp?: number };
    if (claims.sub !== userId || !Number.isSafeInteger(claims.exp) || (claims.exp! * 1000) <= now) throw new Error("Expired or mismatched JWT");
    return { expires_at: new Date(claims.exp! * 1000).toISOString(), expires_ms: claims.exp! * 1000 };
  } catch {
    throw new AppError("PAIRING_INVALID", "临时扫码授权已失效，请重新扫码或使用密码登录。", 401);
  }
}

export function encryptPairingAccess(accessToken: string, userId: string, key = modelEncryptionKey("supabase")): string {
  supabaseTokenDetails(accessToken, userId);
  return encryptModelKey(accessToken, `pairing:${userId}`, key);
}

export function decryptPairingAccess(encrypted: string, userId: string, key = modelEncryptionKey("supabase")): string {
  const token = decryptModelKey(encrypted, `pairing:${userId}`, key);
  supabaseTokenDetails(token, userId);
  return token;
}

export function encodePairedSupabaseCookie(userId: string, accessToken: string, expiresAt?: string, key = modelEncryptionKey("supabase")): string {
  const details = supabaseTokenDetails(accessToken, userId);
  const expires = expiresAt ? Date.parse(expiresAt) : details.expires_ms;
  if (!Number.isSafeInteger(expires) || expires <= Date.now() || expires > details.expires_ms) {
    throw new AppError("PAIRING_INVALID", "临时扫码授权已到期，请重新扫码或使用密码登录。", 401);
  }
  return `${userId}~${expires}~${encryptModelKey(accessToken, `paired-cookie:${userId}:${expires}`, key)}`;
}

export function decodePairedSupabaseCookie(value: string, key = modelEncryptionKey("supabase")): { userId: string; accessToken: string; expiresAt: string } {
  const first = value.indexOf("~");
  const second = value.indexOf("~", first + 1);
  const userId = value.slice(0, first);
  const expires = Number(value.slice(first + 1, second));
  if (value.length > 6000 || first <= 0 || second <= first || !UUID.test(userId) || !Number.isSafeInteger(expires) || expires <= Date.now()) {
    throw new AppError("PAIRING_INVALID", "临时扫码授权已失效，请重新扫码或使用密码登录。", 401);
  }
  const accessToken = decryptModelKey(value.slice(second + 1), `paired-cookie:${userId}:${expires}`, key);
  if (expires > supabaseTokenDetails(accessToken, userId).expires_ms) {
    throw new AppError("PAIRING_INVALID", "临时扫码授权已失效，请重新扫码或使用密码登录。", 401);
  }
  return { userId, accessToken, expiresAt: new Date(expires).toISOString() };
}

export async function clearPairedSupabaseSession() {
  const jar = await cookies();
  jar.delete(PAIRED_SUPABASE_COOKIE);
  jar.delete(`${PAIRED_SUPABASE_COOKIE}.1`);
}

export async function pairedSupabaseCredential(): Promise<{ userId: string; accessToken: string; expiresAt: string } | null> {
  const jar = await cookies();
  const first = jar.get(PAIRED_SUPABASE_COOKIE)?.value;
  if (!first) return null;
  try {
    const value = first + (jar.get(`${PAIRED_SUPABASE_COOKIE}.1`)?.value || "");
    return decodePairedSupabaseCookie(value);
  } catch {
    await clearPairedSupabaseSession();
    return null;
  }
}

export async function setPairedSupabaseSession(userId: string, accessToken: string, expiresAt?: string) {
  const value = encodePairedSupabaseCookie(userId, accessToken, expiresAt);
  const expiresMs = Number(value.split("~")[1]);
  const jar = await cookies();
  const options = {
    httpOnly: true, sameSite: "lax" as const, secure: process.env.COOKIE_SECURE === "true", path: "/",
    maxAge: Math.max(1, Math.floor((expiresMs - Date.now()) / 1000)),
  };
  jar.set(PAIRED_SUPABASE_COOKIE, value.slice(0, CHUNK_SIZE), options);
  if (value.length > CHUNK_SIZE) jar.set(`${PAIRED_SUPABASE_COOKIE}.1`, value.slice(CHUNK_SIZE), options);
  else jar.delete(`${PAIRED_SUPABASE_COOKIE}.1`);
}
