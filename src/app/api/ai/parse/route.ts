import { parseInputSchema } from "@/lib/schemas";
import { parseText } from "@/lib/parser";
import { requireAuth } from "@/lib/server/auth";
import { consumeAiQuota, listEvents } from "@/lib/server/events";
import { AppError } from "@/lib/server/errors";
import { handle, rateLimit, readJson } from "@/lib/server/http";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";
export async function POST(request: Request) {
  return handle(async () => {
    const auth = await requireAuth();
    const { text } = parseInputSchema.parse(await readJson(request));
    rateLimit(`parse:${auth.user.id}`, 6, 60_000);
    await consumeAiQuota(auth);
    // The reference clock is server-owned; the browser cannot silently shift 'today'.
    const now = new Date();
    const busy = await listEvents(auth, new Date(now.getTime() - 86_400_000).toISOString(), new Date(now.getTime() + 31 * 86_400_000).toISOString());
    try { return await parseText(text, now, busy); }
    catch (error) {
      throw new AppError("PARSE_FAILED", error instanceof Error ? error.message : "解析失败，请重试或手工创建日程。", 502);
    }
  });
}
