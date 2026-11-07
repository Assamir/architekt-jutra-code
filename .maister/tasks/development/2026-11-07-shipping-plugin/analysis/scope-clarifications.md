# Scope Clarifications (Phase 2)

## TL;DR
Scope grows by one capability: carriers and methods can be edited in place, so their IDs and the per-product opt-outs that point at them survive. The product tab shows a hint when no methods exist, and inactive methods appear with an "Inactive" badge. Showing restrictions on the product list stays out of scope.

## Key Decisions
- Inline edit for methods (name, carrier, max L/W/H) and carriers (name), saved via `objects.save` with the same id — delete+recreate would change the UUID and silently drop per-product opt-outs (critical gap).
- Product tab empty state: "No delivery methods configured. Add them in the Shipping section." — one line, no navigation API.
- Restriction visibility outside the product (detail info badge / list filter) is out of scope — recorded as follow-up (minimal implementation).
- Inactive methods (or methods of an inactive carrier) stay visible with a `tc-badge` "Inactive" and a disabled checkbox; the stored opt-out is preserved so it re-applies on reactivation — transparent, no new CSS.
- Pruning on save removes only ids of methods that no longer exist; ids of inactive methods are kept.

## Open Questions / Risks
- Follow-up candidate: product.detail.info badge or product.list.filters entry showing restricted products.
- Additional doc touchpoints to update: plugin-development.md code sample (`port: 3004`), README.md (plugin tree ~line 18, install steps ~line 71), .maister/docs/INDEX.md Plugin Development port list.
