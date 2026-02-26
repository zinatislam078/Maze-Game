module com.example.mazegame {
    requires javafx.controls;
    requires javafx.fxml;


    opens com.example.mazegame to javafx.fxml;
    exports com.example.mazegame;
}