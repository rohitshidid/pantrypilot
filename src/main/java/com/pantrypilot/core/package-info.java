/**
 * Core Inventory, Households and Trust module (Prachiti Kulkarni).
 *
 * <p>Owns the ingredient catalog and unit model, households and roles, the
 * confirmation queue, and the inventory itself: lots plus an append-only
 * ledger that is both the audit log and the basis for undo.
 *
 * <p>Everything under {@code internal} is private to this module. Other modules
 * use it only through the shared interfaces it implements
 * ({@link com.pantrypilot.shared.CatalogService},
 * {@link com.pantrypilot.shared.QuantityService},
 * {@link com.pantrypilot.shared.PendingChangeService},
 * {@link com.pantrypilot.shared.InventoryQuery}) and the events it publishes
 * from {@code com.pantrypilot.shared.events}.
 */
package com.pantrypilot.core;
