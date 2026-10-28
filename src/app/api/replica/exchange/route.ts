import { assertAuthActive, requireAuth } from "@/lib/server/auth";
import { handle, readJson } from "@/lib/server/http";
import { exchangeSchema } from "@/lib/server/peers";
import { localStore } from "@/lib/server/local-store";
import { AppError } from "@/lib/server/errors";
export const runtime = "nodejs";
export const dynamic = "force-dynamic";
export async function POST(request: Request) {
  return handle(async () => {
    const auth = await requireAuth();
    const input = exchangeSchema.parse(await readJson(request, 16 * 1024 * 1024));
    assertAuthActive(auth);
    if (input.device_id !== auth.grant!.client_id) throw new AppError("DEVICE_MISMATCH", "同步设备与授权不匹配。", 403);
    const store = localStore();
    return { ...store.devices.context(), ...store.exchange(auth.user.id, input.records, input.after) };
  });
}
