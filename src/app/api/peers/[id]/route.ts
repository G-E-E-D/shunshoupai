import { z } from "zod";
import { assertAuthActive, requireAuth } from "@/lib/server/auth";
import { handle, readJson } from "@/lib/server/http";
import { removePeer, requireOwner } from "@/lib/server/peers";
export const runtime = "nodejs";
export const dynamic = "force-dynamic";
export async function DELETE(request: Request, context: { params: Promise<{ id: string }> }) {
  return handle(async () => {
    const auth = await requireAuth();
    requireOwner(auth.grant);
    z.object({}).strict().parse(await readJson(request));
    const id = z.string().uuid().parse((await context.params).id);
    assertAuthActive(auth);
    return removePeer(id);
  });
}
