package com.pantrypilot.core.internal.pending;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pantrypilot.core.internal.CoreException;
import com.pantrypilot.core.internal.household.HouseholdService;
import com.pantrypilot.core.internal.inventory.CookEventRepository;
import com.pantrypilot.core.internal.inventory.InventoryService;
import com.pantrypilot.core.internal.inventory.InventoryService.LotView;
import com.pantrypilot.core.internal.inventory.InventoryWriter.InLine;
import com.pantrypilot.core.internal.inventory.TxnKind;
import com.pantrypilot.core.internal.pending.PendingChangeQueue.ConfirmResult;
import com.pantrypilot.core.testsupport.Kitchen;
import com.pantrypilot.shared.CatalogService;
import com.pantrypilot.shared.Direction;
import com.pantrypilot.shared.PendingChangeService;
import com.pantrypilot.shared.ProposedChange;
import com.pantrypilot.shared.ProposedLine;
import com.pantrypilot.shared.Source;
import com.pantrypilot.shared.Unit;
import com.pantrypilot.shared.events.DishCooked;
import com.pantrypilot.shared.events.InventoryChanged;
import com.pantrypilot.shared.events.ItemConsumed;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

/** AI proposals wait, are corrected, and are applied all-or-nothing when a member confirms. */
@SpringBootTest
@RecordApplicationEvents
class ConfirmationQueueTest {

    /** The interface the AI module sees. */
    @Autowired
    private PendingChangeService submissions;
    @Autowired
    private PendingChangeQueue queue;
    @Autowired
    private InventoryService inventory;
    @Autowired
    private HouseholdService households;
    @Autowired
    private CatalogService catalog;
    @Autowired
    private CookEventRepository cookEvents;
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

    private static ProposedLine line(String raw, Long ingredientId, String quantity, Unit unit, String unitPrice) {
        return new ProposedLine(raw, ingredientId, new BigDecimal(quantity), unit,
                unitPrice == null ? null : new BigDecimal(unitPrice), 0.9);
    }

    private long receipt(ProposedLine... lines) {
        return submissions.submit(new ProposedChange(h, admin, Source.RECEIPT, Direction.IN, null, null, List.of(lines)));
    }

    private long cooked(String dish, int servings, ProposedLine... lines) {
        return submissions.submit(new ProposedChange(h, admin, Source.LINK, Direction.OUT, dish, servings, List.of(lines)));
    }

    private void stock(String name, String quantity, Unit unit, String unitPrice) {
        inventory.stockIn(h, admin, List.of(new InLine(id(name), new BigDecimal(quantity), unit,
                unitPrice == null ? null : new BigDecimal(unitPrice), null, null, false)));
    }

