package com.pantrypilot.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Function;
import javafx.application.Application;
import javafx.concurrent.Task;
import javafx.geometry.*;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;

public class Dashboard extends Application {
    private final DashboardClient api;
    private final TextField household = new TextField("1");
    private final TextField member = new TextField("1");
    private final Label status = new Label();
    private final TabPane tabs = new TabPane();
    private final DashboardCharts charts = new DashboardCharts();
    private final ListView<String> shopping = list("Shared stock meets all configured thresholds.");
    private final ListView<String> expiry = list("No visible lots expire in the next seven days.");
    private final ListView<String> history = list("No visible changes.");
    private final ListView<String> recipes = list("No starter recipes match the current pantry.");
    private final TextField calendarUrl = new TextField();
    private final Button refresh = new Button("Load household");
    private long activeHousehold;
    private long activeUser;
    private HBox identity;

    public Dashboard() {
        this(new DashboardClient(URI.create(System.getenv().getOrDefault("PANTRYPILOT_SERVER", "http://localhost:8090"))));
    }

    Dashboard(DashboardClient api) {
        this.api = api;
    }

    @Override public void start(Stage stage) {
        household.setPrefColumnCount(4);
        member.setPrefColumnCount(4);
        var title = new Label("PantryPilot");
        title.getStyleClass().add("title");
        var space = new Region();
        HBox.setHgrow(space, Priority.ALWAYS);
        identity = new HBox(10, title, space, new Label("Household ID"), household, new Label("User ID"), member, refresh);
        identity.setAlignment(Pos.CENTER_LEFT);
        identity.setPadding(new Insets(16, 22, 16, 22));
        refresh.setOnAction(event -> load());
        var scroll = new ScrollPane(charts);
        scroll.setFitToWidth(true);
        tabs.getTabs().addAll(tab("Overview", scroll), tab("Shopping list", shoppingTab()),
                tab("Expiry · 7 days", expiry), tab("History", history), tab("Cook now", recipeTab()));
        var root = new BorderPane(tabs, identity, null, status, null);
        BorderPane.setMargin(status, new Insets(10, 20, 10, 20));
        var scene = new Scene(root, 1000, 760);
        scene.getStylesheets().add(Dashboard.class.getResource("dashboard.css").toExternalForm());
        stage.setTitle("PantryPilot · Household insights");
        stage.setScene(scene);
        stage.show();
        load();
    }

    private Pane shoppingTab() {
        TextField name = new TextField();
        name.setPromptText("Ingredient");
        TextField minimum = new TextField();
        minimum.setPromptText("Minimum stock");
        var unit = new ChoiceBox<String>();
        unit.getItems().addAll("COUNT", "G", "ML");
        unit.setValue("COUNT");
        var save = new Button("Save threshold");
        save.setOnAction(event -> {
            String ingredient = name.getText().strip();
            String measure = unit.getValue();
            BigDecimal amount;
            try { amount = new BigDecimal(minimum.getText().strip()); }
            catch (NumberFormatException invalid) { status.setText("Enter a numeric minimum."); return; }
            run(() -> {
                api.threshold(activeHousehold, activeUser, ingredient, measure, amount);
                return api.load(activeHousehold, activeUser);
            }, this::display);
        });
        var subscribe = new Button("Enable calendar feed");
        var remove = new Button("Remove threshold");
        remove.setOnAction(event -> {
            String ingredient = name.getText().strip();
            run(() -> {
                api.removeThreshold(activeHousehold, activeUser, ingredient);
                return api.load(activeHousehold, activeUser);
            }, this::display);
        });
        var google = new Button("Sync to Google");
        var revoke = new Button("Turn off reminders");
        calendarUrl.setEditable(false);
        calendarUrl.setPromptText("Add the enabled feed URL to Google Calendar using From URL");
        HBox.setHgrow(calendarUrl, Priority.ALWAYS);
        subscribe.setOnAction(event -> run(() -> api.subscribe(activeHousehold, activeUser, false), url -> {
            calendarUrl.setText(url);
            status.setText("Feed enabled. Google Calendar needs a server address it can reach.");
        }));
        google.setOnAction(event -> run(() -> api.subscribe(activeHousehold, activeUser, true), url -> {
            calendarUrl.setText(url);
            status.setText("Google Calendar sync enabled for the next scheduled check.");
        }));
        revoke.setOnAction(event -> run(() -> { api.unsubscribe(activeHousehold, activeUser); return true; }, done -> {
            calendarUrl.clear();
            status.setText("Feed revoked. Google reminder removal runs at the next scheduled check.");
        }));
        var box = new VBox(12, new HBox(8, name, minimum, unit, save, remove), shopping,
                new HBox(8, subscribe, google, revoke), calendarUrl);
        box.setPadding(new Insets(16));
        VBox.setVgrow(shopping, Priority.ALWAYS);
        return box;
    }

