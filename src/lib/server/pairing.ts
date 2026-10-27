import { createHash, randomBytes } from "node:crypto";
import { cookies } from "next/headers";
import { z } from "zod";
import type { SessionInfo } from "../types";
import { aiProvider, clearNormalSupabaseCookies, currentSession, SESSION_COOKIE, setLocalSessionToken, supabaseTokenClient, type AuthContext } from "./auth";
import { AppError } from "./errors";
import { localStore } from "./local-store";
import { clearPairedSupabaseSession, decryptPairingAccess, encryptPairingAccess, setPairedSupabaseSession, supabaseTokenDetails } from "./paired-session";

export const pairingIssueSchema = z.object({}).strict();
export const pairingRedeemSchema = z.object({ token: z.string().regex(/^[A-Za-z0-9_-]{43}$/, "登录二维码格式无效，请重新扫码。") }).strict();

export interface PairingTicket {
  token: string;
  expires_at: string;
  pairing_limited: boolean;
  session_expires_at: string | null;
}

export function pairingTokenHash(token: string): string {
  return createHash("sha256").update(token).digest("hex");
}

function pairingDatabaseError(error: { message?: string; code?: string }): never {
  if (error.message?.includes("PAIRING_INVALID")) throw new AppError("PAIRING_INVALID", "登录二维码已失效或已使用，请在已登录设备重新生成。", 400);
  if (error.message?.includes("PAIRING_UNAVAILABLE")) throw new AppError("PAIRING_UNAVAILABLE", "当前会话无法生成登录二维码，请重新登录或改用密码登录。", 503);
  if (error.code === "42501" || error.message?.includes("UNAUTHORIZED")) throw new AppError("UNAUTHORIZED", "请先登录后再生成二维码。", 401);
  throw new AppError("PAIRING_UNAVAILABLE", "扫码登录服务暂时不可用，请检查数据库迁移或使用密码登录。", 503);
}

export async function issuePairingTicket(auth: AuthContext): Promise<PairingTicket> {
  if (!auth.supabase) {
    const source = (await cookies()).get(SESSION_COOKIE)?.value;
    if (!source) throw new AppError("UNAUTHORIZED", "请先登录后再生成二维码。", 401);
    const ticket = localStore().issuePairing(auth.user.id, source);
    return { ...ticket, pairing_limited: false, session_expires_at: null };
  }
  let accessToken = auth.pairedAccessToken;
  if (!accessToken) {
    const { data, error } = await auth.supabase.auth.getSession();
    if (error || !data.session?.access_token) throw new AppError("PAIRING_UNAVAILABLE", "请重新登录后生成二维码，或在另一端使用密码登录。", 503);
    accessToken = data.session.access_token;
  }
  const details = supabaseTokenDetails(accessToken, auth.user.id);
  const encrypted = encryptPairingAccess(accessToken, auth.user.id);
  const token = randomBytes(32).toString("base64url");
  const { data, error } = await auth.supabase.rpc("create_pairing_request", {
    p_token_hash: pairingTokenHash(token), p_auth_ciphertext: encrypted,
  });
  if (error) pairingDatabaseError(error);
  const result = data as { expires_at?: string; auth_expires_at?: string } | null;
  if (!result?.expires_at || !result.auth_expires_at || Date.parse(result.auth_expires_at) > details.expires_ms || Date.parse(result.expires_at) <= Date.now()) {
    throw new AppError("PAIRING_UNAVAILABLE", "扫码授权已到期，请刷新登录状态后重试。", 503);
  }
  return { token, expires_at: result.expires_at, pairing_limited: true, session_expires_at: result.auth_expires_at };
}

export async function redeemPairingTicket(token: string): Promise<SessionInfo> {
  pairingRedeemSchema.parse({ token });
  const mode = process.env.APP_STORAGE || "local";
  if (mode === "local") {
    const result = localStore().redeemPairing(token);
    await clearPairedSupabaseSession();
    await setLocalSessionToken(result.session_token, result.session_expires_at);
    return { user: result.user, mode, ai_provider: aiProvider(), pairing_limited: false, session_expires_at: result.session_expires_at };
  }
  if (mode !== "supabase") throw new AppError("CONFIG_ERROR", "APP_STORAGE 必须为 local 或 supabase。", 503);
  const client = supabaseTokenClient();
  const { data, error } = await client.rpc("consume_pairing_request", { p_token_hash: pairingTokenHash(token) });
  if (error) pairingDatabaseError(error);
  const record = data as { user_id?: string; auth_ciphertext?: string; auth_expires_at?: string } | null;
  if (!record?.user_id || !record.auth_ciphertext || !record.auth_expires_at) {
    throw new AppError("PAIRING_INVALID", "登录二维码已失效，请重新扫码。", 400);
  }
  let accessToken: string;
  try { accessToken = decryptPairingAccess(record.auth_ciphertext, record.user_id); }
  catch { throw new AppError("PAIRING_INVALID", "扫码授权已失效，请重新扫码或使用密码登录。", 400); }
  const details = supabaseTokenDetails(accessToken, record.user_id);
  if (Date.parse(record.auth_expires_at) > details.expires_ms || Date.parse(record.auth_expires_at) <= Date.now()) {
    throw new AppError("PAIRING_INVALID", "临时扫码授权已到期，请重新扫码或使用密码登录。", 401);
  }
  const delegated = supabaseTokenClient(accessToken);
  const checked = await delegated.auth.getUser(accessToken);
  if (checked.error || checked.data.user?.id !== record.user_id) {
    throw new AppError("PAIRING_INVALID", "扫码授权无法验证，请重新扫码或使用密码登录。", 401);
  }
  const existing = await currentSession();
  if (existing.info.user?.id === checked.data.user.id && !existing.pairedAccessToken) {
    // Do not downgrade an already-independent login into a temporary grant.
    await clearPairedSupabaseSession();
    return { ...existing.info, pairing_limited: false };
  }
  if (existing.info.user && !existing.pairedAccessToken && existing.supabase) {
    const signedOut = await existing.supabase.auth.signOut({ scope: "local" });
    if (signedOut.error) throw new AppError("AUTH_UNAVAILABLE", "无法安全切换当前账号，请先退出后重新扫码。", 503);
  }
  await clearNormalSupabaseCookies();
  await setPairedSupabaseSession(checked.data.user.id, accessToken, record.auth_expires_at);
  return {
    user: { id: checked.data.user.id, email: checked.data.user.email ?? "" }, mode, ai_provider: aiProvider(),
    pairing_limited: true, session_expires_at: record.auth_expires_at,
  };
}
