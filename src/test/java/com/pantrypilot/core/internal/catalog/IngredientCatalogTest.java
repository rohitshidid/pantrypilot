package com.pantrypilot.core.internal.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pantrypilot.core.internal.CoreException;
import com.pantrypilot.shared.CatalogMatch;
import com.pantrypilot.shared.CatalogService;
import com.pantrypilot.shared.QuantityService;
import com.pantrypilot.shared.Unit;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** The catalog seeded by Flyway, through the two shared interfaces the other modules use. */
@SpringBootTest
class IngredientCatalogTest {

    @Autowired
    private CatalogService catalog;
    @Autowired
    private QuantityService quantities;
    @Autowired
    private IngredientCatalog admin;

    private CatalogMatch resolved(String text) {
        return catalog.resolve(text).orElseThrow(() -> new AssertionError("no match for " + text));
    }

    @Test
    void namesAliasesPluralsAndOtherLanguagesMapToOneIngredient() {
        long tomato = resolved("tomato").ingredientId();
        for (String text : List.of("Tomatoes", "roma tomato", "tamatar", "  TOMATO  ")) {
            assertThat(resolved(text).ingredientId()).as(text).isEqualTo(tomato);
            assertThat(resolved(text).confidence()).as(text).isEqualTo(1.0);
        }
        assertThat(resolved("whole milk").canonicalName()).isEqualTo("milk");
    }

    @Test
    void looserMatchesAreReportedWithLowerConfidence() {
        CatalogMatch phrase = resolved("2 large roma tomatoes, diced");
        assertThat(phrase.canonicalName()).isEqualTo("tomato");
        assertThat(phrase.confidence()).isEqualTo(0.7);

        CatalogMatch typo = resolved("brocoli");
        assertThat(typo.canonicalName()).isEqualTo("broccoli");
        assertThat(typo.confidence()).isEqualTo(0.6);

        assertThat(catalog.resolve("unobtainium")).isEmpty();
        assertThat(catalog.resolve("  ")).isEmpty();
        assertThat(catalog.resolve(null)).isEmpty();
    }

    @Test
    void amountsConvertBetweenCountGramsAndMillilitres() {
        long tomato = resolved("tomato").ingredientId();   // 120 g each, no density
        long milk = resolved("milk").ingredientId();       // 1.03 g per ml, not counted

        assertThat(quantities.convert(new BigDecimal("2.5"), Unit.COUNT, Unit.G, tomato).orElseThrow())
                .isEqualByComparingTo("300");
        assertThat(quantities.convert(new BigDecimal("200"), Unit.G, Unit.COUNT, tomato).orElseThrow())
                .isEqualByComparingTo("1.667");
        assertThat(quantities.convert(new BigDecimal("500"), Unit.ML, Unit.G, milk).orElseThrow())
                .isEqualByComparingTo("515");
        assertThat(quantities.convert(new BigDecimal("7"), Unit.G, Unit.G, tomato).orElseThrow())
                .isEqualByComparingTo("7");
    }

    @Test
    void aConversionWithoutTheNeededDataIsEmptyRatherThanWrong() {
        long tomato = resolved("tomato").ingredientId();
        long milk = resolved("milk").ingredientId();

        assertThat(quantities.convert(BigDecimal.ONE, Unit.COUNT, Unit.ML, tomato)).isEmpty();
        assertThat(quantities.convert(BigDecimal.ONE, Unit.ML, Unit.COUNT, milk)).isEmpty();
        assertThat(quantities.convert(BigDecimal.ONE, Unit.G, Unit.COUNT, 999_999L)).isEmpty();
    }

    @Test
    void aNewIngredientIsUsableStraightAwayAndNamesStayUnique() {
        String name = "test fruit " + Long.toString(System.nanoTime(), 36).replaceAll("[0-9]", "x");
        var added = admin.add(name, List.of(name + " slices"), new BigDecimal("400"), null,
                StorageLocation.FRIDGE, null, 5, 300);

        assertThat(resolved(name).ingredientId()).isEqualTo(added.id());
        assertThat(resolved(name + " slices").ingredientId()).isEqualTo(added.id());
        assertThat(quantities.convert(BigDecimal.ONE, Unit.COUNT, Unit.G, added.id()).orElseThrow())
                .isEqualByComparingTo("400");
        assertThat(admin.search(name)).singleElement().satisfies(v -> assertThat(v.aliases()).hasSize(1));

        assertThatThrownBy(() -> admin.add(name, null, null, null, null, null, null, null))
                .isInstanceOfSatisfying(CoreException.class, e -> assertThat(e.kind()).isEqualTo(CoreException.Kind.CONFLICT));
        assertThatThrownBy(() -> admin.add("something new", List.of("tamatar"), null, null, null, null, null, null))
                .isInstanceOf(CoreException.class).hasMessageContaining("tomato");
    }
}
