package com.shunshoupai.app;

import static org.junit.Assert.*;
import android.graphics.Rect;
import android.graphics.RectF;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class CaptureGeometryInstrumentedTest {
    @Test public void portraitScreenshotMapsFromItsFitPreviewWithoutSystemBarAssumptions(){RectF image=CaptureGeometry.fit(1080,2400,500,1000);assertEquals(new Rect(108,240,972,2160),CaptureGeometry.crop(new RectF(image.left+image.width()*.1f,image.top+image.height()*.1f,image.left+image.width()*.9f,image.top+image.height()*.9f),image,1080,2400));}
    @Test public void singleAppAndLandscapeLetterboxingUseActualImageBounds(){RectF image=CaptureGeometry.fit(1800,900,400,900);assertEquals(350f,image.top,0.01f);assertEquals(new Rect(450,0,1350,900),CaptureGeometry.crop(new RectF(100,200,300,800),image,1800,900));}
    @Test public void selectionOutsideImageOrTooSmallIsRejected(){RectF image=CaptureGeometry.fit(100,100,200,400);try{CaptureGeometry.crop(new RectF(0,0,10,10),image,100,100);fail();}catch(IllegalArgumentException expected){}try{CaptureGeometry.crop(new RectF(100,200,101,201),image,100,100);fail();}catch(IllegalArgumentException expected){}}
    @Test public void largeDisplaysAreBoundedBeforeImageBuffersAreAllocated(){int[] size=CaptureGeometry.boundedSize(7680,4320);assertTrue((long)size[0]*size[1]<=3_000_000);assertTrue(Math.max(size[0],size[1])<=2560);assertEquals(7680.0/4320,(double)size[0]/size[1],0.002);}
    @Test public void stoppedOrConsumedSystemResultsCannotRearmAfterRotation(){CaptureGrantGate.stop();String first=CaptureGrantGate.begin();assertNotNull(first);assertNull(CaptureGrantGate.begin());assertTrue(CaptureGrantGate.restore(first));CaptureGrantGate.stop();assertFalse(CaptureGrantGate.restore(first));assertFalse(CaptureGrantGate.consume(first));String next=CaptureGrantGate.begin();assertTrue(CaptureGrantGate.consume(next));assertFalse(CaptureGrantGate.consume(next));assertFalse(CaptureGrantGate.restore(next));}
}
