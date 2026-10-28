package com.shunshoupai.capture;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.function.BiConsumer;
import java.util.function.Function;

final class ReviewDialog extends JDialog {
    static final int TEXT_LIMIT = 2000;
    final JTextArea text = new JTextArea();
    private final JLabel count = new JLabel();
    private final JLabel notice = new JLabel();
    private final JCheckBox append = new JCheckBox("追加到已有输入，保留原文", true);
    private final JButton submit = new JButton("整理成草稿");
    private final JButton later = new JButton("稍后继续");
    private boolean hasPrior, blocked, sent, waiting, settingText;
    private boolean contextAvailable = true;
    private int priorLength;
    private final Function<String, Boolean> changed;
    private final Timer saveDelay;

    ReviewDialog(String original, Function<String, Boolean> changed, BiConsumer<String, String> confirm, Runnable discard) {
        super((Frame) null, "截图文字 · 确认后整理", false); this.changed = changed;
        setName("capture-review"); setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        JPanel content = new JPanel(new BorderLayout(0, 12)); content.setBorder(BorderFactory.createEmptyBorder(20, 22, 20, 22));
        JLabel heading = new JLabel("核对识别文字"); heading.setFont(heading.getFont().deriveFont(Font.BOLD, 22));
        JPanel header = new JPanel(new BorderLayout(0, 7)); header.add(heading, BorderLayout.NORTH);
        header.add(new JLabel("图片仅在本机识别。确认后才将文字交给你的日程整理。"), BorderLayout.CENTER);
        content.add(header, BorderLayout.NORTH);
        text.setName("recognized-text"); text.setLineWrap(true); text.setWrapStyleWord(true); text.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 17));
        text.setMargin(new Insets(12, 12, 12, 12)); text.setText(original);
        JScrollPane scroll = new JScrollPane(text); scroll.setPreferredSize(new Dimension(610, 290)); content.add(scroll, BorderLayout.CENTER);
        JPanel bottom = new JPanel(); bottom.setLayout(new BoxLayout(bottom, BoxLayout.Y_AXIS));
        JPanel meta = new JPanel(new BorderLayout()); meta.add(append, BorderLayout.WEST); meta.add(count, BorderLayout.EAST); bottom.add(meta);
        notice.setAlignmentX(Component.LEFT_ALIGNMENT); notice.setBorder(BorderFactory.createEmptyBorder(8, 0, 10, 0)); bottom.add(notice);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0)); JButton remove = new JButton("放弃这条文字");
        remove.addActionListener(e -> { if (JOptionPane.showConfirmDialog(this, "放弃这条识别文字？其他草稿不受影响。", "放弃文字", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) discard.run(); });
        later.addActionListener(e -> hideKeepingText()); submit.setName("capture-confirm");
        submit.addActionListener(e -> { if (flush()) confirm.accept(text.getText(), hasPrior && append.isSelected() ? "append" : "replace"); });
        buttons.add(remove); buttons.add(later); buttons.add(submit); bottom.add(buttons); content.add(bottom, BorderLayout.SOUTH);
        setContentPane(content); pack(); setMinimumSize(new Dimension(590, 420)); setLocationRelativeTo(null);
        saveDelay = new Timer(280, e -> flush()); saveDelay.setRepeats(false);
        text.getDocument().addDocumentListener(new DocumentListener() {
            private void update() { if (!settingText) { saveDelay.restart(); refresh(); } }
            public void insertUpdate(DocumentEvent e) { update(); }
            public void removeUpdate(DocumentEvent e) { update(); }
            public void changedUpdate(DocumentEvent e) { update(); }
        });
        append.addActionListener(e -> refresh());
        addWindowListener(new WindowAdapter() { @Override public void windowClosing(WindowEvent e) { hideKeepingText(); } });
        getRootPane().registerKeyboardAction(e -> hideKeepingText(), KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);
        refresh();
    }
    void updateContext(String prior, boolean review, boolean pending) {
        hasPrior = !prior.isBlank(); priorLength = prior.strip().length(); blocked = review || pending; append.setVisible(hasPrior);
        if (pending) notice.setText("请先回主程序核对上次保存结果，文字会保留。");
        else if (review) notice.setText("请先回主程序处理已有草稿，文字会保留。");
        else notice.setText("识别可能有误，请核对时间、日期和分行。");
        refresh();
    }
    void setSending(boolean sent, boolean waiting) { this.sent = sent; this.waiting = waiting; text.setEditable(!sent); append.setEnabled(!sent); refresh(); }
    void setContextAvailable(boolean value) { contextAvailable = value; refresh(); }
    void error(String message) { notice.setText(message); }
    private void refresh() {
        int length = text.getText().length();
        int combined = hasPrior && append.isSelected() ? priorLength + 1 + text.getText().strip().length() : length;
        count.setText((hasPrior && append.isSelected() ? "合并后 " + combined : String.valueOf(length)) + " / " + TEXT_LIMIT);
        count.setForeground(Math.max(length, combined) > TEXT_LIMIT ? new Color(170, 45, 45) : new Color(90, 100, 110));
        submit.setText(sent ? waiting ? "正在交给主程序…" : "重试确认" : hasPrior ? append.isSelected() ? "追加并整理" : "替换输入并整理" : "整理成草稿");
        submit.setEnabled(contextAvailable && !waiting && (sent || (!blocked && !text.getText().isBlank() && Math.max(length, combined) <= TEXT_LIMIT)));
        if (!sent && Math.max(length, combined) > TEXT_LIMIT) notice.setText("文字已完整保留。请让整理输入不超过 2000 字，或分次整理。");
    }
    boolean flush() { saveDelay.stop(); return settingText || changed.apply(text.getText()); }
    private void hideKeepingText() { if (flush()) setVisible(false); }
    @Override public void dispose() { if (saveDelay != null) saveDelay.stop(); super.dispose(); }
}
