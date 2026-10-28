import { deviceCredential, deviceSession, requireLocalFeature, setDeviceSession } from "@/lib/server/auth";
import { handle, sameOrigin } from "@/lib/server/http";
export const runtime = "nodejs";
export const dynamic = "force-dynamic";
export async function POST(request: Request) {
  return handle(async () => {
    sameOrigin(request);
    const auth = await requireLocalFeature();
    await setDeviceSession((await deviceCredential())!);
    return deviceSession(auth.grant!);
  });
}
