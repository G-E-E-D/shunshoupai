import { z } from "zod";
import { deviceSession, setDeviceSession, storageMode, type AuthContext } from "./auth";
import { deviceTokenHash } from "./device-store";
import { localStore } from "./local-store";
import { AppError } from "./errors";
import { acceptReturnConnection, queueSync, returnConnectionSchema } from "./peers";
export const pairingIssueSchema = z.object({}).strict();
export const deviceNameSchema = z.string().trim().min(1).max(80).refine((value) => !/[\x00-\x1f\x7f]/.test(value));
export const challengeSchema = z.string().regex(/^[A-Za-z0-9_-]{43}$/);
export const pairingRedeemSchema = z.object({
  return_connection: returnConnectionSchema.optional(),
  token: z.string().regex(/^[A-Za-z0-9_-]{43}$/, "配对二维码格式无效，请重新扫码。"),
  device_id: z.string().uuid(), device_name: deviceNameSchema,
  platform: z.enum(["android", "windows", "web", "test"]).default("web"), challenge: challengeSchema.optional(),
}).strict();
export const pairingTokenHash = deviceTokenHash;
export async function issuePairingTicket(auth: AuthContext) {
  if (!auth.grant) throw new AppError("UNAUTHORIZED", "需要本机设备授权。", 401);
  return localStore().devices.issue(auth.grant);
}
export async function redeemPairingTicket(input: z.infer<typeof pairingRedeemSchema>) {
  storageMode();
  const result = localStore().devices.redeem(input.token, input.device_id, input.device_name, input.platform);
  if (input.return_connection) {
    try { await acceptReturnConnection(localStore(), input.return_connection, input.device_id, input.device_name, result.grant.id); }
    catch (error) { localStore().devices.revokeGrant(result.grant.id); throw error; }
  }
  await setDeviceSession(result.token);
  queueSync();
  return { ...deviceSession(result.grant), device_token: result.token, pairing_id: result.grant.id,
    ...(input.challenge ? localStore().devices.proof(input.challenge, input.device_id) : {}) };
}
