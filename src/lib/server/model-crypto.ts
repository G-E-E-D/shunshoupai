import { createCipheriv, createDecipheriv, randomBytes } from "node:crypto";
import { execFileSync } from "node:child_process";
import { linkSync, lstatSync, mkdirSync, readFileSync, unlinkSync, writeFileSync } from "node:fs";
import { userInfo } from "node:os";
import { dirname, resolve } from "node:path";
import { AppError } from "./errors";

type StorageMode = "local" | "supabase";

function configuredKey(): Buffer | undefined {
  const value = process.env.MODEL_SETTINGS_ENCRYPTION_KEY?.trim();
  if (!value) return undefined;
  const key = Buffer.from(value, "base64");
  if (key.length !== 32 || key.toString("base64") !== value) {
    throw new AppError("MODEL_ENCRYPTION_UNAVAILABLE", "服务端的模型加密密钥格式错误，需要 32 字节的 Base64 密钥。", 503);
  }
  return key;
}

export function localModelKeyPath(): string {
  const databasePath = process.env.APP_DB_PATH || resolve(process.cwd(), "data", "shunshoupai.sqlite");
  return resolve(dirname(databasePath), "model-encryption.key");
}

function readLocalKey(filename: string): Buffer {
  const stat = lstatSync(filename);
  if (!stat.isFile() || stat.isSymbolicLink()) throw new Error("Invalid key file");
  const key = readFileSync(filename);
  if (key.length !== 32) throw new Error("Invalid key size");
  return key;
}

/** Keys are stored beside the persistent local database, never inside browser storage. */
export function modelEncryptionKey(mode: StorageMode, allowCreate = false): Buffer {
  const explicit = configuredKey();
  if (explicit) return explicit;
  if (mode === "supabase") {
    throw new AppError("MODEL_ENCRYPTION_UNAVAILABLE", "云端模式需要管理员先设置 MODEL_SETTINGS_ENCRYPTION_KEY，才能保存个人模型密钥。", 503);
  }
  const filename = localModelKeyPath();
  try { return readLocalKey(filename); }
  catch (error) {
    if ((error as NodeJS.ErrnoException).code !== "ENOENT") {
      throw new AppError("MODEL_ENCRYPTION_UNAVAILABLE", "无法读取模型加密密钥，请联系服务管理员检查持久化配置。", 503);
    }
    if (!allowCreate) {
      throw new AppError("MODEL_ENCRYPTION_UNAVAILABLE", "本机模型加密密钥文件缺失，请从备份恢复后重试。", 503);
    }
  }
  let temporary: string | undefined;
  try {
    mkdirSync(dirname(filename), { recursive: true });
    temporary = `${filename}.${process.pid}.${randomBytes(8).toString("hex")}.tmp`;
    writeFileSync(temporary, randomBytes(32), { flag: "wx", mode: 0o600 });
    if (process.platform === "win32") {
      const account = process.env.USERDOMAIN ? `${process.env.USERDOMAIN}\\${userInfo().username}` : userInfo().username;
      execFileSync("icacls.exe", [temporary, "/inheritance:r", "/grant:r", `${account}:F`, "SYSTEM:F"], { stdio: "ignore", windowsHide: true });
    }
    try {
      // Publish only after the file's permissions are set. Linking is atomic
      // and refuses to replace another worker's already-published key.
      linkSync(temporary, filename);
    } catch (error) {
      // Another worker may have created the same key. Never replace that key.
      if ((error as NodeJS.ErrnoException).code !== "EEXIST") throw error;
    }
    return readLocalKey(filename);
  } catch {
    throw new AppError("MODEL_ENCRYPTION_UNAVAILABLE", "无法安全保存模型加密密钥，请检查服务端持久磁盘权限。", 503);
  } finally {
    if (temporary) { try { unlinkSync(temporary); } catch { /* A private temporary key may remain if the disk becomes unavailable. */ } }
  }
}

export function modelEncryptionAvailable(mode: StorageMode): boolean {
  try {
    if (configuredKey()) return true;
    if (mode === "supabase") return false;
    try { readLocalKey(localModelKeyPath()); return true; }
    catch (error) { return (error as NodeJS.ErrnoException).code === "ENOENT"; }
  } catch { return false; }
}

export function encryptModelKey(apiKey: string, userId: string, key: Buffer): string {
  const nonce = randomBytes(12);
  const cipher = createCipheriv("aes-256-gcm", key, nonce);
  cipher.setAAD(Buffer.from(`shunshoupai:model-key:${userId}`, "utf8"));
  const encrypted = Buffer.concat([cipher.update(apiKey, "utf8"), cipher.final()]);
  return ["v1", nonce.toString("base64url"), cipher.getAuthTag().toString("base64url"), encrypted.toString("base64url")].join(".");
}

export function decryptModelKey(value: string, userId: string, key: Buffer): string {
  try {
    const pieces = value.split(".");
    if (pieces.length !== 4 || pieces[0] !== "v1" || pieces.slice(1).some(part => !/^[A-Za-z0-9_-]+$/.test(part))) throw new Error("Invalid envelope");
    const nonce = Buffer.from(pieces[1], "base64url");
    const tag = Buffer.from(pieces[2], "base64url");
    const encrypted = Buffer.from(pieces[3], "base64url");
    if (nonce.length !== 12 || tag.length !== 16 || encrypted.length < 1 || encrypted.length > 4096) throw new Error("Invalid encrypted key");
    const decipher = createDecipheriv("aes-256-gcm", key, nonce);
    decipher.setAAD(Buffer.from(`shunshoupai:model-key:${userId}`, "utf8"));
    decipher.setAuthTag(tag);
    return Buffer.concat([decipher.update(encrypted), decipher.final()]).toString("utf8");
  } catch {
    throw new AppError("MODEL_ENCRYPTION_UNAVAILABLE", "个人模型密钥无法解密，请联系管理员恢复加密密钥，或重新填写 API Key。", 503);
  }
}

export function maskModelKey(apiKey: string): string {
  return apiKey.length >= 8 ? `••••${apiKey.slice(-4)}` : "已保存";
}
