# Code Review Report: Re-verification (cycle 2)

## TL;DR
- All four prior warnings are **resolved**. W1: the prompt, schema order, `pricedAs` field, code guard and BAML `@@assert` are in place, and live Vistara moved from +1117% to FAIR +12%. W2: config, `.env.example`, both READMEs and the conventions doc now agree on `OPENAI_API_KEY` holding an OpenRouter key. W3: only a 400 with a guardrail marker maps to CONTENT_BLOCKED. W4: tests now cover a missing bearer and a host 401 with no LLM call.
- Production concern 1 (raw error logging) is **resolved**: a sanitized single line is logged. Concern 2 (`BAML_LOG`) is **resolved in docs and `.env.example`**, but the user's local `.env` still lacks it and the effect was not verified live.
- 1 new warning: `spec.md` was not amended for the deviations the fixes introduced (`pricedAs`, narrower CONTENT_BLOCKED, the code guard that nulls the deviation, and the LiteLLM change versus Out of Scope). The work-log lists them as fixes, not as spec deviations.
- The new code is correct. 7 info items, mostly edge cases of the guard and the guardrail regex. Re-ran locally: Jest 58/58, `tsc --noEmit` clean, generated `baml_client/types.ts` has `pricedAs`.

## Key Decisions
- W2 counts as resolved against the **user's decision**: keep `OPENAI_API_KEY`, holding an OpenRouter key. A variable named after one provider but holding another's key is now documented consistently, so the trap is accepted rather than removed. No file still claims that config reads `OPENROUTER_API_KEY`. The only `OPENROUTER_API_KEY` mentions left are in earlier verification reports and in `orchestrator-state.yml:199,206` (historical fix entries, superseded by work-log line 115).
- The guard's false positive (a genuine range of a mismatched product that happens to sit inside the peer range) is info only. It can only fire when the category verdict is MISMATCH, and MISMATCH already sets `needsReview`, so the only thing lost is the price percentage.
- The spec drift is a **warning**, not info. `spec.md` is the reference that completeness and spec-audit checks run against, and it now contradicts the code in four places.

## Open Questions / Risks
- When the guard fires, should the tab still show the peer-copied market range and the LLM's ABOVE/FAIR verdict? Today the range is shown unchanged and only the percentage disappears (see I-N1).
- `GUARDRAIL_MARKER` also matches against `detailed_message`. Whether BAML 0.220 puts request content in that field for HTTP errors was not checked. If it does, product text containing "blocked" could turn an ordinary 400 into CONTENT_BLOCKED (see I-N3).

