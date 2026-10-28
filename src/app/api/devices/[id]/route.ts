import { z } from "zod";
import { clearDeviceSession, requireAuth } from "@/lib/server/auth";
import { localStore } from "@/lib/server/local-store";
import { handle, sameOrigin } from "@/lib/server/http";
export const runtime = "nodejs";
export const dynamic = "force-dynamic";
export async function DELETE(request: Request, context: { params: Promise<{ id: string }> }) {
  return handle(async () => {
    sameOrigin(request);
    const auth = await requireAuth();
    const id = z.string().uuid().parse((await context.params).id);
    const store = localStore();
    const result = store.devices.revoke(auth.grant!, id, clientId => store.peers.disable(clientId));
    if (id === auth.grant!.id) await clearDeviceSession();
    return result;
  });
}
