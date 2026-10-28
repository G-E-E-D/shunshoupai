package com.shunshoupai.capture;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.Consumer;

final class FloatingCapture implements AutoCloseable {
    private final JWindow window = new JWindow((Window) null);
    private final Consumer<Point> moved;
    private boolean enabled;
    FloatingCapture(Runnable capture, Runnable settings, Point position, Consumer<Point> moved) {
        this.moved = moved;
        window.setName("capture-float"); window.setFocusableWindowState(false); window.setAutoRequestFocus(false);
        window.setAlwaysOnTop(true); window.setType(Window.Type.UTILITY);
        JLabel button = new JLabel("截图", SwingConstants.CENTER);
        button.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 16)); button.setForeground(Color.WHITE);
        button.setOpaque(true); button.setBackground(new Color(30, 100, 81));
        button.setBorder(BorderFactory.createLineBorder(new Color(112, 182, 153), 1));
        button.setToolTipText("点击框选截图 · 拖动移动 · 右键设置");
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        MouseAdapter mouse = new MouseAdapter() {
            Point press, original;
            @Override public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger() || SwingUtilities.isRightMouseButton(e)) { settings.run(); return; }
                if (SwingUtilities.isLeftMouseButton(e)) { press = e.getLocationOnScreen(); original = window.getLocation(); }
            }
            @Override public void mouseDragged(MouseEvent e) {
                if (press == null) return;
                Point now = e.getLocationOnScreen(); window.setLocation(original.x + now.x - press.x, original.y + now.y - press.y);
            }
            @Override public void mouseReleased(MouseEvent e) {
                if (press == null) return;
                Point end = e.getLocationOnScreen(); boolean clicked = press.distance(end) < 5; press = null;
                if (clicked) capture.run(); else { keepOnScreen(); FloatingCapture.this.moved.accept(window.getLocation()); }
            }
        };
        button.addMouseListener(mouse); button.addMouseMotionListener(mouse);
        window.setContentPane(button); window.setSize(68, 44); window.setLocation(position); keepOnScreen();
    }
    private void keepOnScreen() {
        GraphicsDevice[] screens = GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices();
        Rectangle chosen = null;
        for (GraphicsDevice screen : screens) {
            Rectangle bounds = screen.getDefaultConfiguration().getBounds();
            if (bounds.contains(window.getLocation())) { chosen = bounds; break; }
        }
        if (chosen == null) chosen = screens[0].getDefaultConfiguration().getBounds();
        window.setLocation(Math.max(chosen.x, Math.min(window.getX(), chosen.x + chosen.width - window.getWidth())),
                Math.max(chosen.y, Math.min(window.getY(), chosen.y + chosen.height - window.getHeight())));
    }
    void setEnabled(boolean value) { enabled = value; window.setVisible(value); }
    void hideForCapture() { window.setVisible(false); }
    void restore() { if (enabled) window.setVisible(true); }
    boolean enabled() { return enabled; }
    @Override public void close() { window.dispose(); }
}
