import { syncRequestSchema } from "@/lib/sync";
import { assertAuthActive, requireAuth } from "@/lib/server/auth";
import { syncEvents } from "@/lib/server/sync";
import { handle, rateLimit, readJson } from "@/lib/server/http";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

export async function POST(request: Request) {
  return handle(async () => {
    const auth = await requireAuth();
    rateLimit(`sync:${auth.user.id}`, 120, 60_000);
    const input = syncRequestSchema.parse(await readJson(request));
    assertAuthActive(auth);
    return syncEvents(auth, input);
  });
}
