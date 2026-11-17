package com.pantrypilot.core.internal.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pantrypilot.core.internal.CoreException;
import com.pantrypilot.core.internal.catalog.IngredientKind;
import com.pantrypilot.core.internal.catalog.StorageLocation;
import com.pantrypilot.core.internal.household.HouseholdService;
import com.pantrypilot.core.internal.inventory.InventoryService.ChangeResult;
import com.pantrypilot.core.internal.inventory.InventoryService.LotView;
import com.pantrypilot.core.internal.inventory.InventoryService.TxnView;
import com.pantrypilot.core.internal.inventory.InventoryWriter.InLine;
import com.pantrypilot.core.internal.inventory.InventoryWriter.OutLine;
import com.pantrypilot.core.testsupport.Kitchen;
import com.pantrypilot.shared.CatalogService;
import com.pantrypilot.shared.InventoryQuery;
import com.pantrypilot.shared.StockItem;
import com.pantrypilot.shared.Unit;
import com.pantrypilot.shared.events.DishCooked;
import com.pantrypilot.shared.events.InventoryChanged;
import com.pantrypilot.shared.events.ItemConsumed;
import com.pantrypilot.shared.events.ItemDiscarded;
import com.pantrypilot.shared.events.ItemExpiringSoon;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

/** Manual entry, deduction order, privacy, expiry, leftovers, the audit log and undo. */
@SpringBootTest
@RecordApplicationEvents
class InventoryLedgerTest {

    @Autowired
    private InventoryService inventory;
    @Autowired
    private InventoryQuery stock;
    @Autowired
    private HouseholdService households;
    @Autowired
    private CatalogService catalog;
    @Autowired
    private LedgerEntryRepository ledger;
    @Autowired
    private ExpiryNotifier expiryNotifier;
    @Autowired
    private ApplicationEvents events;

    private Kitchen kitchen;
    private long h;
    private long admin;

    @BeforeEach
    void freshKitchen() {
        kitchen = Kitchen.create(households);
        h = kitchen.householdId();
        admin = kitchen.adminId();
    }

    private long id(String name) {
        return catalog.resolve(name).orElseThrow().ingredientId();
    }

    private static InLine in(long ingredientId, String quantity, Unit unit) {
        return new InLine(ingredientId, new BigDecimal(quantity), unit, null, null, null, false);
    }

    private static OutLine out(long ingredientId, String quantity, Unit unit) {
        return new OutLine(ingredientId, new BigDecimal(quantity), unit);
    }

    private LotView lot(long lotId) {
        return inventory.pantry(h, admin).stream().filter(l -> l.lotId() == lotId).findFirst().orElseThrow();
    }

    /** The invariant behind the audit log: what the ledger adds up to is what the lot holds. */
    private void assertLedgerMatchesEveryLot(long userId) {
        for (LotView lot : inventory.pantry(h, userId)) {
            assertThat(ledger.balanceOfLot(lot.lotId())).as("ledger balance of lot %s", lot.lotId())
                    .isEqualByComparingTo(lot.quantity());
        }
    }

    // ---- stock in and expiry ----------------------------------------------------

    @Test
    void manualEntryCreatesALotWithAnExpiryFromWhereItIsStored() {
        long tomato = id("tomato");   // 5 days in the pantry, 10 in the fridge
        inventory.stockIn(h, admin, List.of(
                in(tomato, "4", Unit.COUNT),
                new InLine(tomato, new BigDecimal("2"), Unit.COUNT, null, StorageLocation.FRIDGE, null, false),
                new InLine(id("rice"), new BigDecimal("1000"), Unit.G, null, StorageLocation.FRIDGE, null, false),
                new InLine(tomato, BigDecimal.ONE, Unit.COUNT, null, null, LocalDate.now().plusDays(1), false)));

        List<LotView> pantry = inventory.pantry(h, admin);
        assertThat(pantry).hasSize(4);
        // soonest expiry first, no date last
        assertThat(pantry).extracting(LotView::daysLeft).containsExactly(1L, 5L, 10L, null);
        assertThat(pantry.get(1).location()).isEqualTo(StorageLocation.PANTRY);
        assertThat(inventory.expiring(h, admin, 3)).hasSize(1);
        assertThat(events.stream(InventoryChanged.class).filter(e -> e.householdId() == h)).hasSize(2);
    }

