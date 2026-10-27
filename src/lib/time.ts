export const TIMEZONE = "Asia/Shanghai" as const;

const LOCAL_PATTERN = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2}))?$/;
const SHANGHAI_OFFSET = 8 * 60 * 60 * 1000;

/** This release accepts Shanghai wall time only, with no DST ambiguity. */
export function validateLocal(value: string): boolean {
  const match = LOCAL_PATTERN.exec(value);
  if (!match) return false;
  const [, y, mo, d, h, mi, s = "0"] = match;
  const values = [y, mo, d, h, mi, s].map(Number);
  const [year, month, day, hour, minute, second] = values;
  // Shanghai used daylight saving before 1992. Limit the supported domain so
  // this fixed-offset conversion agrees with PostgreSQL's IANA timezone data.
  if (year < 2000 || year > 2100 || month < 1 || month > 12 || day < 1 || day > 31 || hour > 23 || minute > 59 || second > 59) return false;
  const date = new Date(Date.UTC(year, month - 1, day, hour, minute, second));
  return date.getUTCFullYear() === year && date.getUTCMonth() === month - 1 && date.getUTCDate() === day;
}

export function localToIso(value: string): string {
  if (!validateLocal(value)) throw new Error("日期或时间无效，请检查具体日期及起止时间。");
  const match = LOCAL_PATTERN.exec(value)!;
  const [, y, mo, d, h, mi, s = "0"] = match;
  return new Date(Date.UTC(+y, +mo - 1, +d, +h, +mi, +s) - SHANGHAI_OFFSET).toISOString();
}

export function isoToLocal(value: string): string {
  const date = new Date(value);
  if (!Number.isFinite(date.getTime())) throw new Error("时间无效。");
  return new Date(date.getTime() + SHANGHAI_OFFSET).toISOString().slice(0, 19);
}

export function shanghaiDate(referenceTime: Date): string {
  return isoToLocal(referenceTime.toISOString()).slice(0, 10);
}

export function addDays(date: string, days: number): string {
  const point = Date.parse(`${date}T00:00:00.000Z`);
  return new Date(point + days * 86_400_000).toISOString().slice(0, 10);
}

export function overlaps(aStart: string, aEnd: string, bStart: string, bEnd: string): boolean {
  return Date.parse(aStart) < Date.parse(bEnd) && Date.parse(bStart) < Date.parse(aEnd);
}

export function eventDurationMinutes(startLocal: string, endLocal: string): number {
  return (Date.parse(localToIso(endLocal)) - Date.parse(localToIso(startLocal))) / 60_000;
}
