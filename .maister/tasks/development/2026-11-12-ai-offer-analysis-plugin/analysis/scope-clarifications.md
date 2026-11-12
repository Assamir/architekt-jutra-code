# Scope Clarifications (Phase 2 decision gate)

## TL;DR
Staleness uses a content fingerprint (not the @Version `updatedAt`). The price check combines LLM knowledge with in-catalog peer statistics. The LLM sees all categories and may suggest one, validated in code. `needsReview` is a deterministic rule in code. The 013 seed adds 1-2 deliberately flawed demo products. A BroadcastChannel keeps the info badge in sync. The shared server-sdk 204 bug is left as a known issue. Static LiteLLM tags are set in the BAML client.

## Key Decisions
- Staleness (critical): content fingerprint, a hash of name|description|price|sku|category id+name, is stored with the analysis and recomputed by the tab. Auto-run happens only when it differs or no analysis exists — this avoids the re-analysis loop caused by setData bumping the @Version `updatedAt`, and catches category renames.
- Price grounding: LLM market knowledge plus same-category peer prices (min/median/max computed in code); USD assumed; verdict carries a confidence and an INSUFFICIENT_DATA option.
- Category context: all categories (name + description) are passed; the suggested category is validated against the real list in code.
- needsReview: deterministic rule in code — description POOR, or category MISMATCH, or price above/below the norm by ≥30%. Uncertain verdicts (INSUFFICIENT_DATA / QUESTIONABLE) do not flag.
- Demo data: seed 1-2 deliberately flawed products in the 013 dev-context seed (ON CONFLICT-safe, names that don't clash with host test search terms or pluginData keys).
- Badge refresh: BroadcastChannel between the same-origin (:3011) tab and badge iframes.
- Shared server-sdk.ts 204 bug: left as is (not used by this feature); recorded as a known issue.
- LiteLLM attribution: static metadata in clients.baml — tags [ai-offer-analysis], trace name, user "ai-offer-analysis". If BAML 0.220 does not forward body metadata, fall back to the `x-litellm-tags` header.

## Open Questions / Risks
- BAML 0.220 metadata passthrough to LiteLLM main-stable must be verified at implementation time.
- Presidio phone/PESEL masking may mangle digit runs (prices, SKUs) in the prompt; format prices as "USD 1799.99" and check the masked prompt in LiteLLM logs.
- A Presidio block (LiteLLM 400) must map to a readable 502 for the UI.
- The seed also loads in host tests (`spring.liquibase.contexts=dev`).
