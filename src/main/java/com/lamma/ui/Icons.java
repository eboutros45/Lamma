package com.lamma.ui;

import javafx.scene.layout.Region;

/**
 * Small vector icons drawn as shaped regions, so their colour comes from CSS
 * ({@code .icon { -fx-background-color: ... }}) and they scale crisply.
 *
 * <p>Path data from Google's Material Icons (24x24), Apache License 2.0.
 */
final class Icons {

    enum Icon {
        HOME("M10 20v-6h4v6h5v-8h3L12 3 2 12h3v8z"),
        WALLET("M21 18v1c0 1.1-.9 2-2 2H5c-1.11 0-2-.9-2-2V5c0-1.1.89-2 2-2h14c1.1 0 2 .9 2 2v1h-9c-1.11 0-2 .9-2 2v8"
                + "c0 1.1.89 2 2 2h9zm-9-2h10V8H12v8zm4-2.5c-.83 0-1.5-.67-1.5-1.5s.67-1.5 1.5-1.5 1.5.67 1.5 1.5"
                + "-.67 1.5-1.5 1.5z"),
        CARD("M20 4H4c-1.11 0-1.99.89-1.99 2L2 18c0 1.11.89 2 2 2h16c1.11 0 2-.89 2-2V6c0-1.11-.89-2-2-2zm0 14H4"
                + "v-6h16v6zm0-10H4V6h16v2z"),
        PERSON("M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2"
                + "c0-2.66-5.33-4-8-4z"),
        SEND("M2.01 21L23 12 2.01 3 2 10l15 2-15 2z"),
        ADD("M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z"),
        BACK("M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z"),
        LOGOUT("M17 7l-1.41 1.41L18.17 11H8v2h10.17l-2.58 2.58L17 17l5-5zM4 5h8V3H4c-1.1 0-2 .9-2 2v14"
                + "c0 1.1.9 2 2 2h8v-2H4V5z"),
        CHECK_CIRCLE("M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-2 15l-5-5 1.41-1.41"
                + "L10 14.17l7.59-7.59L19 8l-9 9z"),
        CASH("M11.8 10.9c-2.27-.59-3-1.2-3-2.15 0-1.09 1.01-1.85 2.7-1.85 1.78 0 2.44.85 2.5 2.1h2.21"
                + "c-.07-1.72-1.12-3.3-3.21-3.81V3h-3v2.16c-1.94.42-3.5 1.68-3.5 3.61 0 2.31 1.91 3.46 4.7 4.13"
                + " 2.5.6 3 1.48 3 2.41 0 .69-.49 1.79-2.7 1.79-2.06 0-2.87-.92-2.98-2.1h-2.2c.12 2.19 1.76 3.42"
                + " 3.68 3.83V21h3v-2.15c1.95-.37 3.5-1.5 3.5-3.55 0-2.84-2.43-3.81-4.7-4.4z"),
        CHEVRON_RIGHT("M10 6L8.59 7.41 13.17 12l-4.58 4.59L10 18l6-6z"),
        RECEIVED("M20 5.41L18.59 4 7 15.59V9H5v10h10v-2H8.41z"),
        SENT("M9 5v2h6.59L4 18.59 5.41 20 17 8.41V15h2V5z"),
        SEARCH("M15.5 14h-.79l-.28-.27C15.41 12.59 16 11.11 16 9.5 16 5.91 13.09 3 9.5 3S3 5.91 3 9.5 5.91 16"
                + " 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5"
                + " 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z"),
        BUSINESS("M12 7V3H2v18h20V7H12zM6 19H4v-2h2v2zm0-4H4v-2h2v2zm0-4H4V9h2v2zm0-4H4V5h2v2zm4 12H8v-2h2v2z"
                + "m0-4H8v-2h2v2zm0-4H8V9h2v2zm0-4H8V5h2v2zm10 12h-8v-2h2v-2h-2v-2h2v-2h-2V9h8v10zm-2-8h-2v2h2v-2z"
                + "m0 4h-2v2h2v-2z"),
        GROUP("M16 11c1.66 0 2.99-1.34 2.99-3S17.66 5 16 5c-1.66 0-3 1.34-3 3s1.34 3 3 3zm-8 0c1.66 0 2.99-1.34"
                + " 2.99-3S9.66 5 8 5C6.34 5 5 6.34 5 8s1.34 3 3 3zm0 2c-2.33 0-7 1.17-7 3.5V19h14v-2.5c0-2.33-4.67"
                + "-3.5-7-3.5zm8 0c-.29 0-.62.02-.97.05 1.16.84 1.97 1.97 1.97 3.45V19h6v-2.5c0-2.33-4.67-3.5-7-3.5z"),
        HISTORY("M13 3c-4.97 0-9 4.03-9 9H1l3.89 3.89.07.14L9 12H6c0-3.87 3.13-7 7-7s7 3.13 7 7-3.13 7-7 7"
                + "c-1.93 0-3.68-.79-4.94-2.06l-1.42 1.42C8.27 19.99 10.51 21 13 21c4.97 0 9-4.03 9-9s-4.03-9-9-9z"
                + "m-1 5v5l4.28 2.54.72-1.21-3.5-2.08V8H12z");

        final String path;

        Icon(String path) {
            this.path = path;
        }
    }

    private Icons() {
    }

    /** A square icon of the given size; colour it with CSS through the "icon" style class. */
    static Region icon(Icon icon, double size) {
        Region region = new Region();
        region.getStyleClass().add("icon");
        region.setStyle("-fx-shape: \"" + icon.path + "\";");
        region.setMinSize(size, size);
        region.setPrefSize(size, size);
        region.setMaxSize(size, size);
        return region;
    }
}
