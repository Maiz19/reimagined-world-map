package com.hdmapreforged;

import static java.awt.RenderingHints.*;
import static javax.swing.SwingUtilities.*;
import static java.awt.Cursor.*;
import static java.lang.Math.*;

import java.awt.*;
import java.awt.Point;
import java.awt.event.*;
import java.awt.geom.*;
import java.util.*;
import java.util.function.*;
import javax.swing.*;
import javax.swing.plaf.*;
import javax.swing.text.*;
import lombok.*;
import net.runelite.api.*;

/**
 * The map over the game view: a window of its own, owned by the client's (RuneLite's own window is never changed).
 * Not drawn in the game because Stretched Mode would blur it. Takes the keyboard only while its search is typed in, or
 * for a click from another program, which it hands on to the game; never moves or resizes the client window. Swing
 * thread only.
 */
final class BigWindow {
    static final Color GRIP_DOTS = new Color(255, 255, 255, 90);
    static final int EDGE = 8;
    /** Thicker than the sides: easy to take hold of. */
    static final int BOTTOM = 12, MIN_WIDTH = 320, MIN_HEIGHT = 240;
    static final Color FRAME = new Color(30, 32, 37);
    /** By the edges a point is on (see edgesAt). */
    static final int[] CURSORS = {DEFAULT_CURSOR, W_RESIZE_CURSOR, E_RESIZE_CURSOR,
        DEFAULT_CURSOR, N_RESIZE_CURSOR, NW_RESIZE_CURSOR, NE_RESIZE_CURSOR,
        DEFAULT_CURSOR, S_RESIZE_CURSOR, SW_RESIZE_CURSOR, SE_RESIZE_CURSOR};

    final Client client;
    final MapScreen screen;
    final Consumer<String> saveBounds;
    final Predicate<KeyEvent> isMapKey;
    JWindow window;
    Canvas watchedCanvas;
    Window watchedFrame;
    /** Fractions of the game view, or null for the default. */
    Rectangle2D relative;
    boolean maximised;
    /** Focusable while another program is active: the next click decides where the keys go (see FirstClick). */
    boolean fromOutside;
    final ComponentListener follower = new ComponentAdapter() {
        public void componentMoved(ComponentEvent e) {
            place();
        }

        public void componentResized(ComponentEvent e) {
            place();
        }

        public void componentHidden(ComponentEvent e) {
            close();
        }
    };
    /** The client window going active or not: see followGame. */
    final WindowAdapter gameFocus = new WindowAdapter() {
        public void windowActivated(WindowEvent e) {
            invokeLater(BigWindow.this::followGame);
        }

        public void windowDeactivated(WindowEvent e) {
            invokeLater(BigWindow.this::followGame);
        }
    };

    BigWindow(Client client, MapScreen screen, String savedBounds, Consumer<String> saveBounds,
        Predicate<KeyEvent> isMapKey) {
        this.client = client;
        this.screen = screen;
        this.saveBounds = saveBounds;
        this.isMapKey = isMapKey;
        screen.view.windowControls = this;
        var decoded = decode(savedBounds);
        if (decoded != null) {
            relative = (Rectangle2D) decoded[0];
            maximised = (Boolean) decoded[1];
        }
        screen.setScreenLayout(MapScreen.Layout.FULL);
        screen.view.addMouseListener(new MouseAdapter() {
            public void mouseReleased(MouseEvent e) {
                invokeLater(BigWindow.this::giveKeysBack);
            }
        });
    }

    boolean isOpen() {
        return window != null && window.isVisible();
    }

    void setHostBounds(Rectangle screenBounds) {
        if (!window.getBounds().equals(screenBounds)) {
            window.setBounds(screenBounds);
            window.validate();
        }
    }

    static boolean menuOpen() {
        return MenuSelectionManager.defaultManager().getSelectedPath().length > 0;
    }

    static Rectangle onScreen(Component c) {
        return new Rectangle(c.getLocationOnScreen(), c.getSize());
    }

    void toggle() {
        if (isOpen()) {
            close();
        } else {
            open();
        }
    }

