import { requireAuth } from "@/lib/server/auth";
import { listEvents } from "@/lib/server/events";
import { handle, validateWindow } from "@/lib/server/http";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";
export async function GET(request: Request) {
  return handle(async () => {
    const auth = await requireAuth();
    const { from, to } = validateWindow(request);
    return { events: await listEvents(auth, from, to) };
  });
}
