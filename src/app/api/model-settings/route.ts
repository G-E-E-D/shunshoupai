import { assertAuthActive, requireLocalFeature } from "@/lib/server/auth";
import { handle, rateLimit, readJson } from "@/lib/server/http";
import { deleteModelSettings, deleteModelSettingsSchema, getModelSettingsInfo, saveModelSettings, saveModelSettingsSchema } from "@/lib/server/model-settings";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

export async function GET() {
  return handle(async () => {
    const auth = await requireLocalFeature();
    rateLimit(`model-settings:read:${auth.user.id}`, 120, 60_000);
    return { settings: await getModelSettingsInfo(auth) };
  });
}

export async function PUT(request: Request) {
  return handle(async () => {
    const auth = await requireLocalFeature();
    const input = saveModelSettingsSchema.parse(await readJson(request));
    assertAuthActive(auth);
    rateLimit(`model-settings:write:${auth.user.id}`, 30, 60_000);
    return { settings: await saveModelSettings(auth, input) };
  });
}

export async function DELETE(request: Request) {
  return handle(async () => {
    const auth = await requireLocalFeature();
    const { base_version } = deleteModelSettingsSchema.parse(await readJson(request));
    assertAuthActive(auth);
    rateLimit(`model-settings:write:${auth.user.id}`, 30, 60_000);
    return { settings: await deleteModelSettings(auth, base_version) };
  });
}
