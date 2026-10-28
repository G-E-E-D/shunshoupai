package com.shunshoupai.app;

import static org.junit.Assert.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.time.Instant;
import java.util.Collections;

@RunWith(AndroidJUnit4.class)
public class LocalPlanningRulesInstrumentedTest {
    private static final long NOW=Instant.parse("2026-10-27T02:00:00Z").toEpochMilli();
    private static JSONObject rules(String text)throws Exception{return LocalPlanningParser.rules(text,NOW);}
    private static JSONObject draft()throws Exception{return new JSONObject().put("title","面试").put("start_local","2026-10-28T15:00:00").put("end_local","2026-10-28T16:00:00").put("timezone","Asia/Shanghai").put("time_source","explicit").put("assumptions",new JSONArray());}
    private static JSONObject model(JSONObject event)throws Exception{return new JSONObject().put("intent","create_events").put("needs_clarification",false).put("questions",new JSONArray()).put("events",new JSONArray().put(event));}
    private static void rejectsModel(String text)throws Exception {try{LocalPlanningParser.model(text);fail("Invalid model output must not become a draft");}catch(LocalPlanningException expected){assertEquals("MODEL_INVALID_RESPONSE",expected.code);}}

    @Test public void chineseAcceptanceSamplesMatchDesktopParserDatesAndTimes()throws Exception {
        String[][] samples={
            {"今天上午十点到十一点开会","开会","2026-10-27T10:00:00","2026-10-27T11:00:00"},
            {"明天下午3点到4点面试","面试","2026-10-28T15:00:00","2026-10-28T16:00:00"},
            {"后天晚上七点半到八点半看书","看书","2026-10-29T19:30:00","2026-10-29T20:30:00"},
            {"大后天15:15-16:45复习","复习","2026-10-30T15:15:00","2026-10-30T16:45:00"},
            {"2026-11-02 09:00–10:00晨会","晨会","2026-11-02T09:00:00","2026-11-02T10:00:00"},
            {"2026年11月3日 下午两点到三点健身","健身","2026-11-03T14:00:00","2026-11-03T15:00:00"},
            {"10月29日 08:00到09:00散步","散步","2026-10-29T08:00:00","2026-10-29T09:00:00"},
            {"下周三下午四点到五点读书","读书","2026-11-04T16:00:00","2026-11-04T17:00:00"},
            {"周五下午三点到四点面试","面试","2026-10-30T15:00:00","2026-10-30T16:00:00"},
            {"今晚七点到八点做饭","做饭","2026-10-27T19:00:00","2026-10-27T20:00:00"},
            {"明早八点到九点跑步","跑步","2026-10-28T08:00:00","2026-10-28T09:00:00"},
            {"明天十五点到十六点写作","写作","2026-10-28T15:00:00","2026-10-28T16:00:00"},
            {"明天上午九点一刻到十点三刻学习","学习","2026-10-28T09:15:00","2026-10-28T10:45:00"},
            {"明天晚上七点跑步四十五分钟","跑步","2026-10-28T19:00:00","2026-10-28T19:45:00"},
            {"明天凌晨一点到两点值班","值班","2026-10-28T01:00:00","2026-10-28T02:00:00"},
            {"明天中午十二点到下午一点吃饭","吃饭","2026-10-28T12:00:00","2026-10-28T13:00:00"},
            {"明天下午三点30分到四点45分讨论","讨论","2026-10-28T15:30:00","2026-10-28T16:45:00"},
            {"明天晚上19:00到20:00练琴","练琴","2026-10-28T19:00:00","2026-10-28T20:00:00"},
            {"2028-02-29 18:00到19:00庆祝","庆祝","2028-02-29T18:00:00","2028-02-29T19:00:00"},
            {"明天 00:00到01:00值班","值班","2026-10-28T00:00:00","2026-10-28T01:00:00"}
        };
        for(String[] sample:samples){JSONObject result=rules(sample[0]);assertFalse(sample[0]+result,result.getBoolean("needs_clarification"));assertEquals(1,result.getJSONArray("events").length());JSONObject event=result.getJSONArray("events").getJSONObject(0);assertEquals(sample[0],sample[1],event.getString("title"));assertEquals(sample[0],sample[2],event.getString("start_local"));assertEquals(sample[0],sample[3],event.getString("end_local"));assertEquals("rules",result.getString("provider"));}
    }

    @Test public void ambiguousInvalidRecurringAndMutationRequestsAskInsteadOfGuessing()throws Exception {
        for(String text:new String[]{"周五三点面试","下周找时间健身","每周一下午三点到四点复习","明天全天旅游","2026-02-30 15:00到16:00开会","明天25:00到26:00开会","明天15:75到16:00开会","明天15:00到14:00开会","明天下午三点到四点","明天东京时间15:00到16:00开会","下个月1号下午三点到四点面试","删除明天下午三点面试"}){
            JSONObject result=rules(text);assertTrue(text,result.getBoolean("needs_clarification"));assertEquals(text,0,result.getJSONArray("events").length());assertTrue(result.getJSONArray("questions").length()>0);
        }
    }

