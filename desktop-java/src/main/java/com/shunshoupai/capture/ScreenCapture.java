package com.shunshoupai.capture;

import javax.swing.JComponent;
import javax.swing.JWindow;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.awt.image.MultiResolutionImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

final class ScreenCapture implements AutoCloseable {
    private final CaptureSession session = new CaptureSession();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "screen-capture"); t.setDaemon(true); return t; });
    private final List<JWindow> overlays = new ArrayList<>();
    private List<CaptureGeometry.Frame> frames = List.of();
    private Point anchor, pointer;
    private Consumer<BufferedImage> completed;
    private Consumer<String> failed;
    private Runnable cancelled;
    private Timer delay;

    void start(Consumer<BufferedImage> completed, Consumer<String> failed, Runnable cancelled) {
        cancel(false);
        this.completed = completed; this.failed = failed; this.cancelled = cancelled;
        long ticket = session.begin();
        // Main/float windows have been hidden before start. A ticket prevents a cancelled timer capturing a later session.
        delay = new Timer(180, e -> { if (session.valid(ticket)) executor.submit(() -> capture(ticket)); });
        delay.setRepeats(false); delay.start();
    }
    private void capture(long ticket) {
        List<CaptureGeometry.Frame> captured = new ArrayList<>();
        try {
            if (!session.valid(ticket)) return;
            if (GraphicsEnvironment.isHeadless()) throw new AWTException("无可用桌面");
            long pixels = 0;
            for (GraphicsDevice screen : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
                if (!session.valid(ticket)) { captured.forEach(f -> f.image().flush()); return; }
                Rectangle bounds = screen.getDefaultConfiguration().getBounds();
                MultiResolutionImage image = new Robot(screen).createMultiResolutionScreenCapture(bounds);
                Image nativeImage = image.getResolutionVariants().stream().max(Comparator.comparingLong(i -> (long) i.getWidth(null) * i.getHeight(null))).orElseThrow();
                pixels += (long) nativeImage.getWidth(null) * nativeImage.getHeight(null);
                if (pixels > 90_000_000) throw new AWTException("屏幕总分辨率过大");
                BufferedImage bitmap = new BufferedImage(nativeImage.getWidth(null), nativeImage.getHeight(null), BufferedImage.TYPE_INT_RGB);
                Graphics2D g = bitmap.createGraphics(); try { g.drawImage(nativeImage, 0, 0, null); } finally { g.dispose(); }
                captured.add(new CaptureGeometry.Frame(bounds, bitmap));
            }
            SwingUtilities.invokeLater(() -> {
                if (!session.valid(ticket)) { captured.forEach(f -> f.image().flush()); return; }
                frames = captured; showOverlays(ticket);
            });
        } catch (Exception | LinkageError error) {
            captured.forEach(f -> f.image().flush());
            SwingUtilities.invokeLater(() -> {
                if (!session.valid(ticket)) return;
                cancel(false); failed.accept("当前桌面无法截图。请在可交互的 Windows 桌面使用截图入口。");
            });
        }
    }
    private void showOverlays(long ticket) {
        for (CaptureGeometry.Frame frame : frames) {
            JWindow window = new JWindow((Window) null);
            window.setName("capture-selection"); window.setAlwaysOnTop(true);
            window.setBounds(frame.bounds());
            JComponent canvas = new JComponent() {
                @Override protected void paintComponent(Graphics graphics) {
                    Graphics2D g = (Graphics2D) graphics.create();
                    try {
                        g.drawImage(frame.image(), 0, 0, getWidth(), getHeight(), null);
                        Rectangle chosen = anchor == null || pointer == null ? new Rectangle() : CaptureGeometry.selection(anchor, pointer);
                        chosen.translate(-frame.bounds().x, -frame.bounds().y);
                        g.setColor(new Color(0, 0, 0, 120));
                        java.awt.geom.Area shade = new java.awt.geom.Area(new Rectangle(0, 0, getWidth(), getHeight()));
                        shade.subtract(new java.awt.geom.Area(chosen)); g.fill(shade);
                        if (!chosen.isEmpty()) { g.setColor(new Color(72, 199, 157)); g.setStroke(new BasicStroke(2)); g.draw(chosen); }
                        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 16));
                        g.setColor(new Color(25, 31, 42, 230)); g.fillRoundRect(22, 22, 370, 48, 14, 14);
                        g.setColor(Color.WHITE); g.drawString("拖动框选文字 · 松开识别 · Esc 取消", 38, 53);
                    } finally { g.dispose(); }
                }
            };
            canvas.setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
            canvas.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "cancel");
            canvas.getActionMap().put("cancel", new javax.swing.AbstractAction() { @Override public void actionPerformed(ActionEvent e) { cancel(true); } });
            MouseAdapter mouse = new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    if (!SwingUtilities.isLeftMouseButton(e) || !session.valid(ticket)) return;
                    anchor = e.getLocationOnScreen(); pointer = anchor; repaintAll();
                }
                @Override public void mouseDragged(MouseEvent e) { if (anchor != null && session.valid(ticket)) { pointer = e.getLocationOnScreen(); repaintAll(); } }
                @Override public void mouseReleased(MouseEvent e) {
                    if (!SwingUtilities.isLeftMouseButton(e) || anchor == null || !session.valid(ticket)) return;
                    pointer = e.getLocationOnScreen(); Rectangle chosen = CaptureGeometry.selection(anchor, pointer);
                    if (chosen.width < 4 || chosen.height < 4) { anchor = null; pointer = null; repaintAll(); return; }
                    try { BufferedImage cropped = CaptureGeometry.crop(chosen, frames); cancel(false); completed.accept(cropped); }
                    catch (IllegalArgumentException error) { cancel(false); failed.accept(error.getMessage()); }
                }
            };
            canvas.addMouseListener(mouse); canvas.addMouseMotionListener(mouse); window.setContentPane(canvas);
            overlays.add(window); window.setVisible(true);
        }
        if (!overlays.isEmpty()) overlays.get(0).requestFocus();
    }
    private void repaintAll() { overlays.forEach(Window::repaint); }
    void cancel(boolean notify) {
        boolean wasActive = session.active(); session.cancel();
        if (delay != null) { delay.stop(); delay = null; }
        overlays.forEach(Window::dispose); overlays.clear();
        frames.forEach(f -> f.image().flush()); frames = List.of(); anchor = null; pointer = null;
        if (notify && wasActive && cancelled != null) cancelled.run();
    }
    @Override public void close() { cancel(false); executor.shutdownNow(); }
}
