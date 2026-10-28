import { z } from "zod";
import { challengeSchema } from "@/lib/server/pairing";
import { localStore } from "@/lib/server/local-store";
import { storageMode } from "@/lib/server/auth";
import { handle, rateLimit } from "@/lib/server/http";
export const runtime = "nodejs";
export const dynamic = "force-dynamic";
export async function GET(request: Request) {
  return handle(() => {
    storageMode(); rateLimit("device:identity", 120, 60_000);
    const params = new URL(request.url).searchParams;
    return localStore().devices.proof(challengeSchema.parse(params.get("challenge")), z.string().uuid().parse(params.get("device_id")));
  });
}