**Date**: 2026-11-12
**Path**: plugins/ai-offer-analysis (baml_src/main.baml, src/domain.ts, src/pages/api/analyze.ts, src/pages/product-tab.tsx, src/__tests__/*, .env.example, README.md), litellm/config.yaml, litellm/.env.example, README.md, .maister/docs/standards/global/conventions.md
**Scope**: all (re-verification of cycle 1 fixes plus regression scan)
**Status**: ⚠️ Issues Found (0 critical, 1 warning, 7 info)

## Summary
- **Critical**: 0 issues
- **Warnings**: 1 issue (new)
- **Info**: 7 issues (new or residual)

Files analyzed: 12. No `.env` file was opened. The search for OpenRouter references excluded `**/.env`.

## Prior Findings: Resolution Status

| ID | Status | Evidence |
|----|--------|----------|
| W1 peer-copied market range | **Resolved** (residual: I-N1, I-N2, I-N7) | **Schema.** `main.baml:40-47` puts `pricedAs` and `reasoning` before `verdict` and the numeric fields, and the min/max descriptions say "for the product type in pricedAs". **Prompt.** `main.baml:112-132` makes the model identify the type first, says the range "must NOT be derived from the catalog peer prices", relabels peers as "Catalog context (... may be a different product type)", places them after the range instruction, and says to ignore different-type peers "for both the market range and the verdict". **Regression check.** `main.baml:194` adds `@@assert(tv_market_range, {{ this.price.estimatedMarketMinUsd > 500 }})`. **Code guard.** `domain.ts:265-283` (`isPeerContaminated`) applies at `domain.ts:319,336,341`. **UI.** `product-tab.tsx:211` shows "Priced as". **Tests.** `domain.test.ts:222-233` (contaminated → `deviationPct` null and confidence LOW; same range with FITS → 287). **Live.** Vistara is priced as "65-inch OLED 4K TV", FAIR +12%; LG C4 is FAIR +3%. |
| W2 shared alias / env mismatch | **Resolved** (per user decision) | Every file below says the alias is shared, the upstream is OpenRouter, the key goes in `OPENAI_API_KEY`, and how to switch back to OpenAI: `litellm/config.yaml:2-8`, `litellm/.env.example:5-9`, `README.md:69`, `conventions.md:39`, `plugins/ai-offer-analysis/README.md:106,124`. None of them claims that config reads `OPENROUTER_API_KEY`. |
| W3 every 400 → CONTENT_BLOCKED | **Resolved** (residual: I-N3) | `analyze.ts:55-56,63-68,73`: CONTENT_BLOCKED now needs `status_code === 400` **and** a marker in `message`, `detailed_message` or `raw_response`. Any other 400 falls through to `BamlError` → LLM_UNAVAILABLE. Tests cover both paths: `analyze.test.ts:259-280` (Presidio 400 → CONTENT_BLOCKED) and `analyze.test.ts:286-290` (non-guardrail 400 → LLM_UNAVAILABLE). The classification order is still correct: timeout before HTTP, because `BamlTimeoutError` extends `BamlClientHttpError`. README `:42` documents the markers. |
| W4 missing bearer / host-401 tests | **Resolved** | `analyze.test.ts:144-155`: missing bearer gives 401 UNAUTHENTICATED, with no `getProduct`, no LLM call and no `llm-call` log line. `analyze.test.ts:158-164,193-203`: host 401 from `getProduct` gives 401 UNAUTHENTICATED, with `mockAnalyzeOffer` called 0 times and `objects.save` 0 times. Together these pin the order: host JWT check first, LLM call after. |
| PR concern 1: raw error logging | **Resolved** (residual: I-N4) | `analyze.ts:88-94,192-194` (`describeError`) logs only the error name, HTTP status and the message, whitespace collapsed and cut to 200 characters. The error object, prompt and `raw_output` are never logged. The exact log line is asserted at `analyze.test.ts:275-277`. |
| PR concern 2: `BAML_LOG` | **Resolved in repo, open locally** | `plugins/ai-offer-analysis/.env.example:11-12` sets `BAML_LOG=warn`, and README `:64` explains why. Work-log line 118 says the user's local `.env` does not have it yet. The effect on BAML stdout was not observed in a live run. |
| I11 duplicate assertion | Resolved | `analyze.test.ts:137-142` has a single `toBe(400)`. |

## Guard Logic Check (`domain.ts:265-283, 313-344`)
- **Condition**: category verdict is MISMATCH, peers are not null, both bounds are non-null, `min >= peers.min × 0.95` and `max <= peers.max × 1.05`. Null peer stats or a null bound short-circuits to `false` before any arithmetic, so null handling is correct.
- **Tolerance math**: for the live failure (peers 179.99–279.99), the band is 170.99–293.99. The copied 179–279 range falls inside it, so the guard fires. The test's 175–290 range also falls inside. A real TV range (1500–2300) does not, so the guard leaves it alone. With a single peer (min equal to max), the band is ±5% around that price, which is reasonable.
- **min > max**: the guard can return true, but `computeDeviationPct` already returns null for that case, so the outcome is the same. Harmless.
- **Effect**: only `deviationPct` (forced to null) and `confidence` (forced to LOW) change. The verdict, `marketMin`/`marketMax` and `peers` are stored as returned. `needsReview` is computed from the guarded deviation, so a contaminated ABOVE_MARKET result can no longer raise the price flag. MISMATCH still sets `needsReview`, as the test at `domain.test.ts:227` asserts.
- **Old records**: `toOfferAnalysis` reads a missing `pricedAs` as `""` (`domain.ts:413`), and the tab only shows "Priced as" when the string is non-empty (`product-tab.tsx:211`). Records saved before this fix still render correctly.
- **Expected-value check**: for the FITS case, the midpoint is 232.5 and (899 − 232.5) / 232.5 = 286.6%, which rounds to 287, matching the test.

## Critical Issues
None.

## Warnings

### W-N1. `spec.md` not amended for the deviations introduced by the fix cycle
- **Location**: `implementation/spec.md:338` (price record has no `pricedAs`), `spec.md:377` and `:387` (any `BamlClientHttpError` 400 → CONTENT_BLOCKED), `spec.md:525` (test spec: "status 400 returns CONTENT_BLOCKED"), `spec.md:301` (the deviation null cases do not include the contamination guard; the confidence override to LOW is not specified anywhere), `spec.md:14` ("Verdicts from the LLM, decisions in code" now has a code-side override of LLM confidence), and the Out of Scope entry "LiteLLM or Presidio config changes" versus the committed `litellm/config.yaml` change. `spec.html` mirrors the same text at lines 319, 328 and 341.
- **Description**: work-log lines 109-115 describe what changed, but none of it is recorded as a deviation from the spec. A later completeness check or spec-audit run against `spec.md` will flag the code as non-conforming, or worse, someone may "fix" the code back to match the spec, for example by restoring the blanket 400 mapping.
- **Recommendation**: add a "Spec deviations (Phase 11 fix cycle 1)" section to `spec.md`, or amend it inline, covering:
  1. the `price.pricedAs: string` field;
  2. CONTENT_BLOCKED only for a guardrail-marked 400, with every other 400 → LLM_UNAVAILABLE;
  3. the peer-contamination guard (MISMATCH and range within peer range ±5% → `deviationPct` null, confidence LOW);
  4. the LiteLLM upstream mapping as a user-approved exception to Out of Scope.

  Regenerate `spec.html`. Fixable: true (docs only).

## Informational

### I-N1. A guarded result still shows the peer-copied range and the LLM's verdict
- **Location**: `domain.ts:334-344`; `product-tab.tsx:153-212`
- When the guard fires, the tab still shows "Estimated market range 175.00 – 290.00" and a neutral "ABOVE MARKET" badge, with "Confidence: LOW" and no explanation. If the model returned FAIR together with a peer-copied range, which is the false-negative case from the original W1, the compact section shows a green FAIR badge and "USD 2,799.00 vs market USD 175.00–290.00". That is contradictory. The live run did not hit this case.
- **Suggestion**: store a `marketRangeUnreliable: true` flag, or null the bounds when the guard fires, and render a short note ("market range unreliable: category mismatch"). Optionally map the verdict to INSUFFICIENT_DATA in code. That would also need a spec note, see W-N1.

### I-N2. Guard edge cases (accepted trade-off)
- **Location**: `domain.ts:269-283`
- **False positive**: a product in the wrong category whose genuine market range falls inside the peer range loses its deviation percentage and is forced to LOW confidence. Example: a USD 300 soundbar filed under Smart Home, where the peers run 100–450. The impact is small because MISMATCH already flags the product.
- **Not covered**: QUESTIONABLE category verdicts, and partial copies where the range only overlaps the peer range rather than sitting inside it.
- No action is needed. The behaviour should be written down in the spec (W-N1).

### I-N3. Guardrail marker is broad and also scans `detailed_message`
- **Location**: `analyze.ts:56` (`/guardrail|presidio|blocked|not allowed/i`) and `:66`
- "blocked" and "not allowed" are generic phrases. Some upstream 400s, such as a provider message saying a model or parameter is "not allowed", would be reported as a content block. `detailed_message` is also tested. If BAML includes request or prompt context in that field, product text could trigger the match. The BAML type docs only describe `raw_response` as the provider body. This was not verified against a real LiteLLM Presidio BLOCK response.
- **Suggestion**: match only `message` and `raw_response`. Prefer specific markers such as `guardrail`, `presidio`, `BlockedPiiEntity` and `blocked pii`. Confirm them once with a real CREDIT_CARD block.

### I-N4. Logged message can still carry up to 200 characters of LLM output or host body
- **Location**: `analyze.ts:89-94`
- `err.message` for `BamlValidationError` can include the start of the raw LLM output, which may echo product text. Host errors include the host response body (`plugins/server-sdk.ts:76`). Truncating to 200 characters bounds this, and these are local server logs only. This is acceptable. It is noted only because README `:113` says "no prompt or error detail" about the `llm-call` line. That statement is true for that line, but the `console.error` line does contain a short message.

### I-N5. `BAML_LOG` not verified live, and the local `.env` lacks it
- **Location**: `plugins/ai-offer-analysis/.env.example:12`; work-log line 118
- Next loads `.env` into `process.env` before routes run, so `warn` should take effect. Nobody has confirmed it in the `npm run dev` output. The user should add the line to the local `.env` and confirm that no prompt is echoed during one analysis.

### I-N6. Small doc drift outside the reviewed scope
- `plugins/CLAUDE.md` ("AI Offer Analysis Plugin" section) still says "the plugin `.env` holds only `LITELLM_API_KEY`". `BAML_LOG=warn` now belongs there too.
- `plugins/ai-offer-analysis/README.md:194` says "check the logged prompt if a verdict looks odd". With `BAML_LOG=warn` the plugin no longer logs prompts, so this should point to the LiteLLM spend logs or Langfuse, where `store_prompts_in_spend_logs: true` keeps the prompt.

### I-N7. W1 items deliberately not addressed (residual from cycle 1)
- `selectPromptPeers` (`domain.ts:236-244`) still sends the 10 peers closest to the product's own price. The prompt now tells the model not to derive the range from peers, which reduces the bias, but the selection itself is unchanged.
- Code does not reconcile the LLM verdict with the range, so FAIR with a +80% deviation, or ABOVE_MARKET with the price inside the range, is still possible. `isPriceFlagged` requires both the verdict and |deviation| ≥ 30, so a contradiction never raises a false flag. It can still show a confusing badge.

## Regression Scan of Changed Code
- **Type safety**: `LlmOfferAnalysis.price.pricedAs` is optional and nullable (`domain.ts:96`). `asString(...).trim()` handles null, as the test at `domain.test.ts:239,247` shows. The generated BAML type is non-null `string` (`baml_client/types.ts:120`), so the two are compatible.
- **Security**: `pricedAs` is rendered as React text only. No new sinks were added, and no raw error detail is added to response bodies. `analyze.test.ts:272-273` still asserts that the raw body is not leaked.
- **Tests**: re-run locally, Jest 2 suites, 58/58 passed; `tsc --noEmit` clean.

## Metrics
- Max function length: `handler` about 62 lines (`analyze.ts:135-197`); `buildAnalysisRecord` 41 lines; `PriceSection` 59 lines
- Max nesting depth: 4 (JSX in `PriceSection`)
- Potential vulnerabilities: 0
- N+1 query risks: 0

## Prioritized Recommendations
1. W-N1: record the four fix-cycle deviations in `spec.md` and `spec.html`.
2. I-N5: add `BAML_LOG=warn` to the local plugin `.env` and confirm in one run that BAML no longer echoes the prompt.
3. I-N3: narrow `GUARDRAIL_MARKER`, drop `detailed_message` from the match, and confirm against a real Presidio BLOCK response.
4. I-N1: mark or hide the market range when the guard fires, so a guarded result does not show the copied range unexplained.
5. I-N6: fix the two small doc lines in `plugins/CLAUDE.md` and the plugin README.
