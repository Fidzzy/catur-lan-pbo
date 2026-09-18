module com.lanchess {
    requires javafx.controls;
    requires javafx.graphics;
    requires javafx.media;

    exports com.lanchess.client;
    exports com.lanchess.model;
    exports com.lanchess.model.pieces;
    exports com.lanchess.server;
    exports com.lanchess.bot;
}
