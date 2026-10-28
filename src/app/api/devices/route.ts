import { requireAuth } from "@/lib/server/auth";
import { localStore } from "@/lib/server/local-store";
import { handle } from "@/lib/server/http";
import { connectionStatus } from "@/lib/server/peers";
export const runtime = "nodejs";
export const dynamic = "force-dynamic";
export async function GET() {
  return handle(async () => {
    const grant = (await requireAuth()).grant!;
    const store = localStore();
    return { ...store.devices.list(grant), ...(grant.role === "owner" ? { peers: store.peers.list(), connection: connectionStatus() } : {}) };
  });
}
