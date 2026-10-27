import { currentSession } from "@/lib/server/auth";
import { handle } from "@/lib/server/http";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";
export async function GET() {
  return handle(async () => (await currentSession()).info);
}
