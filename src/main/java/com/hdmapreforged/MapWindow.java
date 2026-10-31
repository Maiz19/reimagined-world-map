package com.hdmapreforged;

import java.awt.*;
import java.awt.event.*;
import javax.swing.*;

/** The map in a window of its own. */
class MapWindow extends JFrame {
    MapWindow(MapScreen screen, Runnable onClose) {
        super("Reimagined World Map");
        setIconImage(PoiIcons.icon(16, "NAVIGATION"));
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        screen.layOut();
        setContentPane(screen);
        setMinimumSize(new Dimension(480, 360));
        var room = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        setSize(Math.min(1280, room.width), Math.min(820, room.height));
        setLocationByPlatform(true);
        addWindowListener(new WindowAdapter() {
            public void windowClosed(WindowEvent e) {
                onClose.run();
            }
        });
    }
}