    @Test
    void badLinesAreRefusedAndNothingIsWritten() {
        assertThatThrownBy(() -> inventory.stockIn(h, admin, List.of(
                in(id("onion"), "3", Unit.COUNT),
                in(id("tomato"), "0", Unit.COUNT))))
                .isInstanceOf(CoreException.class).hasMessageContaining("more than zero");
        assertThatThrownBy(() -> inventory.stockIn(h, admin, List.of(in(987_654L, "1", Unit.COUNT))))
                .isInstanceOf(CoreException.class).hasMessageContaining("not in the catalog");

        // the valid onion line of the first call was rolled back with the bad tomato line
        assertThat(inventory.pantry(h, admin)).isEmpty();
        assertThat(inventory.history(h, admin, 50)).isEmpty();
    }

    @Test
    void stockIsReportedInCountGramsAndMillilitresAtOnce() {
        inventory.stockIn(h, admin, List.of(
                in(id("tomato"), "2", Unit.COUNT),
                in(id("tomato"), "180", Unit.G),
                in(id("milk"), "500", Unit.ML)));

        List<StockItem> items = stock.available(h, admin);
        StockItem tomato = items.stream().filter(i -> i.name().equals("tomato")).findFirst().orElseThrow();
        assertThat(tomato.grams()).isEqualByComparingTo("420");      // 2 x 120 g + 180 g
        assertThat(tomato.count()).isEqualByComparingTo("3.5");
        assertThat(tomato.millilitres()).isNull();                   // tomatoes have no density
        StockItem milk = items.stream().filter(i -> i.name().equals("milk")).findFirst().orElseThrow();
        assertThat(milk.millilitres()).isEqualByComparingTo("500");
        assertThat(milk.grams()).isEqualByComparingTo("515");
        assertThat(milk.count()).isNull();
    }

    // ---- stock out --------------------------------------------------------------

    @Test
    void deductionsUseTheLotThatExpiresFirst() {
        long milk = id("milk");
        inventory.stockIn(h, admin, List.of(
                new InLine(milk, new BigDecimal("1000"), Unit.ML, null, null, LocalDate.now().plusDays(9), false),
                new InLine(milk, new BigDecimal("400"), Unit.ML, null, null, LocalDate.now().plusDays(2), false)));

        inventory.consume(h, admin, List.of(out(milk, "600", Unit.ML)), true, null, null);

        // the 400 ml that expires in 2 days is gone; the remaining 200 came from the fresher carton
        assertThat(inventory.pantry(h, admin)).singleElement().satisfies(l -> {
            assertThat(l.quantity()).isEqualByComparingTo("800");
            assertThat(l.daysLeft()).isEqualTo(9);
        });
        assertThat(events.stream(ItemConsumed.class).filter(e -> e.householdId() == h)).singleElement()
                .satisfies(e -> assertThat(e.quantity()).isEqualByComparingTo("600"));
        assertThat(inventory.history(h, admin, 1).get(0).entries()).hasSize(2);
    }

    @Test
    void aRecipeInGramsDeductsFromItemsThatWereCounted() {
        long tomato = id("tomato");
        long lotId = lotIdOf(inventory.stockIn(h, admin, List.of(in(tomato, "5", Unit.COUNT))));

        inventory.consume(h, admin, List.of(out(tomato, "300", Unit.G)), true, null, null);   // 2.5 tomatoes

        assertThat(lot(lotId).quantity()).isEqualByComparingTo("2.5");
        assertLedgerMatchesEveryLot(admin);
    }

    @Test
    void cookingADishRecordsWhatTheIngredientsReallyCost() {
        long milk = id("milk");
        long egg = id("egg");
        inventory.stockIn(h, admin, List.of(
                new InLine(milk, new BigDecimal("1000"), Unit.ML, new BigDecimal("0.004"), null, null, false),
                new InLine(egg, new BigDecimal("12"), Unit.COUNT, new BigDecimal("0.35"), null, null, false)));

        ChangeResult cooked = inventory.consume(h, admin,
                List.of(out(milk, "250", Unit.ML), out(egg, "3", Unit.COUNT)), true, "Custard", 4);

        assertThat(cooked.cost()).isEqualByComparingTo("2.05");   // 250 x 0.004 + 3 x 0.35
        assertThat(events.stream(DishCooked.class).filter(e -> e.householdId() == h)).singleElement().satisfies(e -> {
            assertThat(e.dishName()).isEqualTo("Custard");
            assertThat(e.servings()).isEqualTo(4);
            assertThat(e.ingredientCost()).isEqualByComparingTo("2.05");
        });
    }

