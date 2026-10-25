package com.hdmapreforged;

import java.awt.*;
import java.awt.image.*;
import java.util.*;
import java.util.function.*;
import javax.swing.*;
import lombok.*;

/** The map's Icons panel: how icons show, and each kind of icon on or off by itself, under its group. Swing thread. */
@RequiredArgsConstructor
final class IconPanel {
    final HdMapReforgedConfig config;
    /** Builds the panel again where it shows. */
    final Runnable changed;
    /** Writes a setting (the plugin's ConfigManager). */
    BiConsumer<String, Object> set = (key, value) -> { };
    /** Each group's kinds on the map, by name, with a picture to know them by. */
    final Map<Layer, Map<String, Supplier<BufferedImage>>> kinds = new EnumMap<>(Layer.class);
    /** Groups unfolded. */
    final Set<Layer> open = EnumSet.noneOf(Layer.class);

    void setData(MapData data) {
        kinds.clear();
        for (var poi : Poi.flatten(data.pois)) {
            for (var member : data.hidden.contains(poi) ? Collections.<Poi>emptyList() : poi.members()) {
                add(member.type.layer, member.toggle(), () -> {
                    var picture = PoiIcons.picture(member.picture);
                    return picture != null ? picture : PoiIcons.image(member.type, 15);
                });
            }
        }
        for (var icon : data.icons) {
            add(icon.group, icon.toggle, () -> {
                var sprite = GameIconSprites.element(icon.element);
                return sprite != null ? sprite : PoiIcons.image(icon.poi.type, 15);
            });
        }
        changed.run();
    }

    void add(Layer group, String name, Supplier<BufferedImage> picture) {
        kinds.computeIfAbsent(group, k -> new TreeMap<>(String.CASE_INSENSITIVE_ORDER)).putIfAbsent(name, picture);
    }

    Set<String> off() {
        return new TreeSet<>(Arrays.asList(config.iconsOff().split("\n")));
    }

    void save(Set<String> off) {
        off.remove("");
        set.accept("iconsOff", String.join("\n", off));
        changed.run();
    }

    JComponent section(int width) {
        var panel = SearchResults.column();
        panel.add(check("Quest status", "A dot on quest start icons: green finished, yellow started, red not started",
            config.questStatus(), on -> set.accept("questStatus", on)));
        panel.add(check("Hide completed quests", "No quest start icons for finished quests",
            config.hideCompletedQuests(), on -> set.accept("hideCompletedQuests", on)));
        panel.add(check("Mark what I can't use", "A red sign on shortcuts, moorings and resources whose level, quest "
            + "or unlock you lack", config.markLocked(), on -> set.accept("markLocked", on)));
        panel.add(check("Only what I can use", "Hide icons and routes whose level, quest or unlock you lack (needs "
            + "you logged in)", config.onlyUsable(), on -> set.accept("onlyUsable", on)));
        var size = new JSpinner(new SpinnerNumberModel(Math.max(10, Math.min(40, config.iconSize())), 10, 40, 1));
        size.setMaximumSize(size.getPreferredSize());
        size.addChangeListener(e -> set.accept("iconSize", size.getValue()));
        panel.add(TourPanel.row(TourPanel.text("Icon size", Floating.LIGHT, 60), size));
        var off = off();
        kinds.forEach((group, named) -> {
            boolean unfolded = open.contains(group);
            var fold = SearchResults.link(unfolded ? "▾" : "▸", () -> {
                if (!open.remove(group)) {
                    open.add(group);
                }
                changed.run();
            }, 12);
            var all = check(group.title + " (" + named.size() + ")", "Every kind below on or off",
                named.keySet().stream().noneMatch(off::contains), on -> {
                    // As saved now: the other map's panel may have changed it meanwhile.
                    var now = off();
                    now.removeAll(named.keySet());
                    if (!on) {
                        now.addAll(named.keySet());
                    }
                    save(now);
                });
            SearchResults.bold(all);
            SearchResults.add(panel, 6, TourPanel.row(fold, all));
            (unfolded ? named : Map.<String, Supplier<BufferedImage>>of()).forEach((name, picture) -> {
                var image = picture.get();
                var icon = new JLabel(new ImageIcon(image.getWidth() > 17
                    ? image.getScaledInstance(15, 15, Image.SCALE_SMOOTH) : image));
                icon.setBorder(BorderFactory.createEmptyBorder(0, 16, 0, 0));
                panel.add(TourPanel.row(icon, check(name, null, !off.contains(name), on -> {
                    var now = off();
                    if (on) {
                        now.remove(name);
                    } else {
                        now.add(name);
                    }
                    save(now);
                })));
            });
        });
        return panel;
    }

    /** {@code text} may be a kind's name from places.json: never rendered as HTML. */
    static JCheckBox check(String text, String tip, boolean on, Consumer<Boolean> action) {
        var box = new JCheckBox((String) null, on);
        box.putClientProperty("html.disable", Boolean.TRUE);
        box.setText(text);
        box.setOpaque(false);
        box.setFocusable(false);
        box.setForeground(Floating.LIGHT);
        box.setToolTipText(tip);
        box.setAlignmentX(Component.LEFT_ALIGNMENT);
        box.addActionListener(e -> action.accept(box.isSelected()));
        return box;
    }
}
