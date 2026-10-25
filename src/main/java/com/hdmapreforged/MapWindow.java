package com.hdmapreforged;

import java.awt.*;
import java.awt.event.*;
import javax.swing.*;

/** The map in a window of its own. */
final class MapWindow extends JFrame {
    MapWindow(MapScreen screen, Runnable onClose) {
        super("Reimagined World Map");
        setIconImage(PoiIcons.icon(16, "NAVIGATION"));
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        screen.setScreenLayout(MapScreen.Layout.WINDOW);
        setContentPane(screen);
        setMinimumSize(new Dimension(480, 360));
        setSize(1280, 820);
        setLocationByPlatform(true);
        addWindowListener(new WindowAdapter() {
            public void windowClosed(WindowEvent e) {
                onClose.run();
            }
        });
    }
}