    @Test
    void aShortLineRollsBackEveryOtherLineOfTheSameChange() {
        long onion = id("onion");
        long garlic = id("garlic");
        long onions = lotIdOf(inventory.stockIn(h, admin, List.of(in(onion, "3", Unit.COUNT), in(garlic, "2", Unit.COUNT))));

        assertThatThrownBy(() -> inventory.consume(h, admin,
                List.of(out(onion, "2", Unit.COUNT), out(garlic, "5", Unit.COUNT)), true, "Soup", 2))
                .isInstanceOfSatisfying(CoreException.class, e -> {
                    assertThat(e.kind()).isEqualTo(CoreException.Kind.CONFLICT);
                    assertThat(e).hasMessageContaining("Not enough garlic").hasMessageContaining("short by 3");
                });

        // the two onions that were already deducted inside the transaction came back
        assertThat(lot(onions).quantity()).isEqualByComparingTo("3");
        assertThat(inventory.history(h, admin, 50)).hasSize(1);
        assertThat(events.stream(DishCooked.class).filter(e -> e.householdId() == h)).isEmpty();
        assertThat(events.stream(ItemConsumed.class).filter(e -> e.householdId() == h)).isEmpty();
    }

    @Test
    void whenNotStrictWhatIsThereIsUsedAndTheRestIsReported() {
        long onion = id("onion");
        long garlic = id("garlic");
        inventory.stockIn(h, admin, List.of(in(onion, "3", Unit.COUNT), in(garlic, "2", Unit.COUNT)));

        ChangeResult result = inventory.consume(h, admin,
                List.of(out(onion, "2", Unit.COUNT), out(garlic, "5", Unit.COUNT)), false, null, null);

        assertThat(result.shortfalls()).singleElement().satisfies(s -> {
            assertThat(s.name()).isEqualTo("garlic");
            assertThat(s.missing()).isEqualByComparingTo("3");
        });
        assertThat(inventory.pantry(h, admin)).singleElement()
                .satisfies(l -> assertThat(l.quantity()).isEqualByComparingTo("1"));
        assertThatThrownBy(() -> inventory.consume(h, admin, List.of(out(id("salmon"), "1", Unit.G)), false, null, null))
                .isInstanceOf(CoreException.class).hasMessageContaining("nothing was changed");
    }

    // ---- private items ----------------------------------------------------------

    @Test
    void privateItemsAreInvisibleToOthersAndNeverUsedByTheirRecipes() {
        long roommate = kitchen.addMember(households, "Roommate");
        long yogurt = id("yogurt");
        inventory.stockIn(h, admin, List.of(in(yogurt, "200", Unit.G)));
        long mine = lotIdOf(inventory.stockIn(h, roommate,
                List.of(new InLine(yogurt, new BigDecimal("500"), Unit.G, null, null, null, true))));

        assertThat(inventory.pantry(h, admin)).hasSize(1);
        assertThat(inventory.pantry(h, roommate)).hasSize(2);
        assertThat(stock.available(h, admin)).singleElement().satisfies(i -> assertThat(i.grams()).isEqualByComparingTo("200"));
        assertThat(stock.available(h, roommate)).singleElement().satisfies(i -> assertThat(i.grams()).isEqualByComparingTo("700"));

        // the admin's recipe needs 300 g; the roommate's private 500 g must not cover it
        assertThatThrownBy(() -> inventory.consume(h, admin, List.of(out(yogurt, "300", Unit.G)), true, null, null))
                .isInstanceOf(CoreException.class).hasMessageContaining("Not enough yogurt");
        assertThatThrownBy(() -> inventory.discard(h, admin, mine, null, "not mine"))
                .isInstanceOfSatisfying(CoreException.class, e -> assertThat(e.kind()).isEqualTo(CoreException.Kind.NOT_FOUND));
        // the owner can use both
        inventory.consume(h, roommate, List.of(out(yogurt, "650", Unit.G)), true, null, null);
        assertThat(stock.available(h, roommate)).singleElement().satisfies(i -> assertThat(i.grams()).isEqualByComparingTo("50"));
    }

