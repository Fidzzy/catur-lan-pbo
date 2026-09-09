module org.example.caturku {
    requires javafx.controls;
    requires javafx.fxml;


    opens org.example.caturku to javafx.fxml;
    exports org.example.caturku;
}