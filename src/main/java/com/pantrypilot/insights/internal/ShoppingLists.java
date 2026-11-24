package com.pantrypilot.insights.internal;

import com.pantrypilot.shared.CatalogService;
import com.pantrypilot.shared.QuantityService;
import com.pantrypilot.shared.Unit;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
class ShoppingLists {
    record Rule(long ingredientId, String name, Unit unit, BigDecimal minimum) {}
    record Item(String name, Unit unit, BigDecimal minimum, BigDecimal available, BigDecimal needed) {}

    private final JdbcClient db;
    private final CatalogService catalog;
    private final QuantityService quantities;
    private final HouseholdPantry pantry;

    ShoppingLists(JdbcClient db, CatalogService catalog, QuantityService quantities, HouseholdPantry pantry) {
        this.db = db;
        this.catalog = catalog;
        this.quantities = quantities;
        this.pantry = pantry;
    }

    void set(long household, long user, String name, Unit unit, BigDecimal minimum) {
        pantry.visible(household, user);
        if (name == null || unit == null || minimum == null || minimum.signum() <= 0
                || minimum.compareTo(new BigDecimal("1000000000")) > 0 || minimum.stripTrailingZeros().scale() > 6) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Provide an ingredient, unit and positive minimum");
        }
        var ingredient = catalog.resolve(name).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown ingredient"));
        String update = "update insights_threshold set minimum = ?, unit = ? where household_id = ? and ingredient_id = ?";
        if (db.sql(update).params(minimum, unit.name(), household, ingredient.ingredientId()).update() == 0) {
            try {
                db.sql("insert into insights_threshold (household_id, ingredient_id, name, unit, minimum) values (?,?,?,?,?)")
                        .params(household, ingredient.ingredientId(), ingredient.canonicalName(), unit.name(), minimum).update();
            } catch (DuplicateKeyException concurrentInsert) {
                db.sql(update).params(minimum, unit.name(), household, ingredient.ingredientId()).update();
            }
        }
    }

    List<Item> shopping(long household, long user) {
        var lots = pantry.visible(household, user);
        var rules = db.sql("select ingredient_id, name, unit, minimum from insights_threshold where household_id = ? order by name")
                .param(household).query((rs, row) -> new Rule(rs.getLong(1), rs.getString(2),
                        Unit.valueOf(rs.getString(3)), rs.getBigDecimal(4))).list();
        List<Item> list = new ArrayList<>();
        for (Rule rule : rules) {
            BigDecimal stock = BigDecimal.ZERO;
            boolean complete = true;
            for (var lot : lots) {
                if (!lot.personal() && lot.ingredientId() == rule.ingredientId()) {
                    var converted = quantities.convert(lot.quantity(), lot.unit(), rule.unit(), rule.ingredientId());
                    if (converted.isPresent()) stock = stock.add(converted.get());
                    else complete = false;
                }
            }
            if (stock.compareTo(rule.minimum()) < 0) {
                list.add(new Item(rule.name(), rule.unit(), rule.minimum(), complete ? stock : null,
                        complete ? rule.minimum().subtract(stock) : null));
            }
        }
        return list;
    }

    void remove(long household, long user, String name) {
        pantry.visible(household, user);
        var ingredient = catalog.resolve(name).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown ingredient"));
        db.sql("delete from insights_threshold where household_id=? and ingredient_id=?")
                .params(household, ingredient.ingredientId()).update();
    }
}
