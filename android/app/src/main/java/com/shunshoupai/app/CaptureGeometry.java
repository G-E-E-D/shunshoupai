package com.shunshoupai.app;

import android.graphics.Rect;
import android.graphics.RectF;

/** Selection coordinates are relative to the displayed image, never physical-screen guesses. */
final class CaptureGeometry {
    static RectF fit(int bitmapWidth,int bitmapHeight,int viewWidth,int viewHeight){
        if(bitmapWidth<=0||bitmapHeight<=0||viewWidth<=0||viewHeight<=0)return new RectF();
        float scale=Math.min((float)viewWidth/bitmapWidth,(float)viewHeight/bitmapHeight);
        float width=bitmapWidth*scale,height=bitmapHeight*scale;
        return new RectF((viewWidth-width)/2f,(viewHeight-height)/2f,(viewWidth+width)/2f,(viewHeight+height)/2f);
    }
    static Rect crop(RectF selection,RectF displayed,int width,int height){
        if(displayed.width()<=0||displayed.height()<=0)throw new IllegalArgumentException("画面尚未准备好。");
        RectF bounded=new RectF(selection);if(!bounded.intersect(displayed))throw new IllegalArgumentException("请在画面内框选文字。");
        int left=Math.max(0,(int)Math.floor((bounded.left-displayed.left)*width/displayed.width()));
        int top=Math.max(0,(int)Math.floor((bounded.top-displayed.top)*height/displayed.height()));
        int right=Math.min(width,(int)Math.ceil((bounded.right-displayed.left)*width/displayed.width()));
        int bottom=Math.min(height,(int)Math.ceil((bounded.bottom-displayed.top)*height/displayed.height()));
        if(right-left<4||bottom-top<4)throw new IllegalArgumentException("选区太小，请重新框选。");
        return new Rect(left,top,right,bottom);
    }
    static int[] boundedSize(int width,int height){
        if(width<=0||height<=0)throw new IllegalArgumentException("屏幕尺寸无效。");
        double scale=Math.min(1,Math.min(2560.0/Math.max(width,height),Math.sqrt(3_000_000.0/((double)width*height))));
        return new int[]{Math.max(1,(int)Math.floor(width*scale)),Math.max(1,(int)Math.floor(height*scale))};
    }
    private CaptureGeometry(){}
}
