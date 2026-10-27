import { z } from "zod";
import type { ModelConfig } from "../model-config";
import type { ModelSettingsInfo } from "../types";
import type { AuthContext } from "./auth";
import { AppError } from "./errors";
import { localStore, type LocalStore, type ModelSettingRecord } from "./local-store";
import { decryptModelKey, encryptModelKey, maskModelKey, modelEncryptionAvailable, modelEncryptionKey } from "./model-crypto";
import { ModelTransportError, normalizeModelBaseUrl } from "./model-transport";

const configurationFields = {
  base_url: z.string().trim().min(1, "请填写模型接口地址。").max(2048),
  model: z.string().trim().min(1, "请填写模型名称。").max(200).refine(value => !/[\x00-\x1f\x7f]/.test(value), "模型名称不能包含控制字符。"),
  api_key: z.string().trim().max(4096).refine(value => value === "" || /^[\x21-\x7e]+$/.test(value), "API Key 不能包含空格、换行或非 ASCII 字符。").optional(),
};
export const saveModelSettingsSchema = z.object({ base_version: z.number().int().nonnegative(), ...configurationFields }).strict();
export const testModelSettingsSchema = z.object(configurationFields).strict();
export const deleteModelSettingsSchema = z.object({ base_version: z.number().int().nonnegative() }).strict();
export type ModelSettingsInput = z.infer<typeof testModelSettingsSchema>;

type SettingsRuntime = {
  store?: LocalStore;
  normalize?: (value: string) => Promise<string>;
  encryptionKey?: Buffer;
};

function dbFailure(error: { message?: string; code?: string }): never {
  if (error.message?.includes("VERSION_CONFLICT")) throw new AppError("VERSION_CONFLICT", "模型设置已在另一端更新，请重新加载后再操作。", 409);
  if (error.code === "42501" || error.message?.includes("UNAUTHORIZED")) throw new AppError("UNAUTHORIZED", "请先登录。", 401);
  throw new AppError("MODEL_SETTINGS_UNAVAILABLE", "无法读取或保存个人模型设置，请检查数据库迁移及服务配置。", 503);
}

async function readRecord(auth: AuthContext, runtime: SettingsRuntime): Promise<ModelSettingRecord | null> {
  if (!auth.supabase) return (runtime.store ?? localStore()).modelSetting(auth.user.id);
  const { data, error } = await auth.supabase.rpc("get_model_setting");
  if (error) dbFailure(error);
  return data as ModelSettingRecord | null;
}

function hasPersonal(record: ModelSettingRecord | null): record is ModelSettingRecord & { base_url: string; model: string; api_key_ciphertext: string; key_hint: string } {
  return !!record && !record.deleted_at && !!record.base_url && !!record.model && !!record.api_key_ciphertext;
}

function serverConfig(): ModelConfig | undefined {
  const key = process.env.AI_API_KEY?.trim();
  if (!key) return undefined;
  return { baseUrl: process.env.AI_BASE_URL || "https://api.deepseek.com", model: process.env.AI_MODEL || "deepseek-flash", apiKey: key };
}

function defaults() {
  let base = "https://api.deepseek.com";
  try {
    const url = new URL(process.env.AI_BASE_URL || base);
    if (["http:", "https:"].includes(url.protocol)) base = `${url.origin}${url.pathname}`.replace(/\/+$/, "");
  } catch { /* The administrator's invalid provider configuration must not leak secrets into the form. */ }
  return { base_url: base, model: process.env.AI_MODEL || "deepseek-flash" };
}

function publicInfo(auth: AuthContext, record: ModelSettingRecord | null, runtime: SettingsRuntime): ModelSettingsInfo {
  const configured = hasPersonal(record);
  const hint = configured && /^(?:••••[\x21-\x7e]{4}|已保存)$/.test(record.key_hint) ? record.key_hint : configured ? "已保存" : null;
  return {
    configured,
    base_url: configured ? record.base_url : defaults().base_url,
    model: configured ? record.model : defaults().model,
    has_api_key: configured,
    key_hint: hint,
    version: record?.version ?? 0,
    updated_at: record?.updated_at ?? null,
    effective_source: configured ? "personal" : serverConfig() ? "server" : "rules",
    encryption_available: !!runtime.encryptionKey || modelEncryptionAvailable(auth.mode),
  };
}

