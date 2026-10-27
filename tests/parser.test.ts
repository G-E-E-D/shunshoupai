import assert from "node:assert/strict";
import { test } from "node:test";
import type { EventDraft, ScheduleEvent } from "../src/lib/types";
import { parseText, parseWithRules } from "../src/lib/parser";
import { commitSchema, eventDraftSchema, patchEventSchema } from "../src/lib/schemas";
import { isoToLocal, localToIso, overlaps, validateLocal } from "../src/lib/time";

const NOW = new Date("2026-10-27T02:00:00Z"); // Tuesday 10:00 Shanghai.
const sample: EventDraft = { title: "面试", start_local: "2026-10-28T15:00:00", end_local: "2026-10-28T16:00:00", timezone: "Asia/Shanghai", time_source: "explicit", assumptions: [] };

test("main demo: three real drafts inherit date and evening correctly", () => {
  const result = parseWithRules("明天下午三点到四点面试，今天晚上七点到八点改简历，八点到九点准备面试。", NOW);
  assert.equal(result.provider, "rules");
  assert.equal(result.needs_clarification, false);
  assert.deepEqual(result.events.map((event) => [event.title, event.start_local, event.end_local]), [
    ["面试", "2026-10-28T15:00:00", "2026-10-28T16:00:00"],
    ["改简历", "2026-10-27T19:00:00", "2026-10-27T20:00:00"],
    ["准备面试", "2026-10-27T20:00:00", "2026-10-27T21:00:00"],
  ]);
});

const clearCases = [
  ["今天上午十点到十一点开会", "开会", "2026-10-27T10:00:00", "2026-10-27T11:00:00"],
  ["明天下午3点到4点面试", "面试", "2026-10-28T15:00:00", "2026-10-28T16:00:00"],
  ["后天晚上七点半到八点半看书", "看书", "2026-10-29T19:30:00", "2026-10-29T20:30:00"],
  ["大后天15:15-16:45复习", "复习", "2026-10-30T15:15:00", "2026-10-30T16:45:00"],
  ["2026-11-02 09:00–10:00晨会", "晨会", "2026-11-02T09:00:00", "2026-11-02T10:00:00"],
  ["2026年11月3日 下午两点到三点健身", "健身", "2026-11-03T14:00:00", "2026-11-03T15:00:00"],
  ["10月29日 08:00到09:00散步", "散步", "2026-10-29T08:00:00", "2026-10-29T09:00:00"],
  ["下周三下午四点到五点读书", "读书", "2026-11-04T16:00:00", "2026-11-04T17:00:00"],
  ["周五下午三点到四点面试", "面试", "2026-10-30T15:00:00", "2026-10-30T16:00:00"],
  ["今晚七点到八点做饭", "做饭", "2026-10-27T19:00:00", "2026-10-27T20:00:00"],
  ["明早八点到九点跑步", "跑步", "2026-10-28T08:00:00", "2026-10-28T09:00:00"],
  ["明天十五点到十六点写作", "写作", "2026-10-28T15:00:00", "2026-10-28T16:00:00"],
  ["明天上午九点一刻到十点三刻学习", "学习", "2026-10-28T09:15:00", "2026-10-28T10:45:00"],
  ["明天晚上七点跑步四十五分钟", "跑步", "2026-10-28T19:00:00", "2026-10-28T19:45:00"],
  ["明天凌晨一点到两点值班", "值班", "2026-10-28T01:00:00", "2026-10-28T02:00:00"],
  ["明天中午十二点到下午一点吃饭", "吃饭", "2026-10-28T12:00:00", "2026-10-28T13:00:00"],
  ["明天下午三点30分到四点45分讨论", "讨论", "2026-10-28T15:30:00", "2026-10-28T16:45:00"],
  ["明天晚上19:00到20:00练琴", "练琴", "2026-10-28T19:00:00", "2026-10-28T20:00:00"],
  ["2028-02-29 18:00到19:00庆祝", "庆祝", "2028-02-29T18:00:00", "2028-02-29T19:00:00"],
  ["明天 00:00到01:00值班", "值班", "2026-10-28T00:00:00", "2026-10-28T01:00:00"],
] as const;
for (const [text, title, start, end] of clearCases) {
  test(`Chinese acceptance sample: ${text}`, () => {
    const result = parseWithRules(text, NOW);
    assert.equal(result.needs_clarification, false, JSON.stringify(result.questions));
    assert.equal(result.events.length, 1);
    assert.deepEqual([result.events[0].title, result.events[0].start_local, result.events[0].end_local], [title, start, end]);
  });
}

for (const text of ["周五三点面试", "下周找时间健身", "每周一下午三点到四点复习", "明天全天旅游", "2026-02-30 15:00到16:00开会", "明天25:00到26:00开会", "明天15:75到16:00开会", "明天15:00到14:00开会", "明天下午三点到四点", "明天东京时间15:00到16:00开会"]) {
  test(`needs clarification instead of guessing: ${text}`, () => {
    const result = parseWithRules(text, NOW);
    assert.equal(result.needs_clarification, true);
    assert.equal(result.events.length, 0);
    assert.ok(result.questions.length > 0);
  });
}

test("missing end and date are visibly assumed instead of silent defaults", () => {
  const result = parseWithRules("下午三点读书", NOW);
  assert.equal(result.events[0].end_local, "2026-10-27T16:00:00");
  assert.equal(result.events[0].time_source, "assumed");
  assert.equal(result.events[0].assumptions.length, 2);
});

test("five-item limit and length guard", () => {
  const text = Array.from({ length: 6 }, () => "明天下午三点到四点面试").join("，");
  assert.equal(parseWithRules(text, NOW).needs_clarification, true);
  assert.throws(() => parseWithRules("字".repeat(2001), NOW));
  assert.throws(() => parseWithRules("", NOW));
});

