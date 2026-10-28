package com.shunshoupai.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import java.util.function.Consumer;

/** A frozen local preview supports app-window sharing and arbitrary aspect ratios. */
final class CaptureSelectionView extends View {
    private Bitmap bitmap;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private final Consumer<Boolean> valid;
    private final RectF selected=new RectF();
    private float startX,startY;
    private boolean dragging;
    CaptureSelectionView(Context context,Bitmap bitmap,Consumer<Boolean> valid){super(context);this.bitmap=bitmap;this.valid=valid;setContentDescription("在冻结的屏幕画面中拖动，框选需要识别的文字");setFocusable(true);}
    Rect selection(){if(bitmap==null)throw new IllegalStateException("画面已清除。");return CaptureGeometry.crop(selected,bounds(),bitmap.getWidth(),bitmap.getHeight());}
    void clearBitmap(){bitmap=null;selected.setEmpty();invalidate();}
    private RectF bounds(){return bitmap==null?new RectF():CaptureGeometry.fit(bitmap.getWidth(),bitmap.getHeight(),getWidth(),getHeight());}
    @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);canvas.drawColor(Color.rgb(30,33,38));if(bitmap==null||bitmap.isRecycled())return;RectF image=bounds();paint.setColor(Color.WHITE);canvas.drawBitmap(bitmap,null,image,paint);
        if(!selected.isEmpty()){paint.setColor(0x88000000);canvas.drawRect(image.left,image.top,image.right,selected.top,paint);canvas.drawRect(image.left,selected.bottom,image.right,image.bottom,paint);canvas.drawRect(image.left,selected.top,selected.left,selected.bottom,paint);canvas.drawRect(selected.right,selected.top,image.right,selected.bottom,paint);paint.setColor(0xff0b57d0);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(NativeUi.dp(getContext(),3));canvas.drawRect(selected,paint);paint.setStyle(Paint.Style.FILL);}}
    @Override public boolean onTouchEvent(MotionEvent event){if(bitmap==null)return false;RectF image=bounds();float x=Math.max(image.left,Math.min(image.right,event.getX())),y=Math.max(image.top,Math.min(image.bottom,event.getY()));
        if(event.getActionMasked()==MotionEvent.ACTION_DOWN){if(!image.contains(event.getX(),event.getY()))return false;startX=x;startY=y;dragging=true;selected.setEmpty();valid.accept(false);invalidate();return true;}
        if(!dragging)return false;
        if(event.getActionMasked()==MotionEvent.ACTION_MOVE||event.getActionMasked()==MotionEvent.ACTION_UP){selected.set(Math.min(startX,x),Math.min(startY,y),Math.max(startX,x),Math.max(startY,y));invalidate();boolean ok;try{selection();ok=true;}catch(Exception small){ok=false;}valid.accept(ok);if(event.getActionMasked()==MotionEvent.ACTION_UP){dragging=false;performClick();}return true;}
        if(event.getActionMasked()==MotionEvent.ACTION_CANCEL){dragging=false;selected.setEmpty();valid.accept(false);invalidate();return true;}return true;
    }
    @Override public boolean performClick(){super.performClick();return true;}
    @Override protected void onSizeChanged(int width,int height,int oldWidth,int oldHeight){super.onSizeChanged(width,height,oldWidth,oldHeight);selected.setEmpty();dragging=false;valid.accept(false);}
}
