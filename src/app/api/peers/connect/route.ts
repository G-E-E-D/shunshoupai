import { z } from "zod";
import { assertAuthActive, requireAuth } from "@/lib/server/auth";
import { handle, readJson } from "@/lib/server/http";
import { queueConnect, requireOwner } from "@/lib/server/peers";
import { buildConnectionInfo } from "@/lib/server/connection-info";
export const runtime = "nodejs";
export const dynamic = "force-dynamic";
export async function POST(request: Request) {
  const response = await handle(async () => {
    const auth = await requireAuth();
    requireOwner(auth.grant);
    const input = z.object({ url: z.string().max(2048), local_origin: z.string().max(512).optional() }).strict().parse(await readJson(request));
    assertAuthActive(auth);
    const local = buildConnectionInfo(request.url, { requestHost: request.headers.get("host"), appOrigin: process.env.APP_ORIGIN });
    return queueConnect(input.url, input.local_origin ?? local.candidates[0] ?? local.current_origin, auth.grant!.id);
  });
  if (response.ok) return new Response(response.body, { status: 202, headers: response.headers });
  return response;
}
