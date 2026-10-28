package com.shunshoupai.capture;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.List;

/** AWT screen bounds and input points are logical; each image retains that screen's native pixels. */
final class CaptureGeometry {
    static final long MAX_PIXELS = 32_000_000;
    record Frame(Rectangle bounds, BufferedImage image) {
        Frame { bounds = new Rectangle(bounds); }
        double scaleX() { return (double) image.getWidth() / bounds.width; }
        double scaleY() { return (double) image.getHeight() / bounds.height; }
    }
    private CaptureGeometry() {}
    static Rectangle selection(Point first, Point second) {
        return new Rectangle(Math.min(first.x, second.x), Math.min(first.y, second.y),
                Math.abs(first.x - second.x), Math.abs(first.y - second.y));
    }
    static Rectangle source(Rectangle selection, Frame frame) {
        Rectangle part = selection.intersection(frame.bounds());
        if (part.isEmpty()) return new Rectangle();
        int left = (int) Math.floor((part.x - frame.bounds.x) * frame.scaleX());
        int top = (int) Math.floor((part.y - frame.bounds.y) * frame.scaleY());
        int right = Math.min(frame.image.getWidth(), (int) Math.ceil((part.x + part.width - frame.bounds.x) * frame.scaleX()));
        int bottom = Math.min(frame.image.getHeight(), (int) Math.ceil((part.y + part.height - frame.bounds.y) * frame.scaleY()));
        return new Rectangle(left, top, right - left, bottom - top);
    }
    static BufferedImage crop(Rectangle selection, List<Frame> frames) {
        if (selection.width < 2 || selection.height < 2) throw new IllegalArgumentException("请框选稍大一些的文字区域。");
        double scale = frames.stream().filter(f -> f.bounds.intersects(selection))
                .mapToDouble(f -> Math.max(f.scaleX(), f.scaleY())).max().orElseThrow(() -> new IllegalArgumentException("选区不在屏幕内。"));
        int width = (int) Math.ceil(selection.width * scale), height = (int) Math.ceil(selection.height * scale);
        if ((long) width * height > MAX_PIXELS) throw new IllegalArgumentException("选区太大，请只框选需要识别的文字。");
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = result.createGraphics();
        try {
            g.setColor(Color.WHITE); g.fillRect(0, 0, width, height);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            for (Frame frame : frames) {
                Rectangle part = selection.intersection(frame.bounds);
                if (part.isEmpty()) continue;
                Rectangle src = source(selection, frame);
                int x1 = (int) Math.round((part.x - selection.x) * scale), y1 = (int) Math.round((part.y - selection.y) * scale);
                int x2 = (int) Math.round((part.x + part.width - selection.x) * scale), y2 = (int) Math.round((part.y + part.height - selection.y) * scale);
                g.drawImage(frame.image, x1, y1, x2, y2, src.x, src.y, src.x + src.width, src.y + src.height, null);
            }
        } finally { g.dispose(); }
        return result;
    }
}
