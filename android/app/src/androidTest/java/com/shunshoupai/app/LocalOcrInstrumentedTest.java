package com.shunshoupai.app;

import static org.junit.Assert.*;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.os.Looper;
import android.os.SystemClock;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.time.Instant;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(AndroidJUnit4.class)
public class LocalOcrInstrumentedTest {
    private Context context(){return ApplicationProvider.getApplicationContext();}
    private Bitmap textImage(String... lines){
        Bitmap bitmap=Bitmap.createBitmap(1600,Math.max(400,lines.length*180+100),Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(bitmap);canvas.drawColor(Color.WHITE);
        Paint text=new Paint(Paint.ANTI_ALIAS_FLAG);text.setColor(Color.BLACK);text.setTextSize(72);text.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));
        for(int i=0;i<lines.length;i++)canvas.drawText(lines[i],70,130+i*180,text);return bitmap;
    }
    private static final class Outcome {LocalOcrEngine.Result result;String code;}
    private Outcome recognize(Bitmap bitmap,int rotation,boolean recycleCaller)throws Exception {
        LocalOcrEngine engine=new LocalOcrEngine(context());Outcome outcome=new Outcome();CountDownLatch done=new CountDownLatch(1);AtomicBoolean onMain=new AtomicBoolean();
        try{
            engine.recognize(bitmap,rotation,new LocalOcrEngine.Callback(){public void onResult(LocalOcrEngine.Result result){onMain.set(Looper.myLooper()==Looper.getMainLooper());outcome.result=result;done.countDown();}public void onError(String code,String message){onMain.set(Looper.myLooper()==Looper.getMainLooper());outcome.code=code;done.countDown();}});
            if(recycleCaller&&bitmap!=null&&!bitmap.isRecycled())bitmap.recycle();
            assertTrue("Bundled recognition must finish without a model download",done.await(30,TimeUnit.SECONDS));assertTrue("OCR callbacks belong to the main thread",onMain.get());return outcome;
        }finally{engine.close();if(bitmap!=null&&!bitmap.isRecycled())bitmap.recycle();}
    }

    @Test public void bundledChineseBitmapBecomesAnOfflineEditableScheduleDraftWithoutPairing()throws Exception {
        if("true".equals(InstrumentationRegistry.getArguments().getString("require_ocr_offline"))){ConnectivityManager manager=(ConnectivityManager)context().getSystemService(Context.CONNECTIVITY_SERVICE);assertNull("This run must prove first-use OCR without an active network",manager.getActiveNetwork());}
        Outcome output=recognize(textImage("明天上午9点开会一小时"),0,true);assertNull(output.code);assertNotNull(output.result);assertTrue(output.result.text,output.result.text.contains("开会"));
        LocalPlanningEngine planner=new LocalPlanningEngine(context(),"ocr-rule-test-"+UUID.randomUUID());
        try{JSONObject parsed=planner.parse(output.result.text,Instant.parse("2026-10-28T02:00:00Z").toEpochMilli(),Collections.emptyList(),true);
            assertEquals("rules",parsed.getString("source"));assertFalse(parsed.toString(),parsed.getBoolean("needs_clarification"));assertEquals(1,parsed.getJSONArray("drafts").length());JSONObject draft=parsed.getJSONArray("drafts").getJSONObject(0);
            assertEquals("开会",draft.getString("title"));assertEquals("2026-10-29T09:00:00",draft.getString("start_local"));assertEquals("2026-10-29T10:00:00",draft.getString("end_local"));
        }finally{planner.cancel();}
    }

    @Test public void bundledChineseRecognizerAlsoReadsEnglishAndKeepsReadingOrder()throws Exception {
        Outcome output=recognize(textImage("Meeting 15:00 - 16:00","Review 17:00 - 18:00"),0,true);
        assertNull(output.code);assertNotNull(output.result);assertTrue(output.result.text,output.result.text.contains("Meeting"));assertTrue(output.result.text,output.result.text.contains("Review"));assertTrue(output.result.text.indexOf("Meeting")<output.result.text.indexOf("Review"));assertTrue(output.result.lineCount>=2);
    }

    @Test public void blankInvalidAndOversizedImagesFailWithoutReturningPartialText()throws Exception {
        assertEquals("OCR_INVALID_IMAGE",recognize(null,0,false).code);
        Bitmap recycled=Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888);recycled.recycle();assertEquals("OCR_INVALID_IMAGE",recognize(recycled,0,false).code);
        assertEquals("OCR_INVALID_ROTATION",recognize(textImage("会议"),45,false).code);
        assertEquals("OCR_IMAGE_TOO_LARGE",recognize(Bitmap.createBitmap(LocalOcrEngine.MAX_SIDE+1,1,Bitmap.Config.ARGB_8888),0,false).code);
        assertEquals("OCR_IMAGE_TOO_LARGE",recognize(Bitmap.createBitmap(4000,2001,Bitmap.Config.ARGB_8888),0,false).code);
        Bitmap blank=Bitmap.createBitmap(200,200,Bitmap.Config.ARGB_8888);blank.eraseColor(Color.WHITE);assertEquals("OCR_EMPTY_TEXT",recognize(blank,0,false).code);
    }

    @Test public void cancellationSuppressesQueuedCallbacksAndNeverRecyclesTheCallersImage()throws Exception {
        Bitmap source=textImage("明天上午9点开会一小时");LocalOcrEngine engine=new LocalOcrEngine(context());AtomicInteger callbacks=new AtomicInteger();
        try{
            InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{engine.recognize(source,0,new LocalOcrEngine.Callback(){public void onResult(LocalOcrEngine.Result result){callbacks.incrementAndGet();}public void onError(String code,String message){callbacks.incrementAndGet();}});engine.cancel();engine.close();});
            assertFalse("Engine only owns its copy",source.isRecycled());java.lang.reflect.Field field=LocalOcrEngine.class.getDeclaredField("current");field.setAccessible(true);long deadline=SystemClock.elapsedRealtime()+30000;boolean idle=false;
            while(SystemClock.elapsedRealtime()<deadline){synchronized(engine){idle=field.get(engine)==null;}if(idle)break;SystemClock.sleep(20);}
            assertTrue("Cancelled inference must eventually release its owned bitmap",idle);assertEquals(0,callbacks.get());
        }finally{engine.close();source.recycle();}
    }

    @Test public void chineseSpaceNormalizationNeverJoinsSeparateLinesOrEnglishWords() {
        assertEquals("明天上午9点开会一小时",LocalOcrEngine.normalizeLine("明 天 上 午 9 点 开 会 一 小 时"));
        assertEquals("Meeting 15:00 - 16:00",LocalOcrEngine.normalizeLine("Meeting 15 : 00 - 16 ： 00"));
        assertEquals("上午9点\n下午3点",LocalOcrEngine.normalizeLine("上午 9 点\n下午 3 点"));
    }
}
