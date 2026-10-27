import { authSchema } from "@/lib/schemas";
import { aiProvider, clearNormalSupabaseCookies, currentSession, endLocalSession, setLocalSession, storageMode, supabaseClient } from "@/lib/server/auth";
import { clearPairedSupabaseSession } from "@/lib/server/paired-session";
import { localStore } from "@/lib/server/local-store";
import { AppError } from "@/lib/server/errors";
import { handle, rateLimit, readJson, sameOrigin } from "@/lib/server/http";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";
export async function POST(request: Request, context: { params: Promise<{ action: string }> }) {
  return handle(async () => {
    sameOrigin(request);
    const { action } = await context.params;
    const mode = storageMode();
    if (action === "logout") {
      if (mode === "supabase") {
        const session = await currentSession();
        if (!session.pairedAccessToken) {
          const { error } = await (session.supabase ?? await supabaseClient()).auth.signOut({ scope: "local" });
          if (error) throw new AppError("AUTH_UNAVAILABLE", "退出失败，请重试。", 503);
        }
        // A temporary grant must not revoke the issuing desktop's Auth session.
        await clearPairedSupabaseSession();
        await clearNormalSupabaseCookies();
      } else await endLocalSession();
      return { user: null, mode, ai_provider: aiProvider() };
    }
    if (action !== "login" && action !== "register") throw new AppError("NOT_FOUND", "接口不存在。", 404);
    const { email, password } = authSchema.parse(await readJson(request));
    rateLimit("auth:global", 300, 60_000);
    rateLimit(`auth:${action}:${email}`, 15, 15 * 60_000);
    if (mode === "supabase") {
      const client = await supabaseClient();
      const { data, error } = action === "register"
        ? await client.auth.signUp({ email, password })
        : await client.auth.signInWithPassword({ email, password });
      if (error) {
        if (error.status === 429) throw new AppError("RATE_LIMITED", "登录尝试过多，请稍后重试。", 429);
        if (!error.status || error.status >= 500) throw new AppError("AUTH_UNAVAILABLE", "登录服务暂时不可用，请稍后重试。", 503);
        throw new AppError("AUTH_FAILED", action === "login" ? "邮箱或密码不正确，或邮箱尚未完成验证。" : "注册失败，请检查邮箱、密码或稍后重试。", 400);
      }
      if (!data.session || !data.user) return {
        user: null, mode, ai_provider: aiProvider(), requires_email_confirmation: true,
        message: "注册请求已提交。请查看邮箱中的验证邮件，验证后回到这里登录。",
      };
      await clearPairedSupabaseSession();
      return { user: { id: data.user.id, email: data.user.email ?? email }, mode, ai_provider: aiProvider() };
    }
    const user = action === "register" ? localStore().register(email, password) : localStore().login(email, password);
    await setLocalSession(user.id);
    return { user, mode, ai_provider: aiProvider() };
  });
}
