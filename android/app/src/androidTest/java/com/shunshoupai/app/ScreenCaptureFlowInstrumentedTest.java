package com.shunshoupai.app;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inspector.WindowInspector;
import android.widget.EditText;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Real system consent and MediaProjection; the displayed source is a synthetic debug-only Activity. */
@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion=29)
public class ScreenCaptureFlowInstrumentedTest {
    private Context context;
    private ActivityScenario<MainActivity> main;
    private ActivityScenario<CaptureFixtureActivity> fixture;
    private Intent originalMainIntent;
    private boolean otherAppStarted;
    @Before public void prepare()throws Exception{
        android.accessibilityservice.AccessibilityServiceInfo accessibility=InstrumentationRegistry.getInstrumentation().getUiAutomation().getServiceInfo();accessibility.flags|=android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;InstrumentationRegistry.getInstrumentation().getUiAutomation().setServiceInfo(accessibility);
        context=ApplicationProvider.getApplicationContext();ScreenCaptureService.stop(context);SystemClock.sleep(200);CaptureGrantGate.stop();context.deleteDatabase("schedule-offline.sqlite");
        for(String name:new String[]{"connection","offline-editor","native-workbench","planning-drafts","local-model-settings-v1","ocr-text-inbox-v1"})context.getSharedPreferences(name,Context.MODE_PRIVATE).edit().clear().commit();
        shell("appops set "+context.getPackageName()+" SYSTEM_ALERT_WINDOW allow");if(Build.VERSION.SDK_INT>=33)shell("pm grant "+context.getPackageName()+" android.permission.POST_NOTIFICATIONS");
        main=ActivityScenario.launch(MainActivity.class);
        main.onActivity(activity->originalMainIntent=new Intent(activity.getIntent()));
    }
    @After public void close()throws Exception{
        try{
            ScreenCaptureService.stop(context);waitFor("capture stopped",()->!ScreenCaptureService.isRunning(),5000);shell("input keyevent KEYCODE_BACK");
            onMain(()->{java.util.Set<android.app.Activity> activities=new java.util.HashSet<>();for(androidx.test.runner.lifecycle.Stage stage:androidx.test.runner.lifecycle.Stage.values())activities.addAll(androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage));for(android.app.Activity activity:activities)if(!activity.isFinishing()){if(activity instanceof MainActivity&&originalMainIntent!=null)activity.setIntent(originalMainIntent);activity.finish();}});
            SystemClock.sleep(250);if(fixture!=null)fixture.close();if(main!=null)main.close();
        }finally{
            if(otherAppStarted)shell("am start -W -n com.shunshoupai.app.test/com.shunshoupai.app.CaptureOtherAppActivity --activity-clear-top --activity-single-top -a com.shunshoupai.test.FINISH_CAPTURE_FIXTURE");
            shell("settings put system accelerometer_rotation 1");shell("settings put system user_rotation 0");
        }
    }
    private void shell(String command)throws Exception{try(ParcelFileDescriptor descriptor=InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(command);java.io.InputStream input=new ParcelFileDescriptor.AutoCloseInputStream(descriptor)){byte[] buffer=new byte[1024];while(input.read(buffer)!=-1){}}}
    private View find(int id){for(View root:WindowInspector.getGlobalWindowViews()){View found=root.findViewById(id);if(found!=null)return found;}return null;}
    private void onMain(Runnable task){InstrumentationRegistry.getInstrumentation().runOnMainSync(task);}
    private boolean has(int id){AtomicReference<Boolean> result=new AtomicReference<>(false);onMain(()->result.set(find(id)!=null));return result.get();}
    private void click(int id){onMain(()->{View view=find(id);assertNotNull("Missing native control "+id,view);assertTrue(view.isEnabled());view.performClick();});}
    private void waitFor(String why,BooleanSupplier condition,long timeout){long end=SystemClock.elapsedRealtime()+timeout;while(SystemClock.elapsedRealtime()<end){if(condition.getAsBoolean())return;SystemClock.sleep(80);}assertTrue(why,condition.getAsBoolean());}
    private boolean clickText(String... labels){
        AtomicReference<Boolean> own=new AtomicReference<>(false);onMain(()->{for(View root:WindowInspector.getGlobalWindowViews())for(String label:labels){View button=findText(root,label);if(button!=null&&button.isShown()&&button.isClickable()){button.performClick();own.set(true);return;}}});if(own.get())return true;
        AccessibilityNodeInfo root=InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow();if(root==null)return false;
        for(String label:labels)for(AccessibilityNodeInfo node:root.findAccessibilityNodeInfosByText(label)){
            if(node.getText()==null||!label.equals(node.getText().toString())||!node.isVisibleToUser())continue;
            android.graphics.Rect bounds=new android.graphics.Rect();node.getBoundsInScreen(bounds);if(bounds.width()<2||bounds.height()<2)continue;
            long down=SystemClock.uptimeMillis();send(down,down,MotionEvent.ACTION_DOWN,bounds.exactCenterX(),bounds.exactCenterY());send(down,SystemClock.uptimeMillis(),MotionEvent.ACTION_UP,bounds.exactCenterX(),bounds.exactCenterY());return true;
        }return false;
    }
    private View findText(View root,String label){if(root instanceof TextView&&label.contentEquals(((TextView)root).getText()))return root;if(root instanceof ViewGroup){ViewGroup group=(ViewGroup)root;for(int i=0;i<group.getChildCount();i++){View found=findText(group.getChildAt(i),label);if(found!=null)return found;}}return null;}
    private String screenText(){StringBuilder text=new StringBuilder();append(InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow(),text);return text.toString();}
    private void append(AccessibilityNodeInfo node,StringBuilder out){if(node==null)return;if(node.getText()!=null)out.append(node.getText()).append('\n');for(int i=0;i<node.getChildCount();i++)append(node.getChild(i),out);}
    private void beginAndAllow(){
        click(R.id.offline_capture);waitFor("feature explanation",()->clickText("开启悬浮取字"),5000);
        acceptSystemMode(false);
        waitFor("System consent should start capture. UI: "+screenText(),ScreenCaptureService::isRunning,10000);
    }
    private AccessibilityNodeInfo findSystemId(String id){AccessibilityNodeInfo root=InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow();if(root==null)return null;for(AccessibilityNodeInfo node:root.findAccessibilityNodeInfosByViewId(id))if(node.isVisibleToUser())return node;return null;}
    private AccessibilityNodeInfo modeSelector(){AccessibilityNodeInfo node=findSystemId("com.android.systemui:id/screen_share_mode_spinner");return node!=null?node:findSystemId("com.android.systemui:id/screen_share_mode_options");}
    private boolean hasExactText(AccessibilityNodeInfo node,String[] labels){if(node==null)return false;for(String label:labels)if(node.getText()!=null&&label.contentEquals(node.getText()))return true;for(int i=0;i<node.getChildCount();i++)if(hasExactText(node.getChild(i),labels))return true;return false;}
    private void tap(AccessibilityNodeInfo node){assertNotNull(node);android.graphics.Rect bounds=new android.graphics.Rect();node.getBoundsInScreen(bounds);assertTrue(bounds.width()>1&&bounds.height()>1);long down=SystemClock.uptimeMillis();send(down,down,MotionEvent.ACTION_DOWN,bounds.exactCenterX(),bounds.exactCenterY());send(down,SystemClock.uptimeMillis(),MotionEvent.ACTION_UP,bounds.exactCenterX(),bounds.exactCenterY());}
    private void acceptSystemMode(boolean singleApp){
        String[] labels=singleApp?new String[]{"A single app","Single app","单个应用","Share one app"}:new String[]{"Entire screen","整个屏幕","Share entire screen"};
        if(Build.VERSION.SDK_INT>=34){
            waitFor("System sharing mode selector",()->modeSelector()!=null,10000);
            if(!hasExactText(modeSelector(),labels)){
                tap(modeSelector());
                waitFor("Sharing mode popup",()->{AccessibilityNodeInfo root=InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow();if(root==null)return false;for(AccessibilityNodeInfo node:root.findAccessibilityNodeInfosByViewId("android:id/text1"))if(node.isVisibleToUser()&&hasExactText(node,labels)){tap(node);return true;}return false;},5000);
            }
            // Seeing an option in the popup is not evidence that Spinner.onItemSelected ran.
            waitFor("Collapsed selector must confirm the selected sharing mode",()->modeSelector()!=null&&hasExactText(modeSelector(),labels)&&findSystemId("android:id/button1")!=null,5000);
        }
        waitFor("System consent button",()->findSystemId("android:id/button1")!=null,5000);SystemClock.sleep(200);tap(findSystemId("android:id/button1"));
    }
    private boolean chooseSyntheticApp(){AccessibilityNodeInfo root=InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow();if(root==null||!"com.android.systemui".contentEquals(root.getPackageName()))return false;for(AccessibilityNodeInfo label:root.findAccessibilityNodeInfosByText("OCR Test Screen")){if(!label.isVisibleToUser()||!"OCR Test Screen".contentEquals(label.getText()))continue;AccessibilityNodeInfo target=label;while(target!=null&&!target.isClickable())target=target.getParent();if(target!=null&&target.isEnabled()){tap(target);return true;}}return false;}
    private void showFixture(){fixture=ActivityScenario.launch(new Intent(context,CaptureFixtureActivity.class));waitFor("floating button outside the workbench",()->has(R.id.capture_bubble),7000);SystemClock.sleep(400); }
    private void selectTextRegion(){
        waitFor("floating capture action",()->has(R.id.capture_bubble),7000);click(R.id.capture_bubble);waitFor("frozen selection surface",()->{AtomicReference<Boolean> laidOut=new AtomicReference<>(false);onMain(()->{View view=find(R.id.capture_selection);laidOut.set(view!=null&&view.getWidth()>100&&view.getHeight()>100);});return laidOut.get();},7000);
        final float[] points=new float[4];onMain(()->{CaptureSelectionView view=(CaptureSelectionView)find(R.id.capture_selection);try{java.lang.reflect.Field bitmapField=CaptureSelectionView.class.getDeclaredField("bitmap");bitmapField.setAccessible(true);Bitmap bitmap=(Bitmap)bitmapField.get(view);RectF bounds=CaptureGeometry.fit(bitmap.getWidth(),bitmap.getHeight(),view.getWidth(),view.getHeight());int[] position=new int[2];view.getLocationOnScreen(position);points[0]=position[0]+bounds.left+6;points[1]=position[1]+bounds.top+bounds.height()*.12f;points[2]=position[0]+bounds.right-6;points[3]=position[1]+bounds.top+bounds.height()*.7f;}catch(Exception error){throw new AssertionError(error);}});
        long down=SystemClock.uptimeMillis();send(down,down,MotionEvent.ACTION_DOWN,points[0],points[1]);for(int step=1;step<=12;step++){SystemClock.sleep(16);float t=step/12f;send(down,SystemClock.uptimeMillis(),MotionEvent.ACTION_MOVE,points[0]+(points[2]-points[0])*t,points[1]+(points[3]-points[1])*t);}send(down,SystemClock.uptimeMillis(),MotionEvent.ACTION_UP,points[2],points[3]);
    }
    private void send(long down,long when,int action,float x,float y){MotionEvent event=MotionEvent.obtain(down,when,action,x,y,0);try{event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);assertTrue(InstrumentationRegistry.getInstrumentation().getUiAutomation().injectInputEvent(event,true));}finally{event.recycle();}}
    private void capture(String filename)throws Exception{InstrumentationRegistry.getInstrumentation().waitForIdleSync();SystemClock.sleep(500);Bitmap screenshot=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();assertNotNull(screenshot);try(FileOutputStream out=new FileOutputStream(new File(context.getExternalFilesDir(null),filename))){screenshot.compress(Bitmap.CompressFormat.PNG,100,out);}finally{screenshot.recycle();}
        if(!java.util.Arrays.asList("android-ocr-selection.png","android-ocr-review.png","android-ocr-drafts.png").contains(filename))throw new AssertionError("Unexpected evidence filename");
        shell("mkdir -p /data/local/tmp/shunshoupai-ocr");shell("cp /sdcard/Android/data/com.shunshoupai.app/files/"+filename+" /data/local/tmp/shunshoupai-ocr/"+filename);
    }
    @Test public void realConsentTwoCapturesLocalOcrAndConfirmedTextReachEditableDrafts()throws Exception{
        beginAndAllow();showFixture();
        selectTextRegion();capture("android-ocr-selection.png");waitFor("cancel action",()->clickText("取消本次"),3000);waitFor("button returns without new grant",()->has(R.id.capture_bubble),3000);
        selectTextRegion();click(R.id.capture_recognize);waitFor("on-device OCR review",()->has(R.id.capture_text),30000);
        onMain(()->{EditText text=(EditText)find(R.id.capture_text);assertTrue("Actual screen OCR should read the fixture",text.getText().toString().contains("开会"));text.setText("明天下午三点到四点开会");});capture("android-ocr-review.png");click(R.id.capture_to_drafts);
        waitFor("confirmed local text returns to native planner",()->has(R.id.planner_input),10000);waitFor("explicit confirmation creates editable proposal",()->{AtomicReference<Boolean> ready=new AtomicReference<>(false);onMain(()->{ViewGroup rows=(ViewGroup)find(R.id.planner_rows);ready.set(rows!=null&&rows.findViewWithTag("draft_title")!=null);});return ready.get();},15000);
        try(LocalScheduleStore store=new LocalScheduleStore(context)){assertTrue("OCR must never automatically commit a calendar event",store.list(store.primaryScope()).isEmpty());}
        capture("android-ocr-drafts.png");ScreenCaptureService.stop(context);waitFor("explicit stop ends grant",()->!ScreenCaptureService.isRunning()&&!has(R.id.capture_bubble),5000);
    }
    @Test public void denyingSystemCaptureDoesNotStartAServiceOrLoseExistingDrafts(){
        context.getSharedPreferences("planning-drafts",Context.MODE_PRIVATE).edit().putString("sentinel","unchanged").commit();click(R.id.offline_capture);waitFor("feature explanation",()->clickText("开启悬浮取字"),5000);
        waitFor("system denial",()->clickText("Cancel","取消","Don't allow","不允许"),10000);SystemClock.sleep(400);assertFalse(ScreenCaptureService.isRunning());assertFalse(has(R.id.capture_bubble));assertEquals("unchanged",context.getSharedPreferences("planning-drafts",Context.MODE_PRIVATE).getString("sentinel",null));
    }
    @Test public void arbitraryLauncherTextCannotBypassThePrivateConfirmedInbox(){
        main.onActivity(activity->{try{java.lang.reflect.Method handler=MainActivity.class.getDeclaredMethod("handleOcrIntent",Intent.class);handler.setAccessible(true);handler.invoke(activity,new Intent(context,MainActivity.class).setAction(ScreenCaptureService.ACTION_REVIEW).putExtra(ScreenCaptureService.EXTRA_OCR_ID,java.util.UUID.randomUUID().toString()).putExtra("text","明天下午三点开会"));}catch(Exception error){throw new AssertionError(error);}});
        assertFalse(has(R.id.planner_input));assertFalse(ScreenCaptureService.isRunning());
    }
    @Test public void rotationReusesTheSessionAndRevokedOverlayPermissionCleansUp()throws Exception{
        beginAndAllow();showFixture();selectTextRegion();Object originalDisplay=serviceField("display");
        shell("settings put system accelerometer_rotation 0");shell("settings put system user_rotation 1");
        waitFor("rotation cancels old selection safely",()->!has(R.id.capture_selection)&&has(R.id.capture_bubble),8000);
        assertTrue(ScreenCaptureService.isRunning());assertSame("Rotation must not create a second display from the same grant",originalDisplay,serviceField("display"));
        SystemClock.sleep(500);selectTextRegion();waitFor("cancel after rotated capture",()->clickText("取消本次"),3000);
        shell("appops set "+context.getPackageName()+" SYSTEM_ALERT_WINDOW deny");waitFor("revoked overlay permission stops the session",()->!ScreenCaptureService.isRunning()&&!has(R.id.capture_bubble),6000);
        shell("appops set "+context.getPackageName()+" SYSTEM_ALERT_WINDOW allow");
    }
    @Test public void persistentNotificationStopsCaptureAndTheNextSessionNeedsNewConsent()throws Exception{
        beginAndAllow();waitFor("Foreground capture notification is delivered",()->context.getSystemService(android.app.NotificationManager.class).getActiveNotifications().length>0,5000);android.app.Notification.Action stop=null;for(android.service.notification.StatusBarNotification notice:context.getSystemService(android.app.NotificationManager.class).getActiveNotifications())if(notice.getNotification().actions!=null)for(android.app.Notification.Action action:notice.getNotification().actions)if(action.title.toString().contains("停止"))stop=action;
        assertNotNull("Foreground notification must expose an explicit stop",stop);stop.actionIntent.send();waitFor("notification stop cleans overlays and projection",()->!ScreenCaptureService.isRunning()&&!has(R.id.capture_bubble),5000);
        main.onActivity(activity->activity.findViewById(R.id.offline_capture).performClick());waitFor("new-session explanation",()->clickText("开启悬浮取字"),5000);
        waitFor("system consent must return for a new session",()->screenText().contains("Start")||screenText().contains("开始")||screenText().contains("Share"),10000);assertFalse("No prior grant is reused",ScreenCaptureService.isRunning());
        waitFor("decline new session",()->clickText("Cancel","取消","Don't allow","不允许"),5000);assertFalse(ScreenCaptureService.isRunning());
    }
    @Test public void protectedFixtureContentRemainsBlackInsteadOfBeingReadThrough()throws Exception{
        beginAndAllow();fixture=ActivityScenario.launch(new Intent(context,CaptureFixtureActivity.class).putExtra("protected",true));SystemClock.sleep(700);selectTextRegion();
        onMain(()->{Bitmap image=(Bitmap)serviceField("snapshot");assertNotNull(image);int color=image.getPixel(image.getWidth()/2,image.getHeight()/2);assertTrue("FLAG_SECURE source content must remain hidden",android.graphics.Color.red(color)<15&&android.graphics.Color.green(color)<15&&android.graphics.Color.blue(color)<15);});
        waitFor("cancel protected capture",()->clickText("取消本次"),3000);assertTrue(ScreenCaptureService.isRunning());
    }
    @Test @SdkSuppress(minSdkVersion=34) public void singleAppSharingCapturesOnlyTheAuthorizedSyntheticApp()throws Exception{
        shell("am start -W -n com.shunshoupai.app.test/com.shunshoupai.app.CaptureOtherAppActivity");
        otherAppStarted=true;
        waitFor("other synthetic app is visible",()->screenText().contains("OCR Test Screen"),7000);
        onMain(()->context.startActivity(new Intent(originalMainIntent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)));
        waitFor("native workbench resumes",()->screenText().contains("顺手排"),7000);click(R.id.offline_capture);waitFor("feature explanation",()->clickText("开启悬浮取字"),5000);
        acceptSystemMode(true);
        waitFor("System app picker must explicitly select the synthetic app",this::chooseSyntheticApp,10000);
        waitFor("Single-app grant becomes active",ScreenCaptureService::isRunning,15000);
        assertTrue("Single-app consent should complete: "+screenText(),ScreenCaptureService.isRunning());
        waitFor("authorized app returns with the capture control",()->has(R.id.capture_bubble)&&screenText().contains("OCR Test Screen"),8000);
        selectTextRegion();onMain(()->{Bitmap image=(Bitmap)serviceField("snapshot");assertNotNull(image);assertTrue("Shared content remains within the image memory bound",(long)image.getWidth()*image.getHeight()<=3_000_000);});
        click(R.id.capture_recognize);waitFor("local OCR from shared app window",()->has(R.id.capture_text),30000);onMain(()->assertTrue(((EditText)find(R.id.capture_text)).getText().toString().contains("开会")));
        waitFor("discard synthetic OCR result",()->clickText("暂不使用"),3000);
        // Edge-to-edge app content may equal display dimensions, and a detached
        // output surface pauses visibility callbacks. The system mode and explicit
        // app-picker assertions above establish the grant; OCR verifies its pixels.
    }
    private Object serviceField(String name){try{java.lang.reflect.Field current=ScreenCaptureService.class.getDeclaredField("current");current.setAccessible(true);Object service=current.get(null);java.lang.reflect.Field field=ScreenCaptureService.class.getDeclaredField(name);field.setAccessible(true);return field.get(service);}catch(Exception error){throw new AssertionError(error);}}
}