    void open() {
        Canvas canvas = client.getCanvas();
        if (canvas == null || !canvas.isShowing()) {
            return;
        }
        if (window == null) {
            window = new JWindow(getWindowAncestor(canvas));
            // A utility window: left out of the taskbar and alt-tab, raised and lowered with the game.
            window.setType(Window.Type.UTILITY);
            // Some window managers still focus it on click; then Escape and the map key must work here too.
            window.setAutoRequestFocus(false);
            // Never focusable, so the desktop (KWin) keeps the game active; only while typing (see typable).
            window.setFocusableWindowState(false);
            window.addWindowFocusListener(new WindowAdapter() {
                public void windowLostFocus(WindowEvent e) {
                    window.setFocusableWindowState(false);
                }
            });
            window.setContentPane(new JLayer<>(frame(), new FirstClick()));
        }
        watch(canvas);
        place();
        if (!window.isVisible()) {
            window.setVisible(true);
            // Some window managers place a new window themselves.
            invokeLater(this::place);
            screen.opened();
        }
    }

    void close() {
        if (window != null && window.isVisible()) {
            // No longer focusable, a focused window hands the keyboard back to the game's (setFocusableWindowState).
            window.setFocusableWindowState(false);
            window.setVisible(false);
        }
    }

    void toggleMaximised() {
        maximised = !maximised;
        save();
        place();
        screen.view.repaint();
    }

    /**
     * Escape and the map key close the map while one of its parts has the keyboard (the game sees no keys then). While
     * typing or in a menu, keys are theirs: Escape closes the menu, letters type.
     */
    boolean closeKey(KeyEvent e) {
        Object owner = e.getSource();
        if (!isOpen() || menuOpen() || owner instanceof JTextComponent && (e.getKeyCode() != KeyEvent.VK_ESCAPE
            || !((JTextComponent) owner).getText().isEmpty())) {
            return false;
        }
        if (e.getKeyCode() == KeyEvent.VK_ESCAPE || isMapKey.test(e)) {
            close();
            return true;
        }
        return false;
    }

    /** In a window kept unfocusable (see open), a click in {@code field} lets the window take the keyboard to type. */
    static void typable(JTextComponent field) {
        field.addMouseListener(new MouseAdapter() {
            public void mousePressed(MouseEvent e) {
                Window host = getWindowAncestor(field);
                if (host != null && !host.getFocusableWindowState()) {
                    host.setFocusableWindowState(true);
                    invokeLater(field::requestFocus);
                }
            }
        });
    }

    /**
     * While the game is not active (another program is), the map is focusable, so a click on it makes this program
     * active again; giveKeysBack then hands the keyboard to the game.
     */
    void followGame() {
        Window frame = watchedFrame;
        if (window != null && frame != null && !window.isFocused()) {
            fromOutside = !frame.isActive();
            window.setFocusableWindowState(fromOutside);
        }
    }

    /**
     * The first click from another program, on a card's button, a panel or the frame as on the map: the keys go back
     * to the game, or the window's first field would keep them; on a text field they stay, to type.
     */
    final class FirstClick extends LayerUI<JComponent> {
        public void installUI(JComponent c) {
            super.installUI(c);
            ((JLayer<?>) c).setLayerEventMask(AWTEvent.MOUSE_EVENT_MASK);
        }

        protected void processMouseEvent(MouseEvent e, JLayer<? extends JComponent> layer) {
            if (e.getID() == MouseEvent.MOUSE_RELEASED && fromOutside) {
                fromOutside = false;
                if (!(e.getComponent() instanceof JTextComponent)) {
                    invokeLater(BigWindow.this::giveKeysBack);
                }
            }
        }
    }

    /** After a click on the map itself, the keyboard goes back to the game. */
    void giveKeysBack() {
        Canvas canvas = watchedCanvas;
        // Open, and its window has the keyboard (its owner's last focused part gets it: setFocusableWindowState).
        if (isOpen() && window.isFocused() && !menuOpen() && canvas != null && canvas.isShowing()) {
            window.setFocusableWindowState(false);
        }
    }

