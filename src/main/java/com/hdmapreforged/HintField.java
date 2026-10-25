package com.hdmapreforged;

import java.awt.*;
import java.awt.event.*;
import java.util.function.*;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.text.*;
import lombok.*;

/**
 * A text field that shows a grey hint while it is empty and not typed in, and a cross at its right end while it holds
 * text: clicked, it empties the field.
 */
@RequiredArgsConstructor
final class HintField extends JTextField {
    static final Color HINT = new Color(150, 150, 150);
    /** Width of the cross's part at the right end. */
    static final int CROSS = 18;

    final Supplier<String> hint;
    /** Run once the cross has emptied the field. */
    Runnable cleared = () -> {};

    {
        addMouseListener(new MouseAdapter() {
            public void mousePressed(MouseEvent e) {
                if (!getText().isEmpty() && e.getX() >= getWidth() - getInsets().right) {
                    setText("");
                    cleared.run();
                }
            }
        });
    }

    /** Typed text stays left of the cross. */
    public Insets getInsets() {
        var i = super.getInsets();
        return new Insets(i.top, i.left, i.bottom, i.right + CROSS);
    }

    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        boolean empty = getText().isEmpty();
        if (empty && isFocusOwner()) {
            return;
        }
        var g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setColor(HINT);
        if (empty) {
            var m = g2.getFontMetrics();
            g2.drawString(hint.get(), getInsets().left + 2, (getHeight() + m.getAscent() - m.getDescent()) / 2);
        } else {
            int x = getWidth() - getInsets().right + CROSS / 2, y = getHeight() / 2;
            g2.drawLine(x - 3, y - 3, x + 3, y + 3);
            g2.drawLine(x - 3, y + 3, x + 3, y - 3);
        }
        g2.dispose();
    }

    static void onEdit(JTextComponent field, Runnable edited) {
        field.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) {
                edited.run();
            }

            public void removeUpdate(DocumentEvent e) {
                edited.run();
            }

            public void changedUpdate(DocumentEvent e) {
                edited.run();
            }
        });
    }

    /** A key of a focused field: {@code action} when {@code when} holds, else the key is left to the window. */
    static void bind(JComponent field, int key, String name, Runnable action, BooleanSupplier when) {
        field.getInputMap().put(KeyStroke.getKeyStroke(key, 0), name);
        field.getActionMap().put(name, new AbstractAction() {
            public boolean isEnabled() {
                return when.getAsBoolean();
            }

            public void actionPerformed(ActionEvent e) {
                action.run();
            }
        });
    }
}
