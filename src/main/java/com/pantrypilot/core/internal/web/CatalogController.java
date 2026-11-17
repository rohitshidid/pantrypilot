package com.pantrypilot.core.internal.web;

import com.pantrypilot.core.internal.CoreException;
import com.pantrypilot.core.internal.catalog.IngredientCatalog;
import com.pantrypilot.core.internal.catalog.IngredientCatalog.IngredientView;
import com.pantrypilot.core.internal.catalog.StorageLocation;
import com.pantrypilot.shared.CatalogMatch;
import com.pantrypilot.shared.Unit;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The ingredient catalog: search it, match free text, convert units, add what is missing. */
@RestController
@RequestMapping("/api/core/catalog")
public class CatalogController {

    record NewIngredient(String name, List<String> aliases, BigDecimal gramsEach, BigDecimal gramsPerMl,
            StorageLocation defaultLocation, Integer shelfDaysPantry, Integer shelfDaysFridge,
            Integer shelfDaysFreezer) {
    }

    private final IngredientCatalog catalog;

    public CatalogController(IngredientCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    List<IngredientView> search(@RequestParam(required = false) String q) {
        return catalog.search(q);
    }

    @GetMapping("/resolve")
    CatalogMatch resolve(@RequestParam String name) {
        return catalog.resolve(name)
                .orElseThrow(() -> CoreException.notFound("Nothing in the catalog is close to \"" + name + "\""));
    }

    @GetMapping("/convert")
    Map<String, Object> convert(@RequestParam long ingredientId, @RequestParam BigDecimal quantity,
            @RequestParam Unit from, @RequestParam Unit to) {
        BigDecimal result = catalog.convert(quantity, from, to, ingredientId)
                .orElseThrow(() -> CoreException.invalid("That ingredient cannot be converted from " + from + " to " + to));
        return Map.of("quantity", result, "unit", to);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    IngredientView add(@RequestBody NewIngredient body) {
        return catalog.add(body.name(), body.aliases(), body.gramsEach(), body.gramsPerMl(), body.defaultLocation(),
                body.shelfDaysPantry(), body.shelfDaysFridge(), body.shelfDaysFreezer());
    }
}
