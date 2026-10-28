package com.shunshoupai.capture;

import org.junit.jupiter.api.Test;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CaptureGeometryTest {
    private CaptureGeometry.Frame frame(Rectangle bounds, double scale, Color color) {
        BufferedImage image = new BufferedImage((int) (bounds.width * scale), (int) (bounds.height * scale), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics(); g.setColor(color); g.fillRect(0, 0, image.getWidth(), image.getHeight()); g.dispose(); return new CaptureGeometry.Frame(bounds, image);
    }
    @Test void reversedDragAndNegativeMonitorCoordinates() {
        assertEquals(new Rectangle(-240, -100, 400, 230), CaptureGeometry.selection(new Point(160, 130), new Point(-240, -100)));
    }
    @Test void mixedDpiCrossMonitorCropPreservesNativePixelsAndPlacement() {
        var left = frame(new Rectangle(-100, 0, 100, 100), 1.25, Color.RED);
        var right = frame(new Rectangle(0, 0, 100, 100), 2, Color.BLUE);
        BufferedImage image = CaptureGeometry.crop(new Rectangle(-20, 10, 40, 30), List.of(left, right));
        assertEquals(80, image.getWidth()); assertEquals(60, image.getHeight()); assertEquals(Color.RED.getRGB(), image.getRGB(10, 20)); assertEquals(Color.BLUE.getRGB(), image.getRGB(65, 20));
        assertEquals(new Rectangle(100, 12, 25, 38), CaptureGeometry.source(new Rectangle(-20, 10, 40, 30), left));
    }
    @Test void monitorGapIsBlankInsteadOfReadingUnselectedPixels() {
        var left = frame(new Rectangle(0, 0, 20, 20), 1, Color.RED); var right = frame(new Rectangle(40, 0, 20, 20), 1.5, Color.BLUE);
        BufferedImage image = CaptureGeometry.crop(new Rectangle(10, 0, 40, 20), List.of(left, right));
        assertEquals(Color.WHITE.getRGB(), image.getRGB(30, 10));
    }
    @Test void oversizedAndEmptyRegionsRejected() {
        var frame = frame(new Rectangle(0, 0, 20, 20), 1, Color.BLACK);
        assertThrows(IllegalArgumentException.class, () -> CaptureGeometry.crop(new Rectangle(0, 0, 1, 1), List.of(frame)));
        assertThrows(IllegalArgumentException.class, () -> CaptureGeometry.crop(new Rectangle(0, 0, 100000, 100000), List.of(frame)));
    }
    @Test void cancelAndImmediatelyRestartInvalidatesPreviousTimers() {
        CaptureSession state = new CaptureSession(); long first = state.begin(); state.cancel(); long second = state.begin();
        assertFalse(state.valid(first)); assertTrue(state.valid(second)); state.cancel(); assertFalse(state.valid(second));
    }
}
