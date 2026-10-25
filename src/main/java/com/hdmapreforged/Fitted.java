package com.hdmapreforged;

import java.awt.*;
import javax.swing.*;

/** Takes the scroll pane's width; scrolls only up and down. */
class Fitted extends JPanel implements Scrollable {
    /** What a page's scroll keeps in view of the page before. */
    final int overlap;

    Fitted(LayoutManager layout, int overlap) {
        super(layout);
        this.overlap = overlap;
    }

    public Dimension getPreferredScrollableViewportSize() {
        return getPreferredSize();
    }

    public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
        return 16;
    }

    public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
        return visible.height - overlap;
    }

    public boolean getScrollableTracksViewportWidth() {
        return true;
    }

    public boolean getScrollableTracksViewportHeight() {
        return false;
    }
}
