import { currentSession } from "@/lib/server/auth";
import { handle } from "@/lib/server/http";
import { getModelSettingsInfo } from "@/lib/server/model-settings";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";
export async function GET() {
  return handle(async () => {
    const { info, supabase } = await currentSession();
    if (!info.user || info.role !== "owner") return info;
    const settings = await getModelSettingsInfo({
      user: info.user,
      mode: info.mode,
      supabase,
    });
    return {
      ...info,
      ai_provider: settings.effective_source === "rules" ? "rules" : "model",
    };
  });
}
