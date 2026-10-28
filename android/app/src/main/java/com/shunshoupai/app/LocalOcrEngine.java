package com.shunshoupai.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Bundled on-device OCR. Input images and recognized text are never sent by this engine. */
final class LocalOcrEngine implements AutoCloseable {
    static final long MAX_PIXELS=8_000_000;
    static final int MAX_SIDE=8192,MAX_BITMAP_BYTES=48*1024*1024,MAX_RESULT_LENGTH=100_000;
    interface Callback { void onResult(Result result); void onError(String code,String message); }
    static final class Result {
        final String text;final int lineCount;
        Result(String text,int lineCount){this.text=text;this.lineCount=lineCount;}
    }
    private static final class Request {
        Callback callback;Bitmap bitmap;TextRecognizer recognizer;boolean cancelled,finished;
        Request(Callback callback){this.callback=callback;}
    }
    private static final class Line {
        final String text;final Rect bounds;final int order;
        Line(String text,Rect bounds,int order){this.text=text;this.bounds=bounds==null?null:new Rect(bounds);this.order=order;}
    }
    private final Handler main=new Handler(Looper.getMainLooper());
    private Request current;
    private boolean closed;

    LocalOcrEngine(Context context){if(context==null)throw new IllegalArgumentException("Context required");}

    /** Copies the selected bitmap before returning; the caller may then recycle its own image. */
    void recognize(Bitmap source,int rotationDegrees,Callback callback) {
        if(callback==null)throw new IllegalArgumentException("Callback required");
        final Request request;
        synchronized(this){
            if(closed){main.post(()->callback.onError("OCR_CLOSED","识别已结束，请重新打开截图识别。"));return;}
            if(current!=null){Request active=current;main.post(()->{synchronized(LocalOcrEngine.this){if(closed||active.cancelled||current!=active)return;}callback.onError("OCR_BUSY","上一张图片仍在处理，请稍候。");});return;}
            request=new Request(callback);current=request;
        }
        try {
            if(source==null||source.isRecycled()||source.getWidth()<1||source.getHeight()<1){finish(request,null,"OCR_INVALID_IMAGE","没有可识别的图片，请重新框选。 ");return;}
            if(rotationDegrees!=0&&rotationDegrees!=90&&rotationDegrees!=180&&rotationDegrees!=270){finish(request,null,"OCR_INVALID_ROTATION","图片方向无效，请重新框选。 ");return;}
            long pixels=(long)source.getWidth()*source.getHeight();
            if(pixels>MAX_PIXELS||source.getWidth()>MAX_SIDE||source.getHeight()>MAX_SIDE||source.getAllocationByteCount()>MAX_BITMAP_BYTES){finish(request,null,"OCR_IMAGE_TOO_LARGE","图片过大，请缩小截图区域后再识别。 ");return;}
            request.bitmap=source.copy(Bitmap.Config.ARGB_8888,false);
            if(request.bitmap==null)throw new IllegalStateException();
            synchronized(this){if(closed||request.cancelled){finish(request,null,"CANCELLED","识别已取消。");return;}}
            request.recognizer=TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
            request.recognizer.process(InputImage.fromBitmap(request.bitmap,rotationDegrees)).addOnCompleteListener(Runnable::run,task->{
                Result result=null;String code=null,message=null;
                try {
                    if(task.isSuccessful()){
                        result=ordered(task.getResult());
                        if(result.text.isEmpty()){result=null;code="OCR_EMPTY_TEXT";message="没有识别到文字。请框选清晰的文字区域，或手动输入。";}
                    }else{code="OCR_FAILED";message="暂时无法识别这张图片，请重新框选清晰的文字区域。";}
                }catch(IllegalArgumentException tooLong){code="OCR_TEXT_TOO_LONG";message="图片中的文字过多，请缩小框选区域。识别内容没有被截断。";}
                catch(Exception failure){code="OCR_FAILED";message="暂时无法识别这张图片，请重试。";}
                finish(request,result,code,message);
            });
        }catch(OutOfMemoryError memory){finish(request,null,"OCR_IMAGE_TOO_LARGE","手机内存不足，请缩小截图区域后重试。 ");}
        catch(Exception failure){finish(request,null,"OCR_FAILED","暂时无法启动本机识别，请重新框选后重试。 ");}
    }
    private static Result ordered(Text recognized) {
        if(recognized==null)return new Result("",0);
        List<Line> lines=new ArrayList<>();int order=0;
        for(Text.TextBlock block:recognized.getTextBlocks())for(Text.Line line:block.getLines()){
            String text=normalizeLine(line.getText());if(!text.isEmpty())lines.add(new Line(text,line.getBoundingBox(),order++));
        }
        // A total order avoids inconsistent comparators for overlapping boxes.
        // Reading is top-to-bottom, then left-to-right; the user reviews layout-sensitive text.
        lines.sort(Comparator.comparingInt((Line line)->line.bounds==null?Integer.MAX_VALUE:line.bounds.top)
            .thenComparingInt(line->line.bounds==null?Integer.MAX_VALUE:line.bounds.left).thenComparingInt(line->line.order));
        StringBuilder result=new StringBuilder();
        for(Line line:lines){if(result.length()>0)result.append('\n');result.append(line.text);if(result.length()>MAX_RESULT_LENGTH)throw new IllegalArgumentException();}
        return new Result(result.toString(),lines.size());
    }
    static String normalizeLine(String text) {
        // OCR may insert spaces inside Chinese words and between 9 and 点.
        // Horizontal whitespace only: adjacent lines must remain separate clauses.
        return text.trim().replaceAll("(?<=\\p{IsHan})[ \\t\\u00a0\\u3000]+(?=[\\p{IsHan}0-9])","")
            .replaceAll("(?<=[0-9])[ \\t\\u00a0\\u3000]+(?=\\p{IsHan})","")
            .replaceAll("(?<=[0-9])[ \\t\\u00a0\\u3000]*[:：][ \\t\\u00a0\\u3000]*(?=[0-9])",":");
    }
    private void finish(Request request,Result result,String code,String message) {
        synchronized(this){if(request.finished)return;request.finished=true;}
        // ML Kit has no cancellable process Task. Its input remains alive until
        // completion even when UI cancellation suppresses delivery immediately.
        if(request.recognizer!=null){try{request.recognizer.close();}catch(Exception ignored){}request.recognizer=null;}
        if(request.bitmap!=null){try{request.bitmap.recycle();}catch(Exception ignored){}request.bitmap=null;}
        main.post(()->{
            Callback callback;
            synchronized(this){callback=closed||request.cancelled?null:request.callback;request.callback=null;if(current==request)current=null;}
            if(callback==null)return;
            if(result!=null)callback.onResult(result);else callback.onError(code==null?"OCR_FAILED":code,message==null?"暂时无法识别，请重试。":message.trim());
        });
    }
    synchronized void cancel(){if(current!=null){current.cancelled=true;current.callback=null;if(current.finished)current=null;}}
    @Override public synchronized void close(){closed=true;cancel();}
}