    @Test
    void peopleOutsideTheHouseholdCanDoNothing() {
        long stranger = Kitchen.newUser(households, "Stranger");
        inventory.stockIn(h, admin, List.of(in(id("apple"), "3", Unit.COUNT)));

        assertThatThrownBy(() -> inventory.pantry(h, stranger)).isInstanceOf(CoreException.class);
        assertThatThrownBy(() -> inventory.stockIn(h, stranger, List.of(in(id("apple"), "1", Unit.COUNT))))
                .isInstanceOfSatisfying(CoreException.class, e -> assertThat(e.kind()).isEqualTo(CoreException.Kind.FORBIDDEN));
        assertThatThrownBy(() -> inventory.history(h, stranger, 10)).isInstanceOf(CoreException.class);
    }

    // ---- discarding, leftovers, expiry events -----------------------------------

    @Test
    void discardingRecordsWasteWithItsReason() {
        long lotId = lotIdOf(inventory.stockIn(h, admin, List.of(
                new InLine(id("spinach"), new BigDecimal("300"), Unit.G, new BigDecimal("0.01"), null, null, false))));

        ChangeResult partly = inventory.discard(h, admin, lotId, new BigDecimal("100"), "wilted");
        assertThat(partly.cost()).isEqualByComparingTo("1.00");
        assertThat(lot(lotId).quantity()).isEqualByComparingTo("200");
        assertThatThrownBy(() -> inventory.discard(h, admin, lotId, new BigDecimal("500"), null))
                .isInstanceOf(CoreException.class).hasMessageContaining("Only 200");
        inventory.discard(h, admin, lotId, null, null);   // the rest

        assertThat(inventory.pantry(h, admin)).isEmpty();
        assertThat(events.stream(ItemDiscarded.class).filter(e -> e.householdId() == h))
                .extracting(ItemDiscarded::reason).containsExactly("wilted", "discarded");
    }

    @Test
    void leftoversAreItemsWithTheirOwnShortExpiry() {
        inventory.saveLeftover(h, admin, "Dal", new BigDecimal("3"), null, null, false);
        inventory.saveLeftover(h, admin, "dal", new BigDecimal("2"), StorageLocation.FREEZER, null, false);

        List<LotView> pantry = inventory.pantry(h, admin);
        assertThat(pantry).hasSize(2).allSatisfy(l -> {
            assertThat(l.kind()).isEqualTo(IngredientKind.LEFTOVER);
            assertThat(l.name()).isEqualTo("Dal");
        });
        assertThat(pantry).extracting(LotView::daysLeft).containsExactly(4L, 90L);
        // a leftover can be eaten like anything else, but it is not an ingredient for "what can I cook"
        inventory.consume(h, admin, List.of(out(pantry.get(0).ingredientId(), "1", Unit.COUNT)), true, null, null);
        assertThat(lot(pantry.get(0).lotId()).quantity()).isEqualByComparingTo("2");
        assertThat(stock.available(h, admin)).isEmpty();
        // another household's "Dal" is a different item
        Kitchen other = Kitchen.create(households);
        inventory.saveLeftover(other.householdId(), other.adminId(), "Dal", BigDecimal.ONE, null, null, false);
        assertThat(inventory.pantry(other.householdId(), other.adminId()).get(0).ingredientId())
                .isNotEqualTo(pantry.get(0).ingredientId());
    }

    @Test
    void itemsExpiringTodayOrInTwoDaysAreAnnounced() {
        LocalDate today = LocalDate.now().plusYears(3);   // a day no other test stocks for
        long cheese = id("cheese");
        inventory.stockIn(h, admin, List.of(
                new InLine(cheese, BigDecimal.ONE, Unit.COUNT, null, null, today, false),
                new InLine(cheese, BigDecimal.ONE, Unit.COUNT, null, null, today, false),
                new InLine(id("bread"), BigDecimal.ONE, Unit.COUNT, null, null, today.plusDays(2), false),
                new InLine(id("butter"), BigDecimal.ONE, Unit.COUNT, null, null, today.plusDays(1), false)));

        assertThat(expiryNotifier.publishDue(today)).isEqualTo(2);
        assertThat(events.stream(ItemExpiringSoon.class).filter(e -> e.householdId() == h))
                .extracting(ItemExpiringSoon::ingredientId).containsExactlyInAnyOrder(cheese, id("bread"));
    }

    // ---- audit log and undo -----------------------------------------------------

