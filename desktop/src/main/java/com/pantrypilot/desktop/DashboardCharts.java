package com.pantrypilot.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;
import javafx.geometry.Insets;
import javafx.scene.chart.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;

final class DashboardCharts extends VBox {
    private final Label total = new Label();
    private final Label coverage = new Label();
    private final ChoiceBox<String> unit = new ChoiceBox<>();
    private final BarChart<String, Number> saved = chart("Savings by dish", "USD");
    private final BarChart<String, Number> used = chart("Average daily usage", "");
    private final BarChart<String, Number> waste = chart("Discarded quantity", "");
    private JsonNode report;

    DashboardCharts() {
        setPadding(new Insets(18));
        setSpacing(12);
        total.getStyleClass().add("metric");
        unit.getItems().addAll("COUNT", "G", "ML");
        unit.setValue("COUNT");
        unit.setOnAction(event -> updateQuantities());
        var units = new HBox(8, new Label("Quantity unit"), unit);
        var quantities = new HBox(12, used, waste);
        HBox.setHgrow(used, Priority.ALWAYS);
        HBox.setHgrow(waste, Priority.ALWAYS);
        getChildren().addAll(total, coverage, saved, units, quantities);
    }

    void show(JsonNode data) {
        report = data;
        int days = data.path("days").asInt();
        total.setText("$" + data.path("savings").asText("0.00") + " saved   ·   $" +
                data.path("wasteCost").asText("0.00") + " in priced waste");
        coverage.setText("Last " + days + " days   ·   " + data.path("unpricedMeals").asInt() +
                " meals and " + data.path("unpricedWasteLines").asInt() + " waste entries without a complete price" +
                (data.path("historyLimited").asBoolean() ? "   ·   Limited to the latest 200 changes" : ""));
        Map<String, Double> dishes = new LinkedHashMap<>();
        for (var meal : data.path("meals")) {
            var amount = meal.at("/quote/saved");
            if (amount.isNumber()) dishes.merge(meal.path("dish").asText(), amount.asDouble(), Double::sum);
        }
        plot(saved, dishes);
        updateQuantities();
    }

    private void updateQuantities() {
        if (report == null) return;
        plot(used, quantities("usage", "dailyAverage"));
        plot(waste, quantities("waste", "quantity"));
        used.getYAxis().setLabel(unit.getValue() + " / day");
        waste.getYAxis().setLabel(unit.getValue());
    }

    private Map<String, Double> quantities(String key, String amount) {
        Map<String, Double> data = new LinkedHashMap<>();
        for (var row : report.path(key)) {
            if (unit.getValue().equals(row.path("unit").asText())) data.put(row.path("name").asText(), row.path(amount).asDouble());
        }
        return data;
    }

    private static BarChart<String, Number> chart(String title, String unit) {
        var axis = new NumberAxis();
        axis.setLabel(unit);
        var chart = new BarChart<String, Number>(new CategoryAxis(), axis);
        chart.setLegendVisible(false);
        chart.setAnimated(false);
        chart.setTitle(title);
        chart.setPrefSize(420, 220);
        chart.setMinWidth(160);
        return chart;
    }

    private static void plot(BarChart<String, Number> chart, Map<String, Double> data) {
        var series = new XYChart.Series<String, Number>();
        data.forEach((name, value) -> series.getData().add(new XYChart.Data<>(name, value)));
        chart.getData().clear();
        chart.getData().add(series);
    }
}
