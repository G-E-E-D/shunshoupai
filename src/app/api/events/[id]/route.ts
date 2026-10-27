import { z } from "zod";
import { deleteEventSchema, patchEventSchema } from "@/lib/schemas";
import { requireAuth } from "@/lib/server/auth";
import { mutateEvent } from "@/lib/server/events";
import { handle, rateLimit, readJson } from "@/lib/server/http";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";
type Context = { params: Promise<{ id: string }> };

export async function PATCH(request: Request, context: Context) {
  return handle(async () => {
    const auth = await requireAuth();
    rateLimit(`write:${auth.user.id}`, 120, 60_000);
    const id = z.string().uuid().parse((await context.params).id);
    const { base_version, ...patch } = patchEventSchema.parse(await readJson(request));
    return mutateEvent(auth, id, base_version, patch);
  });
}

export async function DELETE(request: Request, context: Context) {
  return handle(async () => {
    const auth = await requireAuth();
    rateLimit(`write:${auth.user.id}`, 120, 60_000);
    const id = z.string().uuid().parse((await context.params).id);
    const { base_version } = deleteEventSchema.parse(await readJson(request));
    return mutateEvent(auth, id, base_version, {}, true);
  });
}