export async function getModelSettingsInfo(auth: AuthContext, runtime: SettingsRuntime = {}): Promise<ModelSettingsInfo> {
  return publicInfo(auth, await readRecord(auth, runtime), runtime);
}

export async function resolveModelConfig(auth: AuthContext, runtime: SettingsRuntime = {}): Promise<ModelConfig | undefined> {
  const record = await readRecord(auth, runtime);
  if (!hasPersonal(record)) return serverConfig();
  const key = runtime.encryptionKey ?? modelEncryptionKey(auth.mode);
  return { baseUrl: record.base_url, model: record.model, apiKey: decryptModelKey(record.api_key_ciphertext, auth.user.id, key) };
}

async function preparedConfig(auth: AuthContext, input: ModelSettingsInput, record: ModelSettingRecord | null, runtime: SettingsRuntime): Promise<ModelConfig> {
  let baseUrl: string;
  try { baseUrl = await (runtime.normalize ?? normalizeModelBaseUrl)(input.base_url); }
  catch (error) {
    if (error instanceof ModelTransportError) throw new AppError(error.code, error.message, 400);
    throw error;
  }
  let apiKey = input.api_key?.trim();
  if (!apiKey) {
    if (!hasPersonal(record)) throw new AppError("MODEL_KEY_REQUIRED", "首次连接个人模型时，请填写 API Key。", 400);
    if (new URL(baseUrl).origin !== new URL(record.base_url).origin) {
      throw new AppError("MODEL_KEY_REQUIRED", "更换模型服务地址后，请重新填写 API Key，避免把原密钥发送给其他服务。", 400);
    }
    const key = runtime.encryptionKey ?? modelEncryptionKey(auth.mode);
    apiKey = decryptModelKey(record.api_key_ciphertext, auth.user.id, key);
  }
  return { baseUrl, model: input.model, apiKey };
}

/** A test uses the same-account saved key if blank, and never persists the draft. */
export async function modelConfigForTest(auth: AuthContext, input: ModelSettingsInput, runtime: SettingsRuntime = {}): Promise<ModelConfig> {
  return preparedConfig(auth, testModelSettingsSchema.parse(input), await readRecord(auth, runtime), runtime);
}

export async function saveModelSettings(auth: AuthContext, input: z.infer<typeof saveModelSettingsSchema>, runtime: SettingsRuntime = {}): Promise<ModelSettingsInfo> {
  const parsed = saveModelSettingsSchema.parse(input);
  const current = await readRecord(auth, runtime);
  if ((current?.version ?? 0) !== parsed.base_version) throw new AppError("VERSION_CONFLICT", "模型设置已在另一端更新，请重新加载后再保存。", 409);
  const config = await preparedConfig(auth, parsed, current, runtime);
  const encryptionKey = runtime.encryptionKey ?? modelEncryptionKey(auth.mode, true);
  const encrypted = encryptModelKey(config.apiKey, auth.user.id, encryptionKey);
  const hint = maskModelKey(config.apiKey);
  let record: ModelSettingRecord | null;
  if (!auth.supabase) {
    record = (runtime.store ?? localStore()).saveModelSetting(auth.user.id, parsed.base_version, {
      base_url: config.baseUrl, model: config.model, api_key_ciphertext: encrypted, key_hint: hint,
    });
  } else {
    const { data, error } = await auth.supabase.rpc("save_model_setting", {
      p_base_version: parsed.base_version, p_base_url: config.baseUrl, p_model: config.model,
      p_api_key_ciphertext: encrypted, p_key_hint: hint,
    });
    if (error) dbFailure(error);
    record = data as ModelSettingRecord;
  }
  return publicInfo(auth, record, runtime);
}

export async function deleteModelSettings(auth: AuthContext, baseVersion: number, runtime: SettingsRuntime = {}): Promise<ModelSettingsInfo> {
  deleteModelSettingsSchema.parse({ base_version: baseVersion });
  let record: ModelSettingRecord | null;
  if (!auth.supabase) record = (runtime.store ?? localStore()).deleteModelSetting(auth.user.id, baseVersion);
  else {
    const { data, error } = await auth.supabase.rpc("delete_model_setting", { p_base_version: baseVersion });
    if (error) dbFailure(error);
    record = data as ModelSettingRecord | null;
  }
  return publicInfo(auth, record, runtime);
}
