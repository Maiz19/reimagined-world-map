package com.hdmapreforged;

import static java.awt.BorderLayout.*;
import java.awt.*;
import javax.swing.*;
import net.runelite.client.ui.*;

/** A panel floating over the map (card, search results, custom routes): a title bar with fold and close buttons. */
final class Floating extends JPanel {
    static final int BAR = 24;
    /** The soft white of names and buttons on the map. */
    static final Color LIGHT = new Color(200, 200, 205);

    final JPanel bar = new JPanel(new BorderLayout(4, 0));
    final JButton fold = small("–", "Fold up");
    JComponent content;
    boolean folded;

    Floating(String title, Runnable close) {
        super(new BorderLayout());
        setBorder(BorderFactory.createLineBorder(MapView.CONTROL_EDGE));
        setBackground(ColorScheme.DARKER_GRAY_COLOR);
        bar.setBackground(new Color(24, 26, 30));
        bar.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 2));
        JLabel name = new JLabel(title);
        name.setForeground(LIGHT);
        name.setFont(name.getFont().deriveFont(Font.BOLD));
        bar.add(name, CENTER);
        JPanel buttons = new JPanel(new BorderLayout(2, 0));
        buttons.setOpaque(false);
        fold.addActionListener(e -> setFolded(!folded));
        buttons.add(fold, WEST);
        JButton shut = small("×", "Close");
        shut.addActionListener(e -> close.run());
        buttons.add(shut, EAST);
        bar.add(buttons, EAST);
        bar.setPreferredSize(new Dimension(10, BAR));
        add(bar, NORTH);
    }

    /** Replaces the content below the bar, matching the current fold state. */
    void setContent(JComponent shown) {
        if (content != null && content.getParent() == this) {
            remove(content);
        }
        content = shown;
        add(shown, CENTER);
        shown.setVisible(!folded);
        revalidate();
    }

    void setFolded(boolean fold) {
        folded = fold;
        this.fold.setText(fold ? "+" : "–");
        this.fold.setToolTipText(fold ? "Unfold" : "Fold up");
        if (content != null) {
            content.setVisible(!fold);
        }
        firePropertyChange("folded", !fold, fold);
        revalidate();
        repaint();
    }

    /** Only its bar when folded, else the bar and {@code contentHeight}. */
    int height(int contentHeight) {
        return BAR + 2 + (folded ? 0 : contentHeight);
    }

    static JButton small(String text, String tip) {
        JButton button = new JButton(text);
        button.setFocusable(false);
        button.setMargin(new Insets(0, 6, 0, 6));
        button.setBorderPainted(false);
        button.setContentAreaFilled(false);
        button.setForeground(LIGHT);
        button.setToolTipText(tip);
        return button;
    }
}