    @Test public void multipleClausesInheritOnlyThePreviousSuccessfulDateAndPeriod()throws Exception {
        JSONArray events=rules("明天下午三点到四点面试，今天晚上七点到八点改简历，八点到九点准备面试。").getJSONArray("events");
        assertEquals(3,events.length());assertEquals("2026-10-28T15:00:00",events.getJSONObject(0).getString("start_local"));assertEquals("2026-10-27T19:00:00",events.getJSONObject(1).getString("start_local"));assertEquals("2026-10-27T20:00:00",events.getJSONObject(2).getString("start_local"));
        assertTrue(rules("今天下午三点到四点面试，明天三点到四点学习").getBoolean("needs_clarification"));
    }

    @Test public void assumedDateAndDurationRemainVisibleAndCrossMidnightDurationIsValid()throws Exception {
        JSONObject result=rules("下午三点读书"),event=result.getJSONArray("events").getJSONObject(0);assertEquals("assumed",event.getString("time_source"));assertEquals(2,event.getJSONArray("assumptions").length());assertEquals("2026-10-27T16:00:00",event.getString("end_local"));
        LocalPlanningParser.warnings(result,NOW,Collections.emptyList());assertEquals(2,result.getJSONArray("warnings").length());
        assertEquals("2026-10-29T01:30:00",rules("明天23:30值班持续两小时").getJSONArray("events").getJSONObject(0).getString("end_local"));
    }

    @Test public void yearsWeekPrefixesAndShanghaiMidnightDoNotDependOnHostTimezone()throws Exception {
        assertEquals("2026-11-04T15:00:00",rules("下星期三下午三点到四点面试").getJSONArray("events").getJSONObject(0).getString("start_local"));
        assertEquals("2026-11-11T15:00:00",rules("下下周三下午三点到四点面试").getJSONArray("events").getJSONObject(0).getString("start_local"));
        assertEquals("2027-11-03T15:00:00",rules("明年11月3日下午三点到四点面试").getJSONArray("events").getJSONObject(0).getString("start_local"));
        assertEquals("2026-10-29T09:00:00",LocalPlanningParser.rules("明天上午九点到十点开会",Instant.parse("2026-10-27T17:30:00Z").toEpochMilli()).getJSONArray("events").getJSONObject(0).getString("start_local"));
    }

    @Test public void limitsAndMalformedModelJsonCannotCreateDrafts()throws Exception {
        assertTrue(rules(String.join("，",Collections.nCopies(6,"明天下午三点到四点面试"))).getBoolean("needs_clarification"));
        for(String text:new String[]{"",String.join("",Collections.nCopies(2001,"字"))})try{rules(text);fail();}catch(LocalPlanningException expected){assertEquals("VALIDATION_ERROR",expected.code);}
        for(String invalid:new String[]{"not json","{events:[]}","{'events':[]}","{\"needs_clarification\":true,\"needs_clarification\":false,\"events\":[],\"questions\":[]}",model(draft()).toString()+" {}",model(draft()).put("unknown","injected").toString(),model(draft().put("end_local","2026-02-30T16:00:00")).toString(),model(draft().put("owner","other")).toString(),model(draft().put("time_source","assumed")).toString()})rejectsModel(invalid);
        JSONObject valid=LocalPlanningParser.model(model(draft()).toString());assertEquals("model",valid.getString("provider"));assertEquals(1,valid.getJSONArray("events").length());
    }

    @Test public void overlapWarningsIgnoreCompletedAndDeletedEventsAndAllowAdjacentTimes()throws Exception {
        JSONObject busy=new JSONObject().put("title","不应发送给模型的标题").put("start_local","2026-10-28T15:30:00").put("end_local","2026-10-28T16:30:00").put("status","planned").put("deleted",false);
        JSONObject result=LocalPlanningParser.warnings(rules("明天下午三点到四点面试"),NOW,Collections.singletonList(busy));assertTrue(result.getJSONArray("warnings").toString().contains("已有日程"));
        busy.put("status","completed");assertEquals(0,LocalPlanningParser.warnings(rules("明天下午三点到四点面试"),NOW,Collections.singletonList(busy)).getJSONArray("warnings").length());
        busy.put("status","planned").put("start_local","2026-10-28T16:00:00");assertEquals(0,LocalPlanningParser.warnings(rules("明天下午三点到四点面试"),NOW,Collections.singletonList(busy)).getJSONArray("warnings").length());
    }

    @Test public void modelAddressValidationRequiresExplicitLocalPermissionAndAlwaysRejectsMetadata()throws Exception {
        assertEquals("https://example.com/v1",LocalModelTransport.normalizeBase("https://example.com/v1/",false));
        assertEquals("http://127.0.0.1:11434/v1",LocalModelTransport.normalizeBase("http://127.0.0.1:11434/v1",true));
        for(String address:new String[]{"http://127.0.0.1/v1","https://192.168.1.1/v1"})try{LocalModelTransport.normalizeBase(address,false);fail("Private models require explicit permission");}catch(LocalPlanningException expected){assertEquals("MODEL_ADDRESS_BLOCKED",expected.code);}
        for(String address:new String[]{"http://169.254.169.254","http://0.0.0.0","http://224.0.0.1","https://[fe80::1]","http://8.8.8.8","http://127.1","http://0177.0.0.1","http://0x7f000001","https://user:password@example.com/v1","https://example.com/v1?api_key=secret","https://example.com/v1#token","https://example.com/v1/chat/completions"})try{LocalModelTransport.normalizeBase(address,true);fail("Unsafe or ambiguous address accepted: "+address);}catch(LocalPlanningException expected){assertFalse(expected.getMessage().contains("secret"));assertFalse(expected.getMessage().contains("password"));}
    }
}
