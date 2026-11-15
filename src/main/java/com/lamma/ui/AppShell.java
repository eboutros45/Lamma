package com.lamma.ui;

import com.lamma.ui.Icons.Icon;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;
import javafx.animation.FadeTransition;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/**
 * Frame for every signed-in screen: navigation plus the current screen.
 *
 * <p>Wide windows get a sidebar; compact windows get a bottom bar. The same four navigation
 * buttons are moved between the two, so resizing never rebuilds the current screen. A 40px
 * dead band between the two thresholds stops the layout from flickering while the window
 * edge is dragged across the boundary.
 *
 * <p>Actions such as Send Money or Add Money are shown with a header that has a Back
 * button; the four destinations are not.
 */
final class AppShell extends BorderPane {

    enum Destination {
        HOME("Home", Icon.HOME, "navHome"),
        WALLETS("Wallets", Icon.WALLET, "navWallets"),
        CARDS("Cards", Icon.CARD, "navCards"),
        PROFILE("Profile", Icon.PERSON, "navProfile");

        final String label;
        final Icon icon;
        final String id;

        Destination(String label, Icon icon, String id) {
            this.label = label;
            this.icon = icon;
            this.id = id;
        }
    }

    /** Switch to the sidebar at this window width or wider... */
    static final double WIDE_AT = 860;
    /** ...and back to the bottom bar only below this one. */
    static final double COMPACT_BELOW = 820;

    private final Map<Destination, ToggleButton> items = new EnumMap<>(Destination.class);
    private final StackPane contentHost = new StackPane();
    private Boolean wide;

    AppShell(Consumer<Destination> onSelect) {
        getStyleClass().add("app-shell");
        ToggleGroup group = new ToggleGroup();
        for (Destination d : Destination.values()) {
            ToggleButton item = new ToggleButton(d.label, Icons.icon(d.icon, 22));
            item.setId(d.id);
            item.getStyleClass().add("nav-item");
            item.setToggleGroup(group);
            item.setMaxWidth(Double.MAX_VALUE);
            item.setTooltip(new Tooltip(d.label));
            item.setOnAction(event -> {
                item.setSelected(true); // a destination can't be "unselected" by clicking it again
                onSelect.accept(d);
            });
            items.put(d, item);
        }
        contentHost.getStyleClass().add("content-host");
        setCenter(contentHost);
    }

    boolean isWide() {
        return Boolean.TRUE.equals(wide);
    }

    /** Chooses sidebar or bottom bar for the given window width (with hysteresis). */
    void updateLayout(double windowWidth) {
        boolean newWide = wide == null ? windowWidth >= WIDE_AT
                : wide ? windowWidth >= COMPACT_BELOW : windowWidth >= WIDE_AT;
        if (wide != null && newWide == wide) {
            return;
        }
        wide = newWide;
        if (newWide) {
            VBox sidebar = new VBox();
            sidebar.getStyleClass().add("sidebar");
            Node brand = Screens.appTitle();
            VBox.setMargin(brand, new javafx.geometry.Insets(0, 0, 18, 6));
            ((HBox) brand).setAlignment(Pos.CENTER_LEFT);
            sidebar.getChildren().add(brand);
            for (ToggleButton item : items.values()) {
                item.setContentDisplay(ContentDisplay.LEFT);
                sidebar.getChildren().add(item);
            }
            setBottom(null);
            setLeft(sidebar);
        } else {
            HBox bar = new HBox(4);
            bar.getStyleClass().add("bottom-nav");
            for (ToggleButton item : items.values()) {
                item.setContentDisplay(ContentDisplay.TOP);
                HBox.setHgrow(item, Priority.ALWAYS);
                bar.getChildren().add(item);
            }
            setLeft(null);
            setBottom(bar);
        }
    }

    /**
     * Shows a screen.
     *
     * @param selected  which destination is highlighted
     * @param flowTitle null for a destination; otherwise the action's title, shown with Back
     * @param back      what Back does (flows only)
     * @param scroll    wrap the screen in a scroll pane (forms); list screens scroll themselves
     */
    void show(Parent screen, Destination selected, String flowTitle, Runnable back, boolean scroll) {
        items.get(selected).setSelected(true);
        Node body = screen;
        if (scroll) {
            StackPane column = new StackPane(screen);
            column.setAlignment(Pos.TOP_CENTER);
            ScrollPane scrollPane = new ScrollPane(column);
            scrollPane.setFitToWidth(true);
            scrollPane.getStyleClass().add("activity-scroll");
            body = scrollPane;
        }
        if (flowTitle != null) {
            Button backButton = new Button();
            backButton.setId("flowBackButton");
            backButton.setGraphic(Icons.icon(Icon.BACK, 20));
            backButton.getStyleClass().add("icon-button");
            backButton.setTooltip(new Tooltip("Back"));
            backButton.setOnAction(event -> back.run());
            Label title = new Label(flowTitle);
            title.setId("flowTitle");
            HBox header = new HBox(backButton, title);
            header.getStyleClass().add("flow-header");
            VBox.setVgrow(body, Priority.ALWAYS);
            body = new VBox(header, body);
        }
        contentHost.getChildren().setAll(body);
        fadeIn(body);
    }

    /** A short fade so screen changes are noticeable but not slow. */
    static void fadeIn(Node node) {
        FadeTransition fade = new FadeTransition(Duration.millis(140), node);
        fade.setFromValue(0.0);
        fade.setToValue(1.0);
        fade.play();
    }

    Region contentHost() {
        return contentHost;
    }
}
