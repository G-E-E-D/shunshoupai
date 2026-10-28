import { timingSafeEqual } from "node:crypto";
import { cookies } from "next/headers";
import { z } from "zod";
import { LEGACY_SESSION_COOKIE, deviceCredential, deviceSession, setDeviceSession, storageMode } from "@/lib/server/auth";
import { localStore } from "@/lib/server/local-store";
import { deviceNameSchema } from "@/lib/server/pairing";
import { AppError } from "@/lib/server/errors";
import { handle, readJson, sameOrigin } from "@/lib/server/http";
export const runtime = "nodejs";
export const dynamic = "force-dynamic";
const schema = z.object({ name: deviceNameSchema, platform: z.enum(["windows", "test"]), legacy_space_id: z.string().uuid().optional() }).strict();
export async function POST(request: Request) {
  return handle(async () => {
    sameOrigin(request); storageMode();
    const expected = process.env.APP_DEVICE_BOOTSTRAP_KEY;
    const supplied = request.headers.get("x-device-bootstrap") ?? "";
    // Host, User-Agent and Origin cannot prove physical device ownership.
    if (!expected || expected.length < 32 || Buffer.byteLength(expected) !== Buffer.byteLength(supplied)
      || !timingSafeEqual(Buffer.from(expected), Buffer.from(supplied))) {
      throw new AppError("BOOTSTRAP_DENIED", "请从这台电脑的原生应用打开日程；其他设备需要扫码配对。", 403);
    }
    const input = schema.parse(await readJson(request));
    const jar = await cookies();
    const result = localStore().devices.bootstrap(input.name, input.platform, await deviceCredential(), jar.get(LEGACY_SESSION_COOKIE)?.value, input.legacy_space_id);
    await setDeviceSession(result.token); jar.delete(LEGACY_SESSION_COOKIE);
    return deviceSession(result.grant);
  });
}
