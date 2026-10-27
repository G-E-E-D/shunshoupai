import { handle, rateLimit, readJson } from "@/lib/server/http";
import { pairingRedeemSchema, pairingTokenHash, redeemPairingTicket } from "@/lib/server/pairing";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

export async function POST(request: Request) {
  return handle(async () => {
    const { token } = pairingRedeemSchema.parse(await readJson(request));
    rateLimit("pairing:redeem:global", 300, 60_000);
    rateLimit(`pairing:redeem:${pairingTokenHash(token)}`, 4, 60_000);
    return redeemPairingTicket(token);
  });
}
