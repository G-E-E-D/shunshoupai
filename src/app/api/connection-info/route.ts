import { requireAuth } from "@/lib/server/auth";
import { buildConnectionInfo } from "@/lib/server/connection-info";
import { handle, rateLimit } from "@/lib/server/http";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

export async function GET(request: Request) {
  return handle(async () => {
    const auth = await requireAuth();
    rateLimit(`connection-info:${auth.user.id}`, 60, 60_000);
    return buildConnectionInfo(request.url, {
      appOrigin: process.env.APP_ORIGIN,
      storageMode: auth.mode,
      requestHost: request.headers.get("host"),
    });
  });
}