    private BigDecimal amountOf(String name) {
        return inventory.pantry(h, admin).stream().filter(l -> l.name().equals(name))
                .map(LotView::quantity).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    void aSubmittedProposalWaitsAndChangesNothing() {
        long pendingId = receipt(line("GV WHL MLK GAL", id("milk"), "3785", Unit.ML, "0.0011"));

        assertThat(queue.listFor(h, admin)).singleElement().satisfies(p -> {
            assertThat(p.id()).isEqualTo(pendingId);
            assertThat(p.state()).isEqualTo(PendingState.PENDING);
            assertThat(p.change().lines().get(0).rawText()).isEqualTo("GV WHL MLK GAL");
            assertThat(p.lineNames()).containsExactly("milk");
        });
        assertThat(inventory.pantry(h, admin)).isEmpty();
        assertThat(inventory.history(h, admin, 10)).isEmpty();
        assertThat(events.stream(InventoryChanged.class).filter(e -> e.householdId() == h)).isEmpty();
    }

    @Test
    void confirmingAReceiptAddsEveryLineWithItsReceiptPrice() {
        long pendingId = receipt(
                line("GV WHL MLK GAL", id("milk"), "3785", Unit.ML, "0.0011"),
                line("LRG EGGS 12CT", id("egg"), "12", Unit.COUNT, "0.29"));

        ConfirmResult result = queue.confirm(pendingId, admin, true);

        assertThat(result.pending().state()).isEqualTo(PendingState.CONFIRMED);
        assertThat(result.pending().decidedBy()).isEqualTo(admin);
        assertThat(inventory.pantry(h, admin)).hasSize(2)
                .anySatisfy(l -> {
                    assertThat(l.name()).isEqualTo("egg");
                    assertThat(l.unitPrice()).isEqualByComparingTo("0.29");
                    assertThat(l.expiresOn()).isNotNull();
                });
        assertThat(inventory.history(h, admin, 10)).singleElement().satisfies(t -> {
            assertThat(t.id()).isEqualTo(result.txnId());
            assertThat(t.kind()).isEqualTo(TxnKind.CONFIRM_IN);
            assertThat(t.entries()).hasSize(2);
        });
        assertThat(events.stream(InventoryChanged.class).filter(e -> e.householdId() == h)).hasSize(2);
        assertThatThrownBy(() -> queue.confirm(pendingId, admin, true))
                .isInstanceOf(CoreException.class).hasMessageContaining("already confirmed");
    }

    @Test
    void confirmingARecipeDeductsItAndRecordsTheCookEventWithRealCost() {
        stock("lentils", "500", Unit.G, "0.004");
        stock("onion", "3", Unit.COUNT, "0.60");
        long pendingId = cooked("Dal", 2,
                line("100 g red lentils", id("lentils"), "100", Unit.G, null),
                line("1/2 onion", id("onion"), "0.5", Unit.COUNT, null));

        ConfirmResult result = queue.confirm(pendingId, admin, true);

        assertThat(result.cost()).isEqualByComparingTo("0.70");   // 100 x 0.004 + 0.5 x 0.60
        assertThat(amountOf("lentils")).isEqualByComparingTo("400");
        assertThat(amountOf("onion")).isEqualByComparingTo("2.5");
        assertThat(cookEvents.findByHouseholdIdOrderByIdDesc(h)).singleElement().satisfies(c -> {
            assertThat(c.getDishName()).isEqualTo("Dal");
            assertThat(c.getServings()).isEqualTo(2);
            assertThat(c.getIngredientCost()).isEqualByComparingTo("0.70");
            assertThat(c.getTxnId()).isEqualTo(result.txnId());
        });
        assertThat(events.stream(DishCooked.class).filter(e -> e.householdId() == h)).hasSize(1);
        assertThat(events.stream(ItemConsumed.class).filter(e -> e.householdId() == h)).hasSize(2);
    }

    /** The proposal's atomicity test: one failing line rolls back the whole proposal. */
    @Test
    void oneFailingLineRollsBackTheWholeProposal() {
        stock("lentils", "500", Unit.G, "0.004");
        stock("onion", "3", Unit.COUNT, "0.60");
        stock("garlic", "1", Unit.COUNT, null);
        long pendingId = cooked("Dal", 4,
                line("200 g red lentils", id("lentils"), "200", Unit.G, null),
                line("2 onions", id("onion"), "2", Unit.COUNT, null),
                line("4 cloves garlic", id("garlic"), "4", Unit.COUNT, null));   // only 1 in stock
        int changesBefore = inventory.history(h, admin, 50).size();

        assertThatThrownBy(() -> queue.confirm(pendingId, admin, true))
                .isInstanceOf(CoreException.class).hasMessageContaining("Not enough garlic");

        // Lentils and onions had already been deducted inside the transaction. All of it is back.
        assertThat(amountOf("lentils")).isEqualByComparingTo("500");
        assertThat(amountOf("onion")).isEqualByComparingTo("3");
        assertThat(amountOf("garlic")).isEqualByComparingTo("1");
        assertThat(inventory.history(h, admin, 50)).hasSize(changesBefore);
        assertThat(cookEvents.findByHouseholdIdOrderByIdDesc(h)).isEmpty();
        assertThat(events.stream(DishCooked.class).filter(e -> e.householdId() == h)).isEmpty();
        assertThat(events.stream(ItemConsumed.class).filter(e -> e.householdId() == h)).isEmpty();
        // ... and the proposal is still waiting, so the user can fix it and confirm again
        assertThat(queue.find(pendingId).orElseThrow().state()).isEqualTo(PendingState.PENDING);

        ConfirmResult lenient = queue.confirm(pendingId, admin, false);
        assertThat(lenient.shortfalls()).singleElement().satisfies(s -> assertThat(s.missing()).isEqualByComparingTo("3"));
        assertThat(amountOf("lentils")).isEqualByComparingTo("300");
    }

    @Test
    void aLineTheCatalogCouldNotMatchMustBeFixedBeforeConfirming() {
        long pendingId = receipt(
                line("ORG BNS 2LB", id("beans"), "907", Unit.G, "0.004"),
                line("XZQ MYSTERY ITM", null, "1", Unit.COUNT, "3.99"));

        assertThatThrownBy(() -> queue.confirm(pendingId, admin, true))
                .isInstanceOfSatisfying(CoreException.class, e -> assertThat(e.kind()).isEqualTo(CoreException.Kind.INVALID))
                .hasMessageContaining("XZQ MYSTERY ITM");
        assertThat(inventory.pantry(h, admin)).isEmpty();

        // the user says what it was, and corrects the beans to what they actually bought
        PendingView edited = queue.edit(pendingId, admin, List.of(
                line("ORG BNS 2LB", id("beans"), "450", Unit.G, "0.004"),
                line("XZQ MYSTERY ITM", id("honey"), "1", Unit.COUNT, "3.99")));
        assertThat(edited.lineNames()).containsExactly("beans", "honey");

        queue.confirm(pendingId, admin, true);
        assertThat(amountOf("beans")).isEqualByComparingTo("450");
        assertThat(amountOf("honey")).isEqualByComparingTo("1");
        assertThatThrownBy(() -> queue.edit(pendingId, admin, List.of(line("x", id("beans"), "1", Unit.G, null))))
                .isInstanceOf(CoreException.class).hasMessageContaining("already confirmed");
    }

    @Test
    void aRejectedProposalIsNeverApplied() {
        long pendingId = receipt(line("BANANAS", id("banana"), "6", Unit.COUNT, "0.25"));

        assertThat(queue.reject(pendingId, admin).state()).isEqualTo(PendingState.REJECTED);

        assertThatThrownBy(() -> queue.confirm(pendingId, admin, true))
                .isInstanceOfSatisfying(CoreException.class, e -> assertThat(e.kind()).isEqualTo(CoreException.Kind.CONFLICT));
        assertThat(inventory.pantry(h, admin)).isEmpty();
    }

    @Test
    void onlyMembersOfTheHouseholdSeeOrDecideItsProposals() {
        long pendingId = receipt(line("BANANAS", id("banana"), "6", Unit.COUNT, "0.25"));
        long stranger = Kitchen.newUser(households, "Stranger");
        long roommate = kitchen.addMember(households, "Roommate");

        assertThatThrownBy(() -> queue.listFor(h, stranger)).isInstanceOf(CoreException.class);
        assertThatThrownBy(() -> queue.findFor(pendingId, stranger)).isInstanceOf(CoreException.class);
        assertThatThrownBy(() -> queue.confirm(pendingId, stranger, true))
                .isInstanceOfSatisfying(CoreException.class, e -> assertThat(e.kind()).isEqualTo(CoreException.Kind.FORBIDDEN));
        assertThatThrownBy(() -> queue.reject(pendingId, stranger)).isInstanceOf(CoreException.class);
        assertThatThrownBy(() -> queue.confirm(987_654_321L, admin, true))
                .isInstanceOfSatisfying(CoreException.class, e -> assertThat(e.kind()).isEqualTo(CoreException.Kind.NOT_FOUND));

        // any member may confirm what another member scanned
        assertThat(queue.confirm(pendingId, roommate, true).pending().decidedBy()).isEqualTo(roommate);
    }

    @Test
    void aConfirmedProposalCanBeUndoneFromTheHistory() {
        long pendingId = receipt(line("BANANAS", id("banana"), "6", Unit.COUNT, "0.25"));
        ConfirmResult confirmed = queue.confirm(pendingId, admin, true);

        inventory.undo(h, admin, confirmed.txnId());

        assertThat(inventory.pantry(h, admin)).isEmpty();
        assertThat(inventory.history(h, admin, 10)).hasSize(2);
    }
}