    private Pane recipeTab() {
        var url = new TextField();
        url.setPromptText("Recipe URL");
        HBox.setHgrow(url, Priority.ALWAYS);
        var check = new Button("Check ingredients");
        check.setOnAction(event -> {
            String link = url.getText().strip();
            if (link.isEmpty()) { status.setText("Enter a recipe URL."); return; }
            run(() -> api.recipe(activeHousehold, activeUser, link), this::showRecipes);
        });
        var box = new VBox(12, new HBox(8, url, check), recipes);
        box.setPadding(new Insets(16));
        VBox.setVgrow(recipes, Priority.ALWAYS);
        return box;
    }

    private void load() {
        long selectedHousehold;
        long selectedUser;
        try {
            selectedHousehold = Long.parseLong(household.getText().strip());
            selectedUser = Long.parseLong(member.getText().strip());
            if (selectedHousehold < 1 || selectedUser < 1) throw new NumberFormatException();
        } catch (NumberFormatException invalid) {
            status.setText("Enter positive numeric household and user IDs.");
            return;
        }
        activeHousehold = 0;
        calendarUrl.clear();
        tabs.setVisible(false);
        run(() -> api.load(selectedHousehold, selectedUser), data -> {
            activeHousehold = selectedHousehold;
            activeUser = selectedUser;
            display(data);
            tabs.setDisable(false);
            tabs.setVisible(true);
        });
    }

    private void display(DashboardClient.Data data) {
        charts.show(data.analytics());
        rows(shopping, data.shopping(), row -> row.path("name").asText() + "   ·   " + (row.path("needed").isNumber()
                ? "Buy " + amount(row,"needed") + " " + row.path("unit").asText()
                : "Choose a threshold unit compatible with the stock"));
        rows(expiry, data.expiry(), row -> row.path("expiresOn").asText() + "   ·   " + row.path("name").asText()
                + "   ·   " + amount(row,"quantity") + " " + row.path("unit").asText());
        rows(history, data.history(), row -> {
            List<String> entries = new ArrayList<>();
            row.path("entries").forEach(e -> entries.add(e.path("name").asText() + " " + amount(e,"delta") + " " + e.path("unit").asText()));
            return row.path("at").asText().replace('T',' ').split("\\.")[0] + "   ·   " + row.path("kind").asText()
                    + "   ·   " + String.join(", ", entries);
        });
        showRecipes(data.recipes());
        status.setText("Household " + activeHousehold + "  ·  User " + activeUser);
    }

    private void showRecipes(JsonNode values) {
        rows(recipes, values, row -> {
            List<String> missing = new ArrayList<>();
            row.path("missing").forEach(item -> missing.add(item.asText()));
            return row.path("title").asText() + "   ·   " + (missing.isEmpty() ? "Ready to cook" : "Missing " + String.join(", ", missing));
        });
    }

    private <T> void run(Callable<T> call, Consumer<T> success) {
        identity.setDisable(true);
        tabs.setDisable(true);
        status.setText("Loading…");
        var task = new Task<T>() { @Override protected T call() throws Exception { return call.call(); } };
        task.setOnSucceeded(event -> { unlock(); status.setText(""); success.accept(task.getValue()); });
        task.setOnFailed(event -> {
            unlock();
            String message = task.getException().getMessage();
            status.setText(message == null ? "Could not connect to PantryPilot." : message);
        });
        Thread.startVirtualThread(task);
    }

    private void unlock() { identity.setDisable(false); tabs.setDisable(activeHousehold == 0); }
    private static String amount(JsonNode row, String key) { return row.path(key).decimalValue().stripTrailingZeros().toPlainString(); }
    private static ListView<String> list(String empty) {
        var list = new ListView<String>();
        list.setPlaceholder(new Label(empty));
        return list;
    }
    private static void rows(ListView<String> list, JsonNode values, Function<JsonNode, String> render) {
        list.getItems().clear();
        values.forEach(row -> list.getItems().add(render.apply(row)));
    }
    private static Tab tab(String label, Node node) { var tab = new Tab(label, node); tab.setClosable(false); return tab; }
    public static void main(String[] args) { launch(args); }
}
