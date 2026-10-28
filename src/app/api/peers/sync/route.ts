import { z } from "zod";
import { assertAuthActive, requireAuth } from "@/lib/server/auth";
import { handle, readJson } from "@/lib/server/http";
import { queueSync, requireOwner } from "@/lib/server/peers";
export const runtime = "nodejs";
export const dynamic = "force-dynamic";
export async function POST(request: Request) {
  const response = await handle(async () => {
    const auth = await requireAuth();
    requireOwner(auth.grant);
    z.object({}).strict().parse(await readJson(request));
    assertAuthActive(auth);
    return queueSync();
  });
  if (response.ok) return new Response(response.body, { status: 202, headers: response.headers });
  return response;
}