    @Test
    void historyShowsWhoChangedWhatNewestFirst() {
        long roommate = kitchen.addMember(households, "Roommate");
        inventory.stockIn(h, admin, List.of(in(id("banana"), "6", Unit.COUNT)));
        inventory.consume(h, roommate, List.of(out(id("banana"), "2", Unit.COUNT)), true, null, null);

        List<TxnView> history = inventory.history(h, admin, 50);
        assertThat(history).extracting(TxnView::kind).containsExactly(TxnKind.MANUAL_OUT, TxnKind.MANUAL_IN);
        assertThat(history).extracting(TxnView::userName).containsExactly("Roommate", "Admin");
        assertThat(history.get(0).entries()).singleElement().satisfies(e -> {
            assertThat(e.name()).isEqualTo("banana");
            assertThat(e.type()).isEqualTo(EntryType.CONSUME);
            assertThat(e.delta()).isEqualByComparingTo("-2");
        });
    }

    @Test
    void undoPutsStockBackByAppendingAndNeverErasesHistory() {
        long rice = id("rice");
        long lotId = lotIdOf(inventory.stockIn(h, admin, List.of(in(rice, "1000", Unit.G))));
        ChangeResult cooked = inventory.consume(h, admin, List.of(out(rice, "300", Unit.G)), true, "Pulao", 2);
        assertThat(lot(lotId).quantity()).isEqualByComparingTo("700");

        ChangeResult undone = inventory.undo(h, admin, cooked.txnId());

        assertThat(lot(lotId).quantity()).isEqualByComparingTo("1000");
        List<TxnView> history = inventory.history(h, admin, 50);
        assertThat(history).extracting(TxnView::kind)
                .containsExactly(TxnKind.UNDO, TxnKind.MANUAL_OUT, TxnKind.MANUAL_IN);
        assertThat(history.get(0).reversesTxnId()).isEqualTo(cooked.txnId());
        assertThat(history.get(1).undoneByTxnId()).isEqualTo(undone.txnId());
        assertThat(history.get(1).entries().get(0).delta()).isEqualByComparingTo("-300");   // still there
        assertThat(history.get(0).entries().get(0).delta()).isEqualByComparingTo("300");
        assertLedgerMatchesEveryLot(admin);

        assertThatThrownBy(() -> inventory.undo(h, admin, cooked.txnId()))
                .isInstanceOf(CoreException.class).hasMessageContaining("already undone");
        assertThatThrownBy(() -> inventory.undo(h, admin, undone.txnId()))
                .isInstanceOf(CoreException.class).hasMessageContaining("cannot itself be undone");
    }

    @Test
    void addedStockCannotBeUndoneOnceSomeOfItWasUsed() {
        long sugar = id("sugar");
        ChangeResult added = inventory.stockIn(h, admin, List.of(in(sugar, "500", Unit.G)));
        ChangeResult used = inventory.consume(h, admin, List.of(out(sugar, "100", Unit.G)), true, null, null);

        assertThatThrownBy(() -> inventory.undo(h, admin, added.txnId()))
                .isInstanceOf(CoreException.class).hasMessageContaining("already been used");
        // undo the use first, then the purchase can be undone
        inventory.undo(h, admin, used.txnId());
        inventory.undo(h, admin, added.txnId());
        assertThat(inventory.pantry(h, admin)).isEmpty();
    }

    @Test
    void membersUndoOnlyTheirOwnChangesButAdminsCanUndoAnyones() {
        long roommate = kitchen.addMember(households, "Roommate");
        ChangeResult byAdmin = inventory.stockIn(h, admin, List.of(in(id("lemon"), "4", Unit.COUNT)));
        ChangeResult byRoommate = inventory.stockIn(h, roommate, List.of(in(id("lime"), "4", Unit.COUNT)));

        assertThatThrownBy(() -> inventory.undo(h, roommate, byAdmin.txnId()))
                .isInstanceOfSatisfying(CoreException.class, e -> assertThat(e.kind()).isEqualTo(CoreException.Kind.FORBIDDEN));
        inventory.undo(h, admin, byRoommate.txnId());
        assertThat(inventory.pantry(h, admin)).extracting(LotView::name).containsExactly("lemon");
    }

    private long lotIdOf(ChangeResult change) {
        return inventory.history(h, admin, 200).stream().filter(t -> t.id() == change.txnId())
                .findFirst().orElseThrow().entries().get(0).lotId();
    }
}
