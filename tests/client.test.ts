import assert from "node:assert/strict";
import { test } from "node:test";
import { moveEventStart, requestId, touchesWindow } from "../src/lib/client";
import { commitSchema } from "../src/lib/schemas";
import type { EventDraft, ScheduleEvent } from "../src/lib/types";

const draft: EventDraft = {
  title: "整理面试材料",
  start_local: "2026-10-28T19:00:00",
  end_local: "2026-10-28T20:00:00",
  timezone: "Asia/Shanghai",
  time_source: "explicit",
  assumptions: [],
};

function withCrypto(crypto: Partial<Crypto>, run: () => void) {
  const descriptor = Object.getOwnPropertyDescriptor(globalThis, "crypto");
  Object.defineProperty(globalThis, "crypto", { value: crypto, configurable: true });
  try {
    run();
  } finally {
    if (descriptor) Object.defineProperty(globalThis, "crypto", descriptor);
    else Reflect.deleteProperty(globalThis, "crypto");
  }
}

test("plain HTTP LAN can create valid, distinct commit IDs without randomUUID", () => {
  const realCrypto = globalThis.crypto;
  withCrypto({ getRandomValues: realCrypto.getRandomValues.bind(realCrypto) }, () => {
    assert.equal(typeof globalThis.crypto.randomUUID, "undefined");
    const ids = Array.from({ length: 128 }, () => requestId());
    assert.equal(new Set(ids).size, ids.length, "separate saves must not reuse an idempotency key");
    for (const id of ids) {
      assert.match(id, /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i);
      assert.equal(commitSchema.safeParse({ request_id: id, events: [draft] }).success, true, "LAN-generated keys must pass the real server commit schema");
    }
  });
});

test("secure browser uses its native UUID implementation", () => {
  const expected = "8371d4d5-0cec-4335-ad0b-5ecc5ae8800b";
  withCrypto({ randomUUID: () => expected }, () => {
    assert.equal(requestId(), expected);
  });
});

function event(start: string, end: string): ScheduleEvent {
  return {
    id: "one-overnight-event",
    title: "夜间值班",
    start_at: start,
    end_at: end,
    timezone: "Asia/Shanghai",
    status: "planned",
    version: 1,
    created_at: "2026-10-27T02:00:00.000Z",
    updated_at: "2026-10-27T02:00:00.000Z",
    deleted_at: null,
  };
}

test("overnight schedule remains visible on both local calendar days", () => {
  // UTC storage dates differ from wall-clock dates: Shanghai Oct 28 23:30 -> Oct 29 00:30.
  const overnight = event("2026-10-28T15:30:00.000Z", "2026-10-28T16:30:00.000Z");
  assert.equal(touchesWindow(overnight, "2026-10-28"), true);
  assert.equal(touchesWindow(overnight, "2026-10-29"), true);
  assert.equal(touchesWindow(overnight, "2026-10-27"), false);
  assert.equal(touchesWindow(overnight, "2026-10-30"), false);
});

test("midnight boundaries do not create phantom events on adjacent days", () => {
  const endingAtMidnight = event("2026-10-28T15:00:00.000Z", "2026-10-28T16:00:00.000Z");
  const startingAtMidnight = event("2026-10-28T16:00:00.000Z", "2026-10-28T17:00:00.000Z");
  assert.equal(touchesWindow(endingAtMidnight, "2026-10-28"), true);
  assert.equal(touchesWindow(endingAtMidnight, "2026-10-29"), false);
  assert.equal(touchesWindow(startingAtMidnight, "2026-10-28"), false);
  assert.equal(touchesWindow(startingAtMidnight, "2026-10-29"), true);
});

test("seven-day view includes carry-in events but excludes its exact upper boundary", () => {
  // Week window Oct 26 00:00 to Nov 2 00:00 Shanghai, including a carry-in event.
  const carryIn = event("2026-10-25T15:30:00Z", "2026-10-25T16:30:00Z");
  const before = event("2026-10-25T15:00:00Z", "2026-10-25T16:00:00Z");
  const atUpperBoundary = event("2026-11-01T16:00:00Z", "2026-11-01T17:00:00Z");
  const lastNight = event("2026-11-01T15:30:00Z", "2026-11-01T16:30:00Z");
  assert.equal(touchesWindow(carryIn, "2026-10-26", "2026-11-02"), true);
  assert.equal(touchesWindow(before, "2026-10-26", "2026-11-02"), false);
  assert.equal(touchesWindow(atUpperBoundary, "2026-10-26", "2026-11-02"), false);
  assert.equal(touchesWindow(lastNight, "2026-10-26", "2026-11-02"), true);
});

test("overnight visibility works across year changes independently of host timezone", () => {
  const yearBoundary = event("2026-12-31T15:30:00Z", "2026-12-31T16:30:00Z");
  assert.equal(touchesWindow(yearBoundary, "2026-12-31"), true);
  assert.equal(touchesWindow(yearBoundary, "2027-01-01"), true);
  assert.equal(touchesWindow(yearBoundary, "2027-01-02"), false);
});

test("moving an event preserves its duration across dates and year boundaries", () => {
  assert.equal(moveEventStart("2026-10-27T09:00", "2026-10-27T10:30", "2026-12-31T23:30"), "2027-01-01T01:00");
  assert.equal(moveEventStart("2026-10-27T23:00", "2026-10-28T01:00", "2026-10-29T10:00"), "2026-10-29T12:00");
});

test("an incomplete or invalid time edit does not erase the chosen end time", () => {
  assert.equal(moveEventStart("2026-10-27T09:00", "2026-10-27T10:30", ""), "2026-10-27T10:30");
  assert.equal(moveEventStart("", "2026-10-27T10:30", "2026-10-28T09:00"), "2026-10-27T10:30");
  assert.equal(moveEventStart("2026-10-27T11:00", "2026-10-27T10:30", "2026-10-28T09:00"), "2026-10-27T10:30");
});
