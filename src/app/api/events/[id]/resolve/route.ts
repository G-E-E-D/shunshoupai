import { z } from "zod";
import { assertAuthActive, requireAuth } from "@/lib/server/auth";
import { handle, readJson } from "@/lib/server/http";
import { localStore } from "@/lib/server/local-store";
export const runtime = "nodejs";
export const dynamic = "force-dynamic";
export async function POST(request: Request, context: { params: Promise<{ id: string }> }) {
  return handle(async () => {
    const auth = await requireAuth();
    const id = z.string().uuid().parse((await context.params).id);
    const input = z.object({ base_version: z.number().int().positive(), revision_id: z.string().uuid() }).strict().parse(await readJson(request));
    assertAuthActive(auth);
    return localStore().resolveReplica(auth.user.id, id, input.base_version, input.revision_id);
  });
}
