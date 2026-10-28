import type { SyncRequest, SyncResponse } from "../sync";
import type { AuthContext } from "./auth";
import { AppError, databaseError } from "./errors";
import { localStore } from "./local-store";

export async function syncEvents(auth: AuthContext, request: SyncRequest): Promise<SyncResponse> {
  const spaceId = request.space_id ?? request.account_id;
  if (spaceId !== auth.user.id) throw new AppError("SPACE_MISMATCH", "配对日程空间不一致，本机修改已保留，请重新核对设备。", 409);
  if (!auth.supabase) return { ...localStore().sync(auth.user.id, request), ...localStore().devices.context() };
  const { data, error } = await auth.supabase.rpc("sync_events", {
    p_account_id: spaceId, p_mutations: request.mutations, p_after: request.after ?? null,
  });
  if (error) {
    if (error.code === "PGRST202") throw new AppError("SYNC_UNAVAILABLE", "服务需要升级才能同步；手机日程已保留，可继续使用。", 503);
    databaseError(error);
  }
  return data as SyncResponse;
}
