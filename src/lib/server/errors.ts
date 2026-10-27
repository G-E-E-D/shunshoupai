export class AppError extends Error {
  constructor(public code: string, message: string, public status = 400, public details?: unknown) {
    super(message);
    this.name = "AppError";
  }
}

export function databaseError(error: { message: string; code?: string }): never {
  const known: [string, string, number][] = [
    ["ACCOUNT_MISMATCH", "登录账号已改变，手机日程已保留，请切回原账号后同步。", 409],
    ["VERSION_CONFLICT", "这条日程已在另一端更新，请刷新后重新确认。", 409],
    ["SCHEDULE_CONFLICT", "时间已被其他日程占用，请调整时间后重试。", 409],
    ["TIME_CONFLICT", "时间已被其他日程占用，请调整时间后重试。", 409],
    ["IDEMPOTENCY_CONFLICT", "这次保存的内容已改变，请重新确认。", 409],
    ["NOT_FOUND", "日程不存在或已删除。", 404],
    ["VALIDATION_ERROR", "日程字段或时间不符合要求。", 400],
    ["UNAUTHORIZED", "请先登录。", 401],
  ];
  for (const [code, message, status] of known) {
    if (error.message.includes(code)) throw new AppError(code === "SCHEDULE_CONFLICT" ? "TIME_CONFLICT" : code, message, status);
  }
  if (error.code === "42501") throw new AppError("UNAUTHORIZED", "请先登录。", 401);
  throw new AppError("DATABASE_ERROR", "数据库操作失败，请检查服务配置后重试。", 503);
}
