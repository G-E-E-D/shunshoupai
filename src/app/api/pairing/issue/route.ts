import { requireAuth } from "@/lib/server/auth";
import { handle, rateLimit, readJson } from "@/lib/server/http";
import { issuePairingTicket, pairingIssueSchema } from "@/lib/server/pairing";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

export async function POST(request: Request) {
  return handle(async () => {
    const auth = await requireAuth();
    pairingIssueSchema.parse(await readJson(request));
    rateLimit(`pairing:issue:${auth.user.id}`, 10, 60_000);
    return issuePairingTicket(auth);
  });
}
