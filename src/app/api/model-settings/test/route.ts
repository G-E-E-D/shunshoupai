import { requireAuth } from "@/lib/server/auth";
import { consumeAiQuota } from "@/lib/server/events";
import { AppError } from "@/lib/server/errors";
import { handle, rateLimit, readJson } from "@/lib/server/http";
import { modelConfigForTest, testModelSettingsSchema } from "@/lib/server/model-settings";
import { checkModelConnection, ModelTransportError } from "@/lib/server/model-transport";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

export async function POST(request: Request) {
  return handle(async () => {
    const auth = await requireAuth();
    const input = testModelSettingsSchema.parse(await readJson(request));
    rateLimit(`model-settings:test:${auth.user.id}`, 6, 60_000);
    const config = await modelConfigForTest(auth, input);
    await consumeAiQuota(auth);
    try {
      const result = await checkModelConnection(config);
      return { success: true, message: result.message };
    } catch (error) {
      if (error instanceof AppError) throw error;
      if (error instanceof ModelTransportError) throw new AppError(error.code, error.message, 502);
      throw new AppError("MODEL_TEST_FAILED", "连接测试失败，请检查接口地址、模型名和 API Key，或稍后重试。", 502);
    }
  });
}