    JPanel frame() {
        JPanel root = new JPanel(new BorderLayout()) {
            protected boolean processKeyBinding(KeyStroke ks, KeyEvent e, int condition, boolean pressed) {
                return pressed && closeKey(e) || super.processKeyBinding(ks, e, condition, pressed);
            }

            protected void paintComponent(Graphics graphics) {
                super.paintComponent(graphics);
                var g = (Graphics2D) graphics.create();
                g.setRenderingHint(KEY_ANTIALIASING, VALUE_ANTIALIAS_ON);
                g.setColor(GRIP_DOTS);
                PoiIcons.glyph(g, "GRIP_CORNER", getWidth() - 3, getHeight() - 3);
                g.dispose();
            }
        };
        root.setBackground(FRAME);
        root.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(255, 255, 255, 40)),
            BorderFactory.createEmptyBorder(0, EDGE - 1, BOTTOM - 1, EDGE - 1)));
        root.add(new Grip(), BorderLayout.NORTH);
        root.add(screen, BorderLayout.CENTER);
        var resizer = new Resizer(root);
        root.addMouseListener(resizer);
        root.addMouseMotionListener(resizer);
        return root;
    }

    void place() {
        Canvas canvas = watchedCanvas;
        if (window == null || canvas == null) {
            return;
        }
        if (!canvas.isShowing()) {
            close();
            return;
        }
        Rectangle view = onScreen(canvas);
        setHostBounds(maximised ? view : fit(relative, view));
    }

    /** Screen bounds for fractions of the game view (null: the default), inside the view, at least the minimum size. */
    static Rectangle fit(Rectangle2D relative, Rectangle view) {
        var r = relative != null ? relative : new Rectangle2D.Double(0.08, 0.06, 0.84, 0.88);
        int width = (int) round(r.getWidth() * view.width), height = (int) round(r.getHeight() * view.height);
        width = min(view.width, max(min(MIN_WIDTH, view.width), width));
        height = min(view.height, max(min(MIN_HEIGHT, view.height), height));
        int x = view.x + (int) round(r.getX() * view.width), y = view.y + (int) round(r.getY() * view.height);
        x = max(view.x, min(view.x + view.width - width, x));
        y = max(view.y, min(view.y + view.height - height, y));
        return new Rectangle(x, y, width, height);
    }

    void setFromScreen(Rectangle bounds) {
        Canvas canvas = watchedCanvas;
        if (canvas == null || !canvas.isShowing() || canvas.getWidth() <= 0 || canvas.getHeight() <= 0) {
            return;
        }
        Rectangle view = onScreen(canvas), fitted = fit(fraction(bounds, view), view);
        relative = fraction(fitted, view);
        maximised = false;
        setHostBounds(fitted);
    }

    /** Screen bounds as fractions of the game view. */
    static Rectangle2D fraction(Rectangle r, Rectangle view) {
        return new Rectangle2D.Double((r.x - view.x) / (double) view.width, (r.y - view.y) / (double) view.height,
            r.width / (double) view.width, r.height / (double) view.height);
    }

    void save() {
        saveBounds.accept(encode(relative, maximised));
    }

    static String encode(Rectangle2D relative, boolean maximised) {
        if (relative == null) {
            return maximised ? "max" : "";
        }
        return String.format(Locale.ROOT, "%.4f,%.4f,%.4f,%.4f%s", relative.getX(), relative.getY(), relative.getWidth(),
            relative.getHeight(), maximised ? ",max" : "");
    }

    /** {bounds or null, maximised}, or null when nothing usable was saved. */
    static Object[] decode(String saved) {
        if (saved == null || saved.trim().isEmpty()) {
            return null;
        }
        var parts = saved.trim().split(",");
        if (parts.length == 1 && "max".equals(parts[0])) {
            return new Object[]{null, true};
        }
        if (parts.length != 4 && parts.length != 5) {
            return null;
        }
        try {
            double x = Double.parseDouble(parts[0]), y = Double.parseDouble(parts[1]), w = Double.parseDouble(parts[2]);
            double h = Double.parseDouble(parts[3]);
            if (!(w > 0.05 && w <= 1 && h > 0.05 && h <= 1 && x >= -1 && x <= 1 && y >= -1 && y <= 1)) {
                return null;
            }
            return new Object[]{new Rectangle2D.Double(x, y, w, h), parts.length == 5 && "max".equals(parts[4])};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    void watch(Canvas canvas) {
        if (canvas == watchedCanvas) {
            return;
        }
        unwatch();
        watchedCanvas = canvas;
        canvas.addComponentListener(follower);
        watchedFrame = getWindowAncestor(canvas);
        if (watchedFrame != null) {
            watchedFrame.addComponentListener(follower);
            watchedFrame.addWindowListener(gameFocus);
        }
    }

    void unwatch() {
        if (watchedCanvas != null) {
            watchedCanvas.removeComponentListener(follower);
        }
        if (watchedFrame != null) {
            watchedFrame.removeComponentListener(follower);
            watchedFrame.removeWindowListener(gameFocus);
        }
        watchedCanvas = null;
        watchedFrame = null;
    }

    void dispose() {
        close();
        unwatch();
        if (window != null) {
            window.dispose();
            window = null;
        }
    }

    /** A mouse drag that moves or resizes the map; saved once let go. */
    abstract class Drag extends MouseAdapter {
        Point grab;
        Rectangle start;

        void grab(MouseEvent e) {
            grab = e.getLocationOnScreen();
            start = window.getBounds();
        }

        public void mouseReleased(MouseEvent e) {
            if (grab != null) {
                grab = null;
                save();
            }
        }
    }

    final class Grip extends JComponent {
        Grip() {
            // The grip's height: 10 pixels.
            setPreferredSize(new Dimension(100, 10));
            setCursor(getPredefinedCursor(MOVE_CURSOR));
            setToolTipText(PoiType.t("t23"));
            Drag mouse = new Drag() {
                public void mousePressed(MouseEvent e) {
                    if (isLeftMouseButton(e)) {
                        grab(e);
                    }
                }

                public void mouseDragged(MouseEvent e) {
                    if (grab == null) {
                        return;
                    }
                    if (maximised) {
                        Canvas canvas = watchedCanvas;
                        if (canvas == null || !canvas.isShowing()) {
                            return;
                        }
                        // Dragging a maximised window restores it under the mouse.
                        Rectangle restored = fit(relative, onScreen(canvas));
                        double along = (grab.x - start.x) / (double) max(1, start.width);
                        start = new Rectangle(grab.x - (int) (restored.width * along), start.y, restored.width, restored.height);
                        maximised = false;
                    }
                    Point now = e.getLocationOnScreen();
                    setFromScreen(new Rectangle(start.x + now.x - grab.x, start.y + now.y - grab.y, start.width, start.height));
                }

                public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2) {
                        toggleMaximised();
                    }
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
        }

        protected void paintComponent(Graphics graphics) {
            var g = (Graphics2D) graphics.create();
            g.setRenderingHint(KEY_ANTIALIASING, VALUE_ANTIALIAS_ON);
            g.setColor(FRAME);
            g.fillRect(0, 0, getWidth(), getHeight());
            g.setColor(GRIP_DOTS);
            PoiIcons.glyph(g, "GRIP", getWidth() / 2.0, getHeight() / 2.0);
            g.dispose();
        }
    }

    @RequiredArgsConstructor
    final class Resizer extends Drag {
        final JComponent root;
        int edges;

        /** Bits: 1 left, 2 right, 4 top, 8 bottom. */
        int edgesAt(Point p) {
            int corner = EDGE * 4, w = root.getWidth(), h = root.getHeight();
            return (p.x < EDGE || p.x < corner && p.y >= h - corner ? 1 : 0)
                | (p.x >= w - EDGE || p.x >= w - corner && p.y >= h - corner ? 2 : 0)
                | (p.y < 3 ? 4 : 0)
                | (p.y >= h - BOTTOM || p.y >= h - corner && (p.x < corner || p.x >= w - corner) ? 8 : 0);
        }

        public void mouseMoved(MouseEvent e) {
            int at = edgesAt(e.getPoint());
            root.setCursor(getPredefinedCursor(at < CURSORS.length ? CURSORS[at] : DEFAULT_CURSOR));
        }

        public void mouseExited(MouseEvent e) {
            if (grab == null) {
                root.setCursor(getDefaultCursor());
            }
        }

        public void mousePressed(MouseEvent e) {
            edges = edgesAt(e.getPoint());
            if (edges != 0 && isLeftMouseButton(e)) {
                grab(e);
            }
        }

        public void mouseDragged(MouseEvent e) {
            if (grab == null) {
                return;
            }
            Point now = e.getLocationOnScreen();
            int dx = now.x - grab.x, dy = now.y - grab.y;
            Rectangle r = new Rectangle(start);
            if ((edges & 1) != 0) {
                int width = max(MIN_WIDTH, start.width - dx);
                r.x = start.x + start.width - width;
                r.width = width;
            }
            if ((edges & 2) != 0) {
                r.width = max(MIN_WIDTH, start.width + dx);
            }
            if ((edges & 4) != 0) {
                int height = max(MIN_HEIGHT, start.height - dy);
                r.y = start.y + start.height - height;
                r.height = height;
            }
            if ((edges & 8) != 0) {
                r.height = max(MIN_HEIGHT, start.height + dy);
            }
            setFromScreen(r);
        }
    }
}
