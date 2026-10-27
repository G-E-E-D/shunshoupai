import type { SyncRequest, SyncResponse } from "../sync";
import type { AuthContext } from "./auth";
import { AppError, databaseError } from "./errors";
import { localStore } from "./local-store";

export async function syncEvents(auth: AuthContext, request: SyncRequest): Promise<SyncResponse> {
  if (request.account_id !== auth.user.id) throw new AppError("ACCOUNT_MISMATCH", "登录账号已改变，手机日程已保留，请切回原账号后同步。", 409);
  if (!auth.supabase) return localStore().sync(auth.user.id, request);
  const { data, error } = await auth.supabase.rpc("sync_events", {
    p_account_id: request.account_id, p_mutations: request.mutations, p_after: request.after ?? null,
  });
  if (error) {
    if (error.code === "PGRST202") throw new AppError("SYNC_UNAVAILABLE", "服务需要升级才能同步；手机日程已保留，可继续使用。", 503);
    databaseError(error);
  }
  return data as SyncResponse;
}
