package com.shunshoupai.app;

import static org.junit.Assert.*;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.inspector.WindowInspector;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/** Product flows use the real native controls and local persistence without any pairing setup. */
@RunWith(AndroidJUnit4.class)
public class StandaloneExperienceInstrumentedTest {
    private Context context;private ActivityScenario<MainActivity> scenario;
    @Before public void open(){
        context=ApplicationProvider.getApplicationContext();BackgroundTestState.reset();context.deleteDatabase("schedule-offline.sqlite");
        for(String name:new String[]{"connection","offline-editor","planning-drafts","local-model-settings-v1","native-workbench"})context.getSharedPreferences(name,Context.MODE_PRIVATE).edit().clear().commit();
        scenario=ActivityScenario.launch(MainActivity.class);
    }
    @After public void close(){if(scenario!=null)scenario.close();}
    private Object field(Object object,String name){try{java.lang.reflect.Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);}catch(Exception error){throw new AssertionError(error);}}
    private LocalScheduleStore store(MainActivity activity){return (LocalScheduleStore)field(activity,"scheduleStore");}
    private <T>T read(Function<MainActivity,T> action){AtomicReference<T> result=new AtomicReference<>();scenario.onActivity(activity->result.set(action.apply(activity)));return result.get();}
    private View windowView(int id){for(View root:WindowInspector.getGlobalWindowViews()){View view=root.findViewById(id);if(view!=null)return view;}return null;}
    private void click(int id){scenario.onActivity(activity->{View view=windowView(id);assertNotNull("Missing control "+id,view);assertTrue("Disabled control "+id,view.isEnabled());view.performClick();});}
    private void setText(int id,String value){scenario.onActivity(activity->{EditText input=(EditText)windowView(id);assertNotNull(input);input.setText(value);});}
    private void await(String description,Function<MainActivity,Boolean> condition){long end=SystemClock.elapsedRealtime()+10000;while(SystemClock.elapsedRealtime()<end){if(read(condition))return;SystemClock.sleep(80);}assertTrue(description,read(condition));}
    private List<JSONObject> saved(){return read(activity->{try{return store(activity).list(store(activity).primaryScope());}catch(Exception error){throw new AssertionError(error);}});}
    private List<View> draftRows(){ViewGroup rows=(ViewGroup)windowView(R.id.planner_rows);List<View> result=new ArrayList<>();if(rows!=null)for(int i=0;i<rows.getChildCount();i++){View row=rows.getChildAt(i);if(row.findViewWithTag("draft_title")!=null)result.add(row);}return result;}
    private boolean containsText(View root,String title){if(root instanceof TextView&&title.contentEquals(((TextView)root).getText()))return true;if(root instanceof ViewGroup){ViewGroup group=(ViewGroup)root;for(int i=0;i<group.getChildCount();i++)if(containsText(group.getChildAt(i),title))return true;}return false;}
    private boolean visibleTitle(MainActivity activity,String title){return containsText(activity.findViewById(R.id.offline_screen),title);}
    private void capture(String name)throws Exception {InstrumentationRegistry.getInstrumentation().waitForIdleSync();Bitmap image=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();assertNotNull(image);try(FileOutputStream out=new FileOutputStream(new File(context.getExternalFilesDir(null),name))){assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,out));}finally{image.recycle();}}

    @Test public void unpairedRulesBatchCanBeEditedClosedRestoredAndCommittedOnlyOnce()throws Exception {
        assertTrue(read(activity->{try{return store(activity).peers().isEmpty();}catch(Exception error){throw new AssertionError(error);}}));
        click(R.id.offline_online);
        setText(R.id.planner_input,"明天下午三点到四点阅读，明天晚上七点到八点散步，明天晚上九点到十点复盘。");
        click(R.id.planner_parse);await("Three locally parsed drafts",activity->draftRows().size()==3);
        assertTrue("Parsing never writes formal events",saved().isEmpty());
        scenario.onActivity(activity->{List<View> rows=draftRows();((EditText)rows.get(0).findViewWithTag("draft_title")).setText("手机修改后的阅读安排");rows.get(2).findViewWithTag("draft_remove").performClick();});
        await("Removed draft stays removed",activity->draftRows().size()==2);
        scenario.onActivity(activity->{android.graphics.Rect visible=new android.graphics.Rect();View commit=windowView(R.id.planner_commit);assertTrue("Confirm save remains visible while the batch list scrolls",commit.getGlobalVisibleRect(visible));assertTrue(visible.height()>=24*activity.getResources().getDisplayMetrics().density);});
        capture("android-standalone-review.png");
        scenario.onActivity(activity->{try{Object dialog=field(activity,"planningDialog");java.lang.reflect.Method close=dialog.getClass().getDeclaredMethod("close");close.setAccessible(true);close.invoke(dialog);}catch(Exception error){throw new AssertionError(error);}});
        scenario.recreate();assertTrue(saved().isEmpty());click(R.id.offline_online);
        await("Edited batch survives recreation",activity->draftRows().size()==2);
        assertEquals("手机修改后的阅读安排",read(activity->((EditText)draftRows().get(0).findViewWithTag("draft_title")).getText().toString()));
        assertTrue("Restoring a draft must not submit it",saved().isEmpty());
        click(R.id.planner_commit);await("Confirmed batch saves both drafts",activity->{try{return store(activity).list(store(activity).primaryScope()).size()==2;}catch(Exception error){return false;}});
        scenario.recreate();assertEquals(2,saved().size());assertTrue(saved().stream().anyMatch(row->"手机修改后的阅读安排".equals(row.optString("title"))));
        assertFalse(saved().stream().anyMatch(row->row.optString("title").contains("复盘")));
        assertTrue(read(activity->{try{return store(activity).peers().isEmpty();}catch(Exception error){throw new AssertionError(error);}}));
    }

    @Test public void ambiguousRulesInputPreservesTextWithoutCreatingAnEvent()throws Exception {
        click(R.id.offline_online);String input="周五三点讨论方案";setText(R.id.planner_input,input);click(R.id.planner_parse);
        await("Missing morning or afternoon must ask for clarification",activity->{View warnings=windowView(R.id.planner_warnings);return warnings instanceof TextView&&!((TextView)warnings).getText().toString().trim().isEmpty();});
        assertTrue(saved().isEmpty());assertEquals(input,read(activity->((EditText)windowView(R.id.planner_input)).getText().toString()));
        scenario.recreate();await("Composer survives recreation",activity->windowView(R.id.planner_input)!=null);
        assertEquals(input,read(activity->((EditText)windowView(R.id.planner_input)).getText().toString()));assertTrue(saved().isEmpty());
    }

    @Test public void tomorrowMeansRealShanghaiTomorrowEvenWhenViewingAFutureCalendarDate()throws Exception {
        LocalDate actual=LocalDate.now(LocalScheduleStore.ZONE);
        scenario.onActivity(activity->((OfflineScheduleView)activity.findViewById(R.id.offline_screen)).showDate(actual.plusDays(30)));
        click(R.id.offline_online);setText(R.id.planner_input,"明天下午三点到四点阅读");click(R.id.planner_rules);
        await("One offline draft",activity->draftRows().size()==1);
        String scope=read(activity->store(activity).primaryScope());
        JSONObject state=new JSONObject(context.getSharedPreferences("planning-drafts",Context.MODE_PRIVATE).getString(scope,"{}"));
        assertEquals(actual.plusDays(1)+"T15:00:00",state.getJSONArray("drafts").getJSONObject(0).getString("start_local"));
        assertTrue(saved().isEmpty());
    }

    @Test public void sevenDayAndCompletedViewsWorkWithoutAnyComputer()throws Exception {
        LocalDate today=LocalDate.now(LocalScheduleStore.ZONE);
        scenario.onActivity(activity->{try{
            LocalScheduleStore local=store(activity);
            for(int offset:new int[]{0,6,7}){LocalDateTime start=today.plusDays(offset).atTime(9,0);local.save(local.primaryScope(),null,LocalScheduleStore.payload("第"+offset+"天日程",start,start.plusHours(1),"planned",false));}
            LocalDateTime completed=today.atTime(14,0);local.save(local.primaryScope(),null,LocalScheduleStore.payload("今天已完成",completed,completed.plusHours(1),"completed",false));
            ((OfflineScheduleView)activity.findViewById(R.id.offline_screen)).refresh();
        }catch(Exception error){throw new AssertionError(error);}});
        click(R.id.offline_tab_week);assertTrue(read(activity->visibleTitle(activity,"第0天日程")));assertTrue(read(activity->visibleTitle(activity,"第6天日程")));assertFalse(read(activity->visibleTitle(activity,"第7天日程")));
        scenario.recreate();assertTrue("View choice survives reopening",read(activity->visibleTitle(activity,"第6天日程")));capture("android-standalone-week.png");
        click(R.id.offline_tab_completed);assertTrue(read(activity->visibleTitle(activity,"今天已完成")));assertFalse(read(activity->visibleTitle(activity,"第0天日程")));
        scenario.onActivity(activity->{View root=activity.findViewById(R.id.offline_screen);CheckBox checkbox=findCheck(root,"完成日程：今天已完成");assertNotNull(checkbox);assertTrue(checkbox.isChecked());checkbox.performClick();});
        assertFalse(read(activity->visibleTitle(activity,"今天已完成")));click(R.id.offline_tab_day);assertTrue(read(activity->visibleTitle(activity,"今天已完成")));
        assertEquals("planned",saved().stream().filter(row->"今天已完成".equals(row.optString("title"))).findFirst().get().getString("status"));
        click(R.id.offline_month);capture("android-standalone-month.png");
        LocalDate picked=today.getDayOfMonth()==1?today.plusDays(1):today.minusDays(1);
        scenario.onActivity(activity->{View choice=null;for(View root:WindowInspector.getGlobalWindowViews()){choice=findDescription(root,picked+"，");if(choice!=null)break;}assertNotNull("Month calendar exposes selectable dates",choice);choice.performClick();});
        assertEquals(picked,read(activity->((OfflineScheduleView)activity.findViewById(R.id.offline_screen)).selectedDate()));
        assertFalse(read(activity->visibleTitle(activity,"第0天日程")));
    }
    private View findDescription(View root,String prefix){if(root.getContentDescription()!=null&&root.getContentDescription().toString().startsWith(prefix))return root;if(root instanceof ViewGroup){ViewGroup group=(ViewGroup)root;for(int i=0;i<group.getChildCount();i++){View found=findDescription(group.getChildAt(i),prefix);if(found!=null)return found;}}return null;}
    private CheckBox findCheck(View root,String description){if(root instanceof CheckBox&&description.contentEquals(root.getContentDescription()==null?"":root.getContentDescription()))return (CheckBox)root;if(root instanceof ViewGroup){ViewGroup group=(ViewGroup)root;for(int i=0;i<group.getChildCount();i++){CheckBox found=findCheck(group.getChildAt(i),description);if(found!=null)return found;}}return null;}

    @Test public void phoneModelSettingsAreAvailableWithoutPairingAndKeepKeyOutOfTheView()throws Exception {
        try(ModelDirectInstrumentedTest.ModelServer fixture=new ModelDirectInstrumentedTest.ModelServer("UI模型结果")){
            click(R.id.offline_model_settings);
            await("Local model settings open without a paired computer",activity->windowView(R.id.model_base_url)!=null&&windowView(R.id.model_api_key)!=null);
            assertEquals("",read(activity->((EditText)windowView(R.id.model_api_key)).getText().toString()));
            String key="sk-ui-disposable-phone-key";
            setText(R.id.model_base_url,fixture.base());setText(R.id.model_name,"phone-ui-model");setText(R.id.model_api_key,key);
            scenario.onActivity(activity->((CheckBox)windowView(R.id.model_private_network)).setChecked(true));
            scenario.recreate();await("Open model form restored after rotation",activity->windowView(R.id.model_api_key)!=null);
            assertEquals(fixture.base(),read(activity->((EditText)windowView(R.id.model_base_url)).getText().toString()));
            assertEquals("phone-ui-model",read(activity->((EditText)windowView(R.id.model_name)).getText().toString()));
            assertEquals(key,read(activity->((EditText)windowView(R.id.model_api_key)).getText().toString()));
            assertTrue(read(activity->((EditText)windowView(R.id.model_api_key)).getTransformationMethod() instanceof android.text.method.PasswordTransformationMethod));
            for(String name:new String[]{"local-model-settings-v1","planning-drafts","connection","native-workbench"})assertFalse(context.getSharedPreferences(name,Context.MODE_PRIVATE).getAll().toString().contains(key));
            click(R.id.model_test);await("Phone itself reaches model test endpoint",activity->fixture.requests.size()==1&&windowView(R.id.model_save).isEnabled());
            assertFalse(read(activity->{try{return ((LocalPlanningEngine)field(activity,"planningEngine")).settings().getBoolean("configured");}catch(Exception error){throw new AssertionError(error);}}));
            click(R.id.model_save);await("Model saved only after explicit confirmation",activity->{try{return ((LocalPlanningEngine)field(activity,"planningEngine")).settings().getBoolean("configured")&&((EditText)windowView(R.id.model_api_key)).getText().length()==0;}catch(Exception error){return false;}});
            assertEquals("Saving settings does not spend another model request",1,fixture.requests.size());
            assertFalse(context.getSharedPreferences("local-model-settings-v1",Context.MODE_PRIVATE).getAll().toString().contains(key));
            assertTrue(read(activity->{try{return store(activity).peers().isEmpty();}catch(Exception error){throw new AssertionError(error);}}));
            capture("android-standalone-model.png");
        }
    }
}
