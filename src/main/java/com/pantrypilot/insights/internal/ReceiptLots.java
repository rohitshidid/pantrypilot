package com.pantrypilot.insights.internal;

import com.pantrypilot.shared.events.InventoryChanged;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Public pantry responses carry prices and visibility only while a lot still has stock. */
@Service
class ReceiptLots {
    record Receipt(BigDecimal unitPrice, Long owner) {}
    private static final Logger log = LoggerFactory.getLogger(ReceiptLots.class);
    private final JdbcClient db;
    private final HouseholdPantry pantry;
    private final Environment environment;

    ReceiptLots(JdbcClient db, HouseholdPantry pantry, Environment environment) {
        this.db = db;
        this.pantry = pantry;
        this.environment = environment;
    }

    Map<Long, Receipt> observe(long household, long viewer, List<HouseholdPantry.Lot> lots) {
        Map<Long, Receipt> known = new HashMap<>();
        db.sql("select lot_id, unit_price, owner_id from insights_receipt_lot where household_id = ?")
                .param(household).query(rs -> {
                    while (rs.next()) known.put(rs.getLong(1), new Receipt(rs.getBigDecimal(2), rs.getObject(3, Long.class)));
                    return known;
                });
        for (var lot : lots) {
            if (known.containsKey(lot.lotId())) continue;
            var receipt = new Receipt(lot.unitPrice(), lot.personal() ? viewer : null);
            try {
                db.sql("insert into insights_receipt_lot (household_id,lot_id,unit_price,owner_id) values (?,?,?,?)")
                        .param(household).param(lot.lotId()).param(receipt.unitPrice()).param(receipt.owner()).update();
            } catch (DuplicateKeyException alreadyObserved) {
                // Purchase price and owner are immutable for a lot.
            }
            known.put(lot.lotId(), receipt);
        }
        return known;
    }

    @EventListener
    void afterChange(InventoryChanged change) {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)
                || !environment.containsProperty("local.server.port")) return;
        var request = attributes.getRequest();
        String actor = request.getParameter("userId");
        String observed = ReceiptLots.class.getName() + change.householdId();
        if (actor == null || request.getAttribute(observed) != null) return;
        request.setAttribute(observed, true);
        try {
            long member = Long.parseLong(actor);
            observe(change.householdId(), member, pantry.visible(change.householdId(), member));
        } catch (RuntimeException unavailable) {
            // Stock is already committed. A later dashboard read can recover lots still in stock.
            log.warn("Could not refresh Insights lot prices for household {}", change.householdId(), unavailable);
        }
    }
}