test("Shanghai midnight is computed independently of execution host timezone", () => {
  const result = parseWithRules("明天上午九点到十点开会", new Date("2026-10-27T17:30:00Z"));
  assert.equal(result.events[0].start_local, "2026-10-29T09:00:00");
  assert.equal(localToIso("2026-10-28T15:30"), "2026-10-28T07:30:00.000Z");
  assert.equal(isoToLocal("2026-10-28T07:30:00.000Z"), "2026-10-28T15:30:00");
  assert.equal(validateLocal("2026-02-29T15:00:00"), false);
  assert.equal(validateLocal("2028-02-29T15:00:00"), true);
  assert.equal(validateLocal("2026-01-01T24:00:00"), false);
  assert.equal(validateLocal("1991-07-01T15:00:00"), false);
  assert.equal(validateLocal("2000-01-01T00:00:00"), true);
  assert.throws(() => localToIso("2026-04-31T15:00"));
});

test("half-open conflicts allow adjacent schedules", () => {
  assert.equal(overlaps("2026-10-28T07:00Z", "2026-10-28T08:00Z", "2026-10-28T08:00Z", "2026-10-28T09:00Z"), false);
  assert.equal(overlaps("2026-10-28T07:00Z", "2026-10-28T08:00Z", "2026-10-28T07:30Z", "2026-10-28T08:30Z"), true);
});

test("schemas reject invalid durations, owner injection, unknown fields and empty patches", () => {
  assert.equal(eventDraftSchema.safeParse({ ...sample, end_local: "2026-10-28T14:00:00" }).success, false);
  assert.equal(eventDraftSchema.safeParse({ ...sample, end_local: "2026-10-30T16:00:00" }).success, false);
  assert.equal(eventDraftSchema.safeParse({ ...sample, user_id: "other-user" }).success, false);
  assert.equal(eventDraftSchema.safeParse({ ...sample, time_source: "assumed" }).success, false);
  assert.equal(commitSchema.safeParse({ request_id: "bad", events: [sample] }).success, false);
  assert.equal(patchEventSchema.safeParse({ base_version: 1 }).success, false);
  assert.equal(patchEventSchema.safeParse({ base_version: 0, title: "new" }).success, false);
  assert.equal(patchEventSchema.safeParse({ base_version: 1, title: "new", user_id: "other" }).success, false);
});

test("parser warns about past time, busy intervals and draft clashes", async () => {
  const oldKey = process.env.AI_API_KEY;
  delete process.env.AI_API_KEY;
  try {
    const busy: ScheduleEvent = { id: "busy", title: "private title not sent to AI", start_at: "2026-10-28T07:30:00Z", end_at: "2026-10-28T08:30:00Z", timezone: "Asia/Shanghai", status: "planned", version: 1, deleted_at: null, created_at: NOW.toISOString(), updated_at: NOW.toISOString() };
    const result = await parseText("今天上午八点到九点散步，明天下午三点到四点面试，下午三点半到四点半读书", NOW, [busy]);
    assert.equal(result.events.length, 3);
    assert.ok(result.warnings.some((message) => message.includes("已经过去")));
    assert.ok(result.warnings.some((message) => message.includes("已有日程")));
    assert.ok(result.warnings.some((message) => message.includes("草稿")));
  } finally { if (oldKey === undefined) delete process.env.AI_API_KEY; else process.env.AI_API_KEY = oldKey; }
});

test("model adapter validates output and never substitutes rule success for API errors", async () => {
  const oldFetch = globalThis.fetch;
  const oldKey = process.env.AI_API_KEY;
  process.env.AI_API_KEY = "test-placeholder-never-a-real-key";
  let lastBody: { messages: { content: string }[] } | undefined;
  const setContent = (content: unknown) => {
    globalThis.fetch = (async (_input: unknown, init?: RequestInit) => {
      lastBody = JSON.parse(String(init?.body));
      return new Response(JSON.stringify({ choices: [{ message: { content } }] }), { status: 200 });
    }) as typeof fetch;
  };
  try {
    setContent(JSON.stringify({ intent: "create_events", needs_clarification: false, questions: [], events: [sample] }));
    const result = await parseText("明天下午三点到四点面试", NOW);
    assert.equal(result.provider, "model");
    assert.deepEqual(result.events, [sample]);
    assert.ok(lastBody?.messages[1].content.includes("2026-10-27T10:00:00"));
    for (const content of ["", "not json", JSON.stringify({ needs_clarification: false, questions: [], events: [{ ...sample, end_local: "2026-02-30T00:00" }] }), JSON.stringify({ needs_clarification: true, questions: [], events: [] })]) {
      setContent(content);
      await assert.rejects(parseText("明天下午三点到四点面试", NOW));
    }
    globalThis.fetch = (async () => new Response("upstream secret error never exposed", { status: 500 })) as typeof fetch;
    await assert.rejects(parseText("明天下午三点到四点面试", NOW), /HTTP 500/);
    // Semantic limits are checked even when a model could confidently invent a time.
    globalThis.fetch = (async () => { throw new Error("must not call model for ambiguous or unsupported input"); }) as typeof fetch;
    assert.equal((await parseText("周五三点面试", NOW)).needs_clarification, true);
    assert.equal((await parseText("每周一下午三点面试", NOW)).needs_clarification, true);
  } finally {
    globalThis.fetch = oldFetch;
    if (oldKey === undefined) delete process.env.AI_API_KEY; else process.env.AI_API_KEY = oldKey;
  }
});
