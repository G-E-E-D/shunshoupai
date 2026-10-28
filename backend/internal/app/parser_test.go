package app

import (
	"context"
	"strings"
	"testing"
	"time"
)

func TestRulesParserParity(t *testing.T) {
	now, _ := time.Parse(time.RFC3339, "2026-10-27T02:00:00Z")
	cases := [][4]string{
		{"今天上午十点到十一点开会", "开会", "2026-10-27T10:00:00", "2026-10-27T11:00:00"},
		{"明天下午3点到4点面试", "面试", "2026-10-28T15:00:00", "2026-10-28T16:00:00"},
		{"后天晚上七点半到八点半看书", "看书", "2026-10-29T19:30:00", "2026-10-29T20:30:00"},
		{"大后天15:15-16:45复习", "复习", "2026-10-30T15:15:00", "2026-10-30T16:45:00"},
		{"2026-11-02 09:00–10:00晨会", "晨会", "2026-11-02T09:00:00", "2026-11-02T10:00:00"},
		{"2026年11月3日 下午两点到三点健身", "健身", "2026-11-03T14:00:00", "2026-11-03T15:00:00"},
		{"10月29日 08:00到09:00散步", "散步", "2026-10-29T08:00:00", "2026-10-29T09:00:00"},
		{"下周三下午四点到五点读书", "读书", "2026-11-04T16:00:00", "2026-11-04T17:00:00"},
		{"周五下午三点到四点面试", "面试", "2026-10-30T15:00:00", "2026-10-30T16:00:00"},
		{"今晚七点到八点做饭", "做饭", "2026-10-27T19:00:00", "2026-10-27T20:00:00"},
		{"明早八点到九点跑步", "跑步", "2026-10-28T08:00:00", "2026-10-28T09:00:00"},
		{"明天十五点到十六点写作", "写作", "2026-10-28T15:00:00", "2026-10-28T16:00:00"},
		{"明天上午九点一刻到十点三刻学习", "学习", "2026-10-28T09:15:00", "2026-10-28T10:45:00"},
		{"明天晚上七点跑步四十五分钟", "跑步", "2026-10-28T19:00:00", "2026-10-28T19:45:00"},
		{"明天凌晨一点到两点值班", "值班", "2026-10-28T01:00:00", "2026-10-28T02:00:00"},
		{"明天中午十二点到下午一点吃饭", "吃饭", "2026-10-28T12:00:00", "2026-10-28T13:00:00"},
		{"明天下午三点30分到四点45分讨论", "讨论", "2026-10-28T15:30:00", "2026-10-28T16:45:00"},
		{"明天晚上19:00到20:00练琴", "练琴", "2026-10-28T19:00:00", "2026-10-28T20:00:00"},
		{"2028-02-29 18:00到19:00庆祝", "庆祝", "2028-02-29T18:00:00", "2028-02-29T19:00:00"},
		{"明天 00:00到01:00值班", "值班", "2026-10-28T00:00:00", "2026-10-28T01:00:00"},
	}
	for _, v := range cases {
		t.Run(v[0], func(t *testing.T) {
			r, e := parseWithRules(v[0], now)
			if e != nil || r.NeedsClarification || len(r.Events) != 1 {
				t.Fatalf("%+v %v", r, e)
			}
			d := r.Events[0]
			if d.Title != v[1] || d.StartLocal != v[2] || d.EndLocal != v[3] {
				t.Fatalf("%+v", d)
			}
		})
	}
	r, e := parseWithRules("明天下午三点到四点面试，今天晚上七点到八点改简历，八点到九点准备面试。", now)
	if e != nil || r.NeedsClarification || len(r.Events) != 3 || r.Events[2].StartLocal != "2026-10-27T20:00:00" {
		t.Fatalf("%+v %v", r, e)
	}
	assumed, _ := parseWithRules("下午三点读书", now)
	if len(assumed.Events) != 1 || len(assumed.Events[0].Assumptions) != 2 || assumed.Events[0].TimeSource != "assumed" {
		t.Fatal(assumed)
	}
}
func TestRulesParserRefusesAmbiguousAndInvalidDates(t *testing.T) {
	now, _ := time.Parse(time.RFC3339, "2026-10-27T02:00:00Z")
	for _, v := range []string{"周五三点面试", "下周找时间健身", "每周一下午三点到四点复习", "明天全天旅游", "2026-02-30 15:00到16:00开会", "明天25:00到26:00开会", "明天15:75到16:00开会", "明天15:00到14:00开会", "明天下午三点到四点", "明天东京时间15:00到16:00开会", "下个月1号下午三点到四点面试"} {
		r, e := parseWithRules(v, now)
		if e != nil || !r.NeedsClarification || len(r.Events) != 0 {
			t.Fatalf("%s %+v %v", v, r, e)
		}
	}
	for _, v := range []string{"", strings.Repeat("字", 2001)} {
		if _, e := parseWithRules(v, now); e == nil {
			t.Fatal("accepted invalid text")
		}
	}
	midnight, _ := time.Parse(time.RFC3339, "2026-10-27T17:30:00Z")
	r, _ := parseWithRules("明天上午九点到十点开会", midnight)
	if r.Events[0].StartLocal != "2026-10-29T09:00:00" {
		t.Fatal(r)
	}
	r, e := parseText(context.Background(), "明天下午三点到四点面试，下午三点半到四点半读书", now, []busyInterval{{now.Add(30 * time.Hour), now.Add(31 * time.Hour)}}, nil)
	if e != nil || len(r.Warnings) == 0 {
		t.Fatal(r, e)
	}
}
