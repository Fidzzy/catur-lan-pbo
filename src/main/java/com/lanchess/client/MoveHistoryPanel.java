package com.lanchess.client;

import com.lanchess.model.Move;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.layout.VBox;

import java.util.List;

/** Panel riwayat langkah (kotak kanan-atas di frame 5 desain Figma). */
public class MoveHistoryPanel extends VBox {

    private final ListView<String> listView = new ListView<>();
    private final ObservableList<String> items = FXCollections.observableArrayList();

    public MoveHistoryPanel() {
        super(8);
        getStyleClass().add("info-panel");
        setPrefWidth(220);
        setPrefHeight(180);

        Label title = new Label("Riwayat Langkah");
        title.getStyleClass().add("section-label");

        listView.setItems(items);
        listView.getStyleClass().add("history-list");
        listView.setPrefHeight(150);

        getChildren().addAll(title, listView);
    }

    /** Refresh isi panel dari daftar Move terkini. Dipanggil setiap kali GameState berubah. */
    public void refresh(List<Move> moveHistory) {
        items.clear();
        for (int i = 0; i < moveHistory.size(); i += 2) {
            Move white = moveHistory.get(i);
            Move black = (i + 1 < moveHistory.size()) ? moveHistory.get(i + 1) : null;
            items.add(MoveNotationFormatter.formatPair((i / 2) + 1, white, black));
        }
        if (!items.isEmpty()) {
            listView.scrollTo(items.size() - 1);
        }
    }
}
