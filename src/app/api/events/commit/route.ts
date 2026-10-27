import { commitSchema } from "@/lib/schemas";
import { requireAuth } from "@/lib/server/auth";
import { commitEvents } from "@/lib/server/events";
import { handle, rateLimit, readJson } from "@/lib/server/http";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";
export async function POST(request: Request) {
  return handle(async () => {
    const auth = await requireAuth();
    rateLimit(`write:${auth.user.id}`, 120, 60_000);
    const { request_id, events } = commitSchema.parse(await readJson(request));
    return commitEvents(auth, request_id, events);
  });
}
