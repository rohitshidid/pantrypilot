package com.pantrypilot.core.internal.web;

import com.pantrypilot.core.internal.CoreException;
import com.pantrypilot.shared.CatalogService;
import org.springframework.stereotype.Component;

/** A request line may name its ingredient by id or by text; this turns either into the id. */
@Component
class LineResolver {

    private final CatalogService catalog;

    LineResolver(CatalogService catalog) {
        this.catalog = catalog;
    }

    long ingredientId(Long ingredientId, String name) {
        if (ingredientId != null) {
            return ingredientId;
        }
        if (name == null || name.isBlank()) {
            throw CoreException.invalid("Every line needs an ingredientId or a name");
        }
        return catalog.resolve(name)
                .orElseThrow(() -> CoreException.invalid("\"" + name.strip() + "\" is not in the catalog. "
                        + "Add it to the catalog first, or pick the closest ingredient."))
                .ingredientId();
    }
}
