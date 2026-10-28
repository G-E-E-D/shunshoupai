package com.shunshoupai.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.nio.ByteBuffer;

/** User-started capture session. No calendar, synchronization, model, or image-upload code runs here. */
public final class ScreenCaptureService extends Service {
    static final String ACTION_START="com.shunshoupai.app.START_SCREEN_CAPTURE",ACTION_STOP="com.shunshoupai.app.STOP_SCREEN_CAPTURE";
    static final String EXTRA_REQUEST="capture_request_id",EXTRA_RESULT_CODE="capture_result_code",EXTRA_RESULT_DATA="capture_result_data";
    static final String ACTION_REVIEW="com.shunshoupai.app.REVIEW_LOCAL_OCR",EXTRA_OCR_ID="local_ocr_id";
    private static final String CHANNEL="screen-capture";
    private static final int NOTIFICATION=204;
    private static volatile ScreenCaptureService current;
    private static volatile boolean mainVisible;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Object frameLock=new Object();
    private HandlerThread imageThread;
    private Handler images;
    private WindowManager windows;
    private DisplayManager displays;
    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private LocalOcrEngine ocr;
    private volatile boolean active,wantFrame;
    private volatile long frameAfter,epoch;
    private String scope;
    private View bubble,panel;
    private WindowManager.LayoutParams bubbleParams;
    private CaptureSelectionView selection;
    private Bitmap snapshot;
    private String phase="idle";
    private boolean contentVisible=true,receiverRegistered;
    private int surfaceWidth,surfaceHeight;
    private final Runnable frameTimeout=()->{if(active&&"waiting".equals(phase)){advanceEpoch();detachSurface();phase="idle";showBubble();message("当前画面无法捕获，请切换到允许截图的内容后重试。");}};
    private final Runnable snapshotTimeout=()->{if(active&&"selection".equals(phase)){clearPanel();releaseSnapshot();phase="idle";showBubble();message("已清除停留较久的画面，请重新框选。");}};
    private final Runnable permissionMonitor=new Runnable(){@Override public void run(){if(!active)return;if(!Settings.canDrawOverlays(ScreenCaptureService.this)){stopSelf();return;}main.postDelayed(this,1000);}};
    private final BroadcastReceiver screenOff=new BroadcastReceiver(){@Override public void onReceive(Context context,Intent intent){if(Intent.ACTION_SCREEN_OFF.equals(intent.getAction()))stopSelf();}};
    private final DisplayManager.DisplayListener displayChanges=new DisplayManager.DisplayListener(){public void onDisplayAdded(int id){}public void onDisplayRemoved(int id){}public void onDisplayChanged(int id){if(Build.VERSION.SDK_INT<34&&id==android.view.Display.DEFAULT_DISPLAY&&active){int[] size=screenSize();resize(size[0],size[1]);}}};
    private final MediaProjection.Callback projectionCallback=new MediaProjection.Callback(){
        @Override public void onStop(){if(active)message("系统已停止屏幕捕获，重新开启时需要再次授权。");stopSelf();}
        @Override public void onCapturedContentResize(int width,int height){if(active&&images!=null)images.post(()->resize(width,height));}
        @Override public void onCapturedContentVisibilityChanged(boolean visible){contentVisible=visible;}
    };
    static boolean isRunning(){ScreenCaptureService service=current;return service!=null&&service.active;}
    static void setMainVisible(boolean visible){mainVisible=visible;ScreenCaptureService service=current;if(service==null)return;if(visible)service.main.post(service::hideBubble);else service.main.postDelayed(()->{if(!mainVisible&&service.active&&"idle".equals(service.phase))service.showBubble();},200);}
    static void stop(Context context){CaptureGrantGate.stop();context.stopService(new Intent(context,ScreenCaptureService.class));}
    @Override public void onCreate(){super.onCreate();windows=getSystemService(WindowManager.class);displays=getSystemService(DisplayManager.class);imageThread=new HandlerThread("local-screen-image");imageThread.start();images=new Handler(imageThread.getLooper());ocr=new LocalOcrEngine(this);}
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent==null||ACTION_STOP.equals(intent.getAction())){CaptureGrantGate.stop();stopSelf();return START_NOT_STICKY;}
        if(!ACTION_START.equals(intent.getAction())||active)return START_NOT_STICKY;
        String request=intent.getStringExtra(EXTRA_REQUEST);scope=intent.getStringExtra(ScreenCapturePermissionActivity.EXTRA_SCOPE);
        Intent data=Build.VERSION.SDK_INT>=33?intent.getParcelableExtra(EXTRA_RESULT_DATA,Intent.class):intent.getParcelableExtra(EXTRA_RESULT_DATA);
        if(!CaptureGrantGate.consume(request)||scope==null||scope.isEmpty()||data==null||intent.getIntExtra(EXTRA_RESULT_CODE,0)!=android.app.Activity.RESULT_OK||!Settings.canDrawOverlays(this)){stopSelf();return START_NOT_STICKY;}
        try{
            // Android requires the FGS to be in mediaProjection type before obtaining the projection.
            notification();projection=getSystemService(MediaProjectionManager.class).getMediaProjection(android.app.Activity.RESULT_OK,data);
            if(projection==null)throw new IllegalStateException();projection.registerCallback(projectionCallback,main);active=true;advanceEpoch();current=this;
            if(Build.VERSION.SDK_INT>=33)registerReceiver(screenOff,new IntentFilter(Intent.ACTION_SCREEN_OFF),Context.RECEIVER_NOT_EXPORTED);else registerReceiver(screenOff,new IntentFilter(Intent.ACTION_SCREEN_OFF));receiverRegistered=true;
            if(Build.VERSION.SDK_INT<34)displays.registerDisplayListener(displayChanges,images);
            int[] size=screenSize();images.post(()->resize(size[0],size[1]));main.post(permissionMonitor);
        }catch(RuntimeException denied){message("屏幕授权已失效或系统暂时不允许捕获，请在应用中重新开启。");stopSelf();}
        return START_NOT_STICKY;
    }
    private void notification(){
        NotificationManager manager=getSystemService(NotificationManager.class);manager.createNotificationChannel(new NotificationChannel(CHANNEL,"悬浮取字与屏幕捕获",NotificationManager.IMPORTANCE_LOW));
        PendingIntent stop=PendingIntent.getService(this,11,new Intent(this,ScreenCaptureService.class).setAction(ACTION_STOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent open=PendingIntent.getActivity(this,12,new Intent(this,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder=new Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_capture).setContentTitle("顺手排正在屏幕取字").setContentText("点悬浮按钮框选；图片只在本机处理，可随时停止").setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_SERVICE).setContentIntent(open).addAction(new Notification.Action.Builder(null,"停止并关闭悬浮按钮",stop).build());
        if(Build.VERSION.SDK_INT>=31)builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        Notification notice=builder.build();
        if(Build.VERSION.SDK_INT>=29)startForeground(NOTIFICATION,notice,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);else startForeground(NOTIFICATION,notice);
    }
    @SuppressWarnings("deprecation") private int[] screenSize(){if(Build.VERSION.SDK_INT>=30){Rect bounds=windows.getMaximumWindowMetrics().getBounds();return new int[]{bounds.width(),bounds.height()};}DisplayMetrics metrics=new DisplayMetrics();windows.getDefaultDisplay().getRealMetrics(metrics);return new int[]{metrics.widthPixels,metrics.heightPixels};}
    /** One createVirtualDisplay per system grant. Later changes only resize that display and replace its surface. */
    private void resize(int naturalWidth,int naturalHeight){
        if(!active||projection==null)return;ImageReader next=null;
        try{int[] size=CaptureGeometry.boundedSize(naturalWidth,naturalHeight);if(reader!=null&&size[0]==surfaceWidth&&size[1]==surfaceHeight)return;
            next=ImageReader.newInstance(size[0],size[1],PixelFormat.RGBA_8888,2);next.setOnImageAvailableListener(this::frameAvailable,images);
            int density=getResources().getConfiguration().densityDpi;
            if(display==null)display=projection.createVirtualDisplay("顺手排本机取字",size[0],size[1],density,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,next.getSurface(),null,images);
            else{display.resize(size[0],size[1],density);display.setSurface(next.getSurface());}
            ImageReader old=reader;reader=next;next=null;surfaceWidth=size[0];surfaceHeight=size[1];
            if(old!=null){old.setOnImageAvailableListener(null,null);old.close();}
            if(!wantFrame)display.setSurface(null);
            main.post(()->{if(active&&"idle".equals(phase))showBubble();});
        }catch(RuntimeException|OutOfMemoryError failed){main.post(()->{if(active){message("无法创建捕获画面或内存不足，请停止后重新授权。");stopSelf();}});}
        finally{if(next!=null)next.close();}
    }
    private void frameAvailable(ImageReader source){
        if(!active||source!=reader)return;
        final long deliveryEpoch=epoch;
        try(Image image=source.acquireLatestImage()){
            synchronized(frameLock){if(image==null||!active||source!=reader||epoch!=deliveryEpoch||!wantFrame||SystemClock.elapsedRealtime()<frameAfter)return;wantFrame=false;}
            Bitmap frame=copy(image);
            if(!active||epoch!=deliveryEpoch){frame.recycle();return;}
            if(display!=null)display.setSurface(null);
            main.post(()->{if(!active||epoch!=deliveryEpoch||!"waiting".equals(phase)){frame.recycle();return;}main.removeCallbacks(frameTimeout);snapshot=frame;showSelection();});
        }catch(RuntimeException|OutOfMemoryError failed){
            synchronized(frameLock){if(!active||source!=reader||epoch!=deliveryEpoch)return;wantFrame=false;}
            main.post(()->{if(active&&epoch==deliveryEpoch&&"waiting".equals(phase)){main.removeCallbacks(frameTimeout);detachSurface();phase="idle";showBubble();message("当前画面无法读取或内存不足，请重新框选较小区域。");}});
        }
    }
    private Bitmap copy(Image image){
        int width=image.getWidth(),height=image.getHeight();Image.Plane plane=image.getPlanes()[0];if(plane.getPixelStride()!=4||width<=0||height<=0||(long)width*height>3_000_000)throw new IllegalArgumentException();
        ByteBuffer source=plane.getBuffer().duplicate(),packed=ByteBuffer.allocateDirect(width*height*4);int stride=plane.getRowStride();
        for(int row=0;row<height;row++){int start=row*stride,end=start+width*4;if(end>source.limit())throw new IllegalArgumentException();ByteBuffer line=source.duplicate();line.position(start);line.limit(end);packed.put(line);}
        packed.flip();Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);try{bitmap.copyPixelsFromBuffer(packed);return bitmap;}catch(RuntimeException|OutOfMemoryError error){bitmap.recycle();throw error;}
    }
    private void showBubble(){
        if(!active||mainVisible||bubble!=null||panel!=null||!Settings.canDrawOverlays(this))return;
        Button button=NativeUi.button(this,"取字",true,this::requestFrame);button.setContentDescription("屏幕框选取字，长按可停止，拖动可移动");button.setId(R.id.capture_bubble);button.setOnLongClickListener(view->{showMenu();return true;});
        if(bubbleParams==null){bubbleParams=params(NativeUi.dp(this,60),NativeUi.dp(this,52),false);int[] size=screenSize();bubbleParams.x=Math.max(0,size[0]-NativeUi.dp(this,72));bubbleParams.y=NativeUi.dp(this,160);}
        final float[] down=new float[2];final int[] initial=new int[2];final boolean[] moved={false},longPressed={false};
        GestureDetector detector=new GestureDetector(this,new GestureDetector.SimpleOnGestureListener(){@Override public void onLongPress(MotionEvent event){if(!moved[0]){longPressed[0]=true;showMenu();}}});
        button.setOnTouchListener((view,event)->{detector.onTouchEvent(event);switch(event.getActionMasked()){
            case MotionEvent.ACTION_DOWN:down[0]=event.getRawX();down[1]=event.getRawY();initial[0]=bubbleParams.x;initial[1]=bubbleParams.y;moved[0]=false;longPressed[0]=false;return true;
            case MotionEvent.ACTION_MOVE:float dx=event.getRawX()-down[0],dy=event.getRawY()-down[1];if(Math.abs(dx)+Math.abs(dy)>NativeUi.dp(this,8))moved[0]=true;if(moved[0]&&bubble!=null){int[] size=screenSize();bubbleParams.x=Math.max(0,Math.min(size[0]-bubbleParams.width,initial[0]+(int)dx));bubbleParams.y=Math.max(0,Math.min(size[1]-bubbleParams.height,initial[1]+(int)dy));try{windows.updateViewLayout(bubble,bubbleParams);}catch(RuntimeException revoked){stopSelf();}}return true;
            case MotionEvent.ACTION_UP:if(!moved[0]&&!longPressed[0])view.performClick();return true;
            case MotionEvent.ACTION_CANCEL:return true;default:return false;
        }});
        try{bubble=button;windows.addView(button,bubbleParams);}catch(RuntimeException denied){bubble=null;message("悬浮权限已关闭，请重新开启后使用。");stopSelf();}
    }
    private void hideBubble(){if(bubble!=null){remove(bubble);bubble=null;}}
    private void requestFrame(){if(!active||!"idle".equals(phase))return;if(!contentVisible){message("所选分享应用当前不可见，请先切换回该应用。");return;}hideBubble();phase="waiting";final long requested=advanceEpoch();
        main.postDelayed(()->{if(!active||epoch!=requested||!"waiting".equals(phase))return;images.post(()->{
            if(!active||epoch!=requested||display==null||reader==null)return;
            try{try(Image stale=reader.acquireLatestImage()){/* Drop only frames left from the previous attachment. */}synchronized(frameLock){if(!active||epoch!=requested)return;frameAfter=0;wantFrame=true;}display.setSurface(reader.getSurface());}
            catch(RuntimeException failed){main.post(()->{if(active&&epoch==requested)frameTimeout.run();});}
        });},220);main.postDelayed(frameTimeout,4000);}
    private void showMenu(){if(!active||!"idle".equals(phase))return;hideBubble();phase="menu";LinearLayout content=panelBase("屏幕取字已开启");content.addView(NativeUi.text(this,"长按悬浮按钮可打开此菜单。屏幕图像仅短期留在本机内存。",15));content.addView(NativeUi.button(this,"继续框选",true,()->{clearPanel();phase="idle";requestFrame();}));content.addView(NativeUi.button(this,"停止并关闭悬浮按钮",false,this::stopSelf));content.addView(NativeUi.button(this,"返回",false,()->{clearPanel();phase="idle";showBubble();}));attachPanel(content,false);}
    private void showSelection(){
        if(snapshot==null)return;phase="selection";LinearLayout content=panelBase("拖动框选需要识别的文字");
        content.addView(NativeUi.text(this,"这是已授权画面的本机预览。只识别框选区域；受保护内容无法获取。",12));
        Button confirm=NativeUi.button(this,"识别选区",true,this::recognize);confirm.setId(R.id.capture_recognize);confirm.setEnabled(false);
        selection=new CaptureSelectionView(this,snapshot,confirm::setEnabled);selection.setId(R.id.capture_selection);content.addView(selection,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout actions=NativeUi.row(this);actions.addView(NativeUi.button(this,"取消本次",false,this::cancelPanel),NativeUi.weight(this));actions.addView(confirm,NativeUi.weight(this));content.addView(actions);attachPanel(content,true);main.postDelayed(snapshotTimeout,120000);
    }
    private void recognize(){
        if(!active||!"selection".equals(phase)||snapshot==null||selection==null)return;Bitmap crop;
        try{Rect selected=selection.selection();crop=Bitmap.createBitmap(snapshot,selected.left,selected.top,selected.width(),selected.height());if(crop==snapshot)crop=snapshot.copy(Bitmap.Config.ARGB_8888,false);}
        catch(RuntimeException|OutOfMemoryError invalid){cancelPanel();message("选区无法读取或内存不足，请重新框选更小的文字区域。");return;}
        clearPanel();releaseSnapshot();phase="ocr";long expected=advanceEpoch();LinearLayout wait=panelBase("正在本机识别文字…");wait.addView(NativeUi.text(this,"图片不会发送到日程模型。",14));wait.addView(NativeUi.button(this,"取消识别",false,this::cancelPanel));attachPanel(wait,false);
        try{ocr.recognize(crop,0,new LocalOcrEngine.Callback(){public void onResult(LocalOcrEngine.Result result){if(!active||epoch!=expected||!"ocr".equals(phase))return;clearPanel();showReview(result.text);}public void onError(String code,String text){if(!active||epoch!=expected||!"ocr".equals(phase))return;clearPanel();phase="idle";showBubble();message(text+" 受保护或不清晰的画面可能无法识别。");}});}finally{crop.recycle();}
    }
    private void showReview(String text){
        if(text==null||text.trim().isEmpty()){phase="idle";showBubble();message("未识别到文字，请选择更清晰的区域；受保护画面不能截取。");return;}
        phase="review";LinearLayout content=panelBase("识别文字 · 请核对后继续");content.addView(NativeUi.text(this,"图片只在本机识别。请核对文字；点整理后按手机当前规则或模型设置生成草稿，已配置模型时会发送确认的文字。已有原稿会先询问，正式日程不自动保存。",13));
        EditText editor=NativeUi.field(this,"核对识别文字",R.id.capture_text);editor.setSingleLine(false);editor.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);editor.setMinLines(5);editor.setMaxLines(10);editor.setText(text);editor.setSaveEnabled(false);ScrollView scroll=new ScrollView(this);scroll.addView(editor);content.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        final String[] queuedId={null},queuedText={null};
        TextView count=NativeUi.text(this,"",13);content.addView(count);Button confirm=NativeUi.button(this,"整理成草稿",true,()->{
            String value=editor.getText().toString();if(value.trim().isEmpty()||value.length()>OcrTextInbox.MAX_TEXT_LENGTH)return;
            try{OcrTextInbox inbox=new OcrTextInbox(this);String id=queuedId[0];if(id==null||!value.equals(queuedText[0])){id=inbox.offer(scope,value);queuedId[0]=id;queuedText[0]=value;}Intent open=new Intent(this,MainActivity.class).setAction(ACTION_REVIEW).putExtra(EXTRA_OCR_ID,id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);startActivity(open);cancelPanel();}
            catch(RuntimeException failure){message(queuedId[0]==null?"暂时无法暂存识别文字，请保持本页并稍后重试。":"文字已在本机待处理，可重试或打开顺手排的智能整理；不会重复排队。");}
        });confirm.setId(R.id.capture_to_drafts);Runnable validate=()->{int length=editor.length();count.setText(length+" / "+OcrTextInbox.MAX_TEXT_LENGTH+" 字"+(length>OcrTextInbox.MAX_TEXT_LENGTH?" · 请删减后继续，未自动截断":""));confirm.setEnabled(length>0&&length<=OcrTextInbox.MAX_TEXT_LENGTH&&!editor.getText().toString().trim().isEmpty());};editor.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int c,int a){}public void onTextChanged(CharSequence s,int start,int before,int c){validate.run();}public void afterTextChanged(Editable e){}});validate.run();
        LinearLayout actions=NativeUi.row(this);actions.addView(NativeUi.button(this,"暂不使用",false,this::cancelPanel),NativeUi.weight(this));actions.addView(confirm,NativeUi.weight(this));content.addView(actions);attachPanel(content,true);
    }
    private LinearLayout panelBase(String title){LinearLayout content=NativeUi.column(this);content.setBackgroundColor(Color.rgb(248,250,253));int p=NativeUi.dp(this,16);content.setPadding(p,p,p,p);content.addView(NativeUi.text(this,title,20));content.setFocusableInTouchMode(true);content.setOnKeyListener((view,key,event)->{if(key==KeyEvent.KEYCODE_BACK&&event.getAction()==KeyEvent.ACTION_UP){cancelPanel();return true;}return false;});return content;}
    private void attachPanel(LinearLayout content,boolean full){
        if(!active)return;WindowManager.LayoutParams params=params(-1,full?-1:-2,true);params.gravity=full?Gravity.TOP|Gravity.START:Gravity.CENTER;
        if(Build.VERSION.SDK_INT>=28)params.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        if(full)content.setOnApplyWindowInsetsListener((view,insets)->{int p=NativeUi.dp(this,16);if(Build.VERSION.SDK_INT>=30){android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout()|WindowInsets.Type.ime());view.setPadding(p+bars.left,p+bars.top,p+bars.right,p+bars.bottom);}else view.setPadding(p+insets.getSystemWindowInsetLeft(),p+insets.getSystemWindowInsetTop(),p+insets.getSystemWindowInsetRight(),p+insets.getSystemWindowInsetBottom());return insets;});
        try{panel=content;windows.addView(content,params);content.requestFocus();content.requestApplyInsets();}catch(RuntimeException denied){panel=null;releaseSnapshot();stopSelf();}
    }
    private WindowManager.LayoutParams params(int width,int height,boolean focus){WindowManager.LayoutParams result=new WindowManager.LayoutParams(width,height,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN|(focus?0:WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE),PixelFormat.TRANSLUCENT);result.gravity=Gravity.TOP|Gravity.START;result.softInputMode=WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;return result;}
    private long advanceEpoch(){synchronized(frameLock){wantFrame=false;return ++epoch;}}
    private void detachSurface(){if(images!=null)images.post(()->{if(display!=null&&!wantFrame)try{display.setSurface(null);}catch(RuntimeException ignored){}});}
    private void cancelPanel(){advanceEpoch();detachSurface();ocr.cancel();main.removeCallbacks(frameTimeout);clearPanel();releaseSnapshot();phase="idle";showBubble();}
    private void clearPanel(){main.removeCallbacks(snapshotTimeout);if(selection!=null){selection.clearBitmap();selection=null;}if(panel!=null){remove(panel);panel=null;}}
    private void releaseSnapshot(){if(snapshot!=null){snapshot.recycle();snapshot=null;}}
    private void remove(View view){try{windows.removeViewImmediate(view);}catch(RuntimeException detached){/* System may already have revoked the overlay. */}}
    private void message(String message){Toast.makeText(this,message,Toast.LENGTH_LONG).show();}
    @Override public void onConfigurationChanged(Configuration config){super.onConfigurationChanged(config);if("selection".equals(phase)||"waiting".equals(phase)){cancelPanel();message("画面方向已变化，请重新框选。");}if(bubble!=null){hideBubble();bubbleParams=null;showBubble();}}
    @Override public void onDestroy(){
        active=false;advanceEpoch();if(current==this)current=null;main.removeCallbacksAndMessages(null);hideBubble();clearPanel();releaseSnapshot();if(ocr!=null)ocr.close();
        if(receiverRegistered)try{unregisterReceiver(screenOff);}catch(RuntimeException ignored){}if(displays!=null)displays.unregisterDisplayListener(displayChanges);
        MediaProjection old=projection;projection=null;if(old!=null){try{old.unregisterCallback(projectionCallback);}catch(RuntimeException ignored){}try{old.stop();}catch(RuntimeException ignored){}}
        if(images!=null)images.post(()->{if(display!=null){display.release();display=null;}if(reader!=null){reader.close();reader=null;}imageThread.quitSafely();});
        stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();
    }
}
