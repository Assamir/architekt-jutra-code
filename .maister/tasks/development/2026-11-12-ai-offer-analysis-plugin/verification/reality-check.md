# Reality Check: AI Offer Analysis Plugin

## TL;DR
- **Status: ⚠️ Issues Found.** The plugin works end-to-end for the course task. All 6 stated requirements are met in the running stack, and every LLM call went through LiteLLM and is recorded there.
- **The real weakness is the price check (requirement 6, price vs market).** In 2 of 3 live runs the "market range" was a copy of the catalog peer range. For the mis-categorised TV this produced a confidently wrong figure: **+1117% above market, HIGH confidence**, for a 65" OLED at USD 2799, which is a normal market price.
- **LiteLLM evidence is solid but indirect.** The UI Logs page reads `LiteLLM_SpendLogs`, which holds 4 success rows tagged `ai-offer-analysis` plus 1 earlier failure row. A human has not yet looked at the panel itself; that is a 1-minute check for the user.
- Plugin Jest suite re-run here: **2 suites, 55/55 pass** (0.57 s). Endpoints on :3011 behave as specified for the 405 and 401 paths.

## Key Decisions
- **The DB rows count as evidence, but the course step is not closed until the user checks the UI.** The requirement literally says "check in the LiteLLM panel". I treated it as satisfied in substance and **open as a confirmation step** (user logs into `http://localhost:4000/ui`, Logs page). I did not count it as a gap in the implementation.
- **The +1117% finding is rated High for quality, not Critical.** `needsReview` for the TV is still correct, because the description is POOR and the category is MISMATCH. The badge, the filter and the demo flow all behave correctly. What is wrong is the price number and the reason text shown to the editor, which lists the wrong price first. The demo works, but the price check cannot be trusted whenever peers are misleading.
- **"Automatically" = auto-run when an editor opens the tab.** This was confirmed with the user in `analysis/clarifications.md`. It means products nobody opens are never analysed, and there is no bulk or on-save trigger. That is an accepted scope decision, not a gap.
- **I accept "market norms" = what the LLM knows about market prices, with no live price data.** gpt-4o-mini has no web access, so the price check can only be as good as the model's knowledge plus the catalog context. That limit should be stated in the README. It is acceptable for the course task.

## Open Questions / Risks
1. **Committed `litellm/config.yaml` now routes `gpt-4o-mini` through OpenRouter for the whole repo.** That includes `ai-description`. Anyone whose `litellm/.env` has a real OpenAI key in `OPENAI_API_KEY` (what `litellm/.env.example` implies) will get upstream 401s in both AI plugins. The user chose this mapping; the open question is whether it should be committed or kept local.
2. **Should the price check be fixed before this is called done?** A small prompt or schema change, plus 1-2 live re-runs (a few cents), would remove the peer-copying (see the action plan). Otherwise, document it as a known limitation.
3. **The plugin uses the LiteLLM master key as its API key** (`.env.example`: "Set this to LITELLM_MASTER_KEY"). That is fine for local dev, but it is an admin credential. A scoped virtual key would be the realistic setup. Low priority for the course.
4. **Nothing is committed yet.** `plugins/ai-offer-analysis/`, the 013 seed and the task folder are untracked.

---

## 1. Inputs reviewed

| Source | Used for |
|---|---|
| `implementation/work-log.md` | Claimed completion, Group 9 live-run evidence |
| `verification/spec-audit.md` (pre-implementation) | Known risks M1–M3, L1–L9; checked whether each was addressed |
| `plugins/ai-offer-analysis/{baml_src/*, src/domain.ts, src/pages/api/analyze.ts, src/pages/product-tab.tsx, manifest.json, package.json, .env.example, README.md}` | Code reading |
| `src/main/resources/db/changelog/2026/013-insert-ai-offer-analysis-plugin.yaml` | Seed |
| `litellm/config.yaml` + `git diff` | Gateway routing |
| `verification/screenshots/g9-tab-vistara-needs-review.png` | UI evidence |
| Live read-only checks (below) | Independent confirmation |

Not available (the parallel reviewers had not written them yet): `code-review-report.md`, `pragmatic-review.md`, `production-readiness*.md`, `implementation-verification.md`, `visual-fidelity.md`.

## 2. Independent checks I ran

| Check | Result |
|---|---|
| `node node_modules/jest/bin/jest.js` in the plugin | `Test Suites: 2 passed`, `Tests: 55 passed` |
| `GET :3011/product-tab`, `GET :3011/product-badge` | 200, 200 |
| `GET :3011/api/analyze` | 405 |
| `POST :3011/api/analyze` with no token | 401 `{"code":"UNAUTHENTICATED"}`. No LLM call is made, because the auth gate runs before any host or LLM call. |
| LiteLLM `/health/liveliness`, `/ui/` | 200, 200 |
| `LiteLLM_SpendLogs` total rows | **5** |
| `LiteLLM_SpendLogs` detail | 1 × failure, 18:41 (Group 2 BAML test, upstream `api.openai.com`, before the OpenRouter switch). 4 × success, 19:08–19:10, `model_group=gpt-4o-mini`, `custom_llm_provider=openrouter`. All 5 rows have `end_user=ai-offer-analysis` and `request_tags=["ai-offer-analysis"]`; tokens and spend are recorded (about USD 0.0002–0.0003 each). |
| Host `plugin_objects` (plugin `ai-offer-analysis`) | 3 analyses, all bound `PRODUCT`: ids 15 (Vistara), 1 (LG C4), 16 (Skylink) |
| Host `products.plugin_data->'ai-offer-analysis'` | 3 summaries matching the objects (`needsReview` true / false / true) |
| Egress grep (`api.openai`, `openrouter`, `anthropic`, `base_url`, `fetch(`) in plugin `src` and `baml_src` | The only LLM endpoint is `base_url "http://localhost:4000/v1"` in `clients.baml`. The only other fetches go to the host `/api/categories` and the plugin's own `/api/analyze`. |

I triggered no LLM calls and read no `.env` file or key.

## 3. Requirements vs. reality

| # | Requirement (course task) | Claimed | Reality | Verdict |
|---|---|---|---|---|
| 1 | Plugin integrated with the platform backend | Yes | Registered by the 013 seed. Uses `createServerSDK` with the user's JWT. Stores data as plugin objects plus a `setData` summary. The native list filter, badge and tab are all live (screenshots and DB rows). | ✅ |
| 2 | BAML for LLM calls with structured data | Yes | `AnalyzeOffer -> OfferAnalysis`: 4 enums, typed classes, BAML 0.220.0 pinned. Output is narrowed and clamped again in `buildAnalysisRecord`. | ✅ |
| 3 | ALL requests through LiteLLM | Yes | There is a single BAML client, `OfferAnalysisLlm`, with `base_url` `:4000/v1`. No provider SDKs and no other egress. Provider keys exist only in `litellm/.env`. Spend log rows match the `llm-call` lines 1:1. | ✅ |
| 4 | Automatically checks description correctness | Yes | Runs automatically when an editor opens the tab, if there is no stored result or the fingerprint changed (the "hidden Details view" check showed no call). The output is credible: Vistara POOR 3/10 with concrete issues; LG C4 GOOD 8/10. | ✅ |
| 5 | Verifies category fit | Yes | The suggested category is resolved by exact name against the real category list. Vistara: Smart Home → Televisions. Skylink: Home Appliances → Networking. LG C4: FITS. | ✅ |
| 6 | Assesses significant price deviation from market norms | Yes | It works in the simple case (LG C4 FAIR +3%) and the obvious case (Skylink USD 19.99, BELOW −90%). **It is wrong for the TV** (see §4.1). The "market range" also mirrors catalog peers instead of being an independent market estimate. | ⚠️ |
| 7 | Available on port 3011 | Yes | `next dev -p 3011` / `next start -p 3011`; manifest URL `:3011`; endpoints answer. | ✅ |
| 8 | Verified in the LiteLLM panel that all model traffic is logged | Yes, through the DB | 4 analyses equal 4 success rows with attribution, read from the table the UI's Logs page shows. Langfuse traces are in screenshots. **No one has looked at the LiteLLM UI itself.** | ⚠️ user confirmation |

**Functional completeness: about 90%.** Everything is built and wired. The remaining 10% is the quality of the price check and the UI confirmation step.

## 4. Reality gaps

### 4.1 [High, quality] The price "market range" is copied from catalog peers, including wrong-type peers

- **Claim (work-log G9):** "verdict is right", "deviation +1117% is distorted".
- **Reality (host DB):**
  - Vistara (65" OLED, USD 2799, wrongly in Smart Home): `marketMin 179.99, marketMax 279.99`, which is exactly the Smart Home peer min and max (Echo Hub, Hue, Nest). The result is `ABOVE_MARKET`, `deviationPct 1117`, confidence **HIGH**. The reasoning says "significantly higher than typical peers, which range from USD 179.99 to USD 279.99".
  - LG C4 (USD 1799.99): `marketMin 1299.99, marketMax 2199.99`, which is again exactly the Televisions peer range.
  - Skylink: `100–300`, the only independent estimate, because its Home Appliances peers were ignored.
- **Gap:**
  - The prompt says "Ignore peers of a different product type for the price verdict" (`main.baml`). gpt-4o-mini did not follow this for the TV.
  - In the other case it echoed the peer range instead of estimating a market range. So the "market norms" check is mostly a "catalog peers" check.
  - A 65" OLED at USD 2799 is within or at the top of normal US retail. The honest verdict is FAIR or slightly ABOVE, not +1117%.
- **Why it matters:**
  - A mis-categorised product is exactly the case the plugin is meant to catch, so this error will happen whenever category and price problems occur together.
  - The editor sees a wrong number with HIGH confidence. The badge reason text puts it first: "price +1117% above market · category mismatch · poor description".
  - `needsReview` is still correct here only because the other two checks also flagged the product. A correctly described product that sits in a wrong category would be flagged only for the price, and that reason would be bogus.
- **Fix options, cheapest first:**
  1. **Schema order and wording.** Put the price assessment's market estimate before any peer reasoning: add a `productType string` field and require `estimatedMarketMin/MaxUsd` "from your general retail knowledge for this product type, NOT from the catalog peers". Then add an explicit rule: "if peers are of a different product type than `productType`, say so in the reasoning and do not use them." BAML outputs fields in schema order, so the model commits to a type and an independent range first.
  2. **Deterministic guard in `buildAnalysisRecord`.** If `category.verdict === "MISMATCH"` and the LLM range equals the assigned-category peer min/max (within rounding), downgrade the confidence to LOW and do not flag the price. This takes about 10 lines plus 1 test.
  3. Optionally, show peers in the prompt only as "catalog context", never as "market".

### 4.2 [Medium, integration and repo hygiene] The LiteLLM upstream switch is global and committed

- `git diff litellm/config.yaml`: `openai/gpt-4o-mini` → `openrouter/openai/gpt-4o-mini`, keeping `api_key: os.environ/OPENAI_API_KEY`.
- This affects `plugins/ai-description` too (`clients.baml` model `gpt-4o-mini`).
- `litellm/.env.example` still presents `OPENAI_API_KEY` as an OpenAI key. With a real OpenAI key, both AI plugins would fail upstream after this commit.
- The plugin README documents both mappings (line 106). The repo-level example file and docs do not.
- **Fix:**
  - Either keep the OpenRouter mapping out of the commit (local change only),
  - or add a separate `OPENROUTER_API_KEY` env var and a note in `litellm/.env.example`, so the variable name matches its content.

### 4.3 [Low, verification] The LiteLLM UI step is pending the user

- The DB evidence is strong. The rows the UI reads are present, correctly attributed, and match the call count.
- The literal course step ("check in the LiteLLM panel") still needs a human to log in. Because the master key is off-limits for the agent, that has to be the user.
- **Fix:** the user logs into `http://localhost:4000/ui` (admin and the master key) → Logs. Expect 4 success rows between 19:08 and 19:10 UTC (20:08–20:10 local) with tag/user `ai-offer-analysis` and model group `gpt-4o-mini`, plus one failure row at 18:41. The plugin README, Step 2, already describes this.

### 4.4 [Low] Accepted limits (documented or by design)

- **Credential:** the master key is used as the plugin API key (see Open Questions 3).
- **No live market data:** the LLM's knowledge is the only market source. It should be mentioned in the README's known limitations.
- **Staleness:** the staleness fingerprint ignores changes to the category list or peer prices (spec-audit L8; accepted).
- **Auto-run only on tab open:** there is no bulk analysis, so the "needs review" filter only covers products an editor has opened at least once. This is a real usability gap for the "find flagged offers" journey, but it is in scope by user decision.

## 5. Spec-audit risks: were they addressed?

| Audit item | Status in code |
|---|---|
| M1 hidden-iframe auto-run | Fixed: a resize/visibility gate in `product-tab.tsx` (`isVisible()` and a `resize` listener); the live run confirmed no call from the hidden tab |
| M2 TV peers vs category | Spec aligned to Smart Home peers (BAML test args). **The downstream effect is exactly gap 4.1** |
| M3 count check | README gives a timestamped psql count and a ground-truth `llm-call` log line |
| L1 error order | Done (`classifyError` checks timeout first; there is a comment) |
| L2 `/errors` import | Done |
| L3 `String(id)` self-exclusion | Done (`toPeers`) |
| L4 INSUFFICIENT_DATA for unknown brands | Done in the prompt; Skylink and Vistara got real verdicts |
| L5 INNER JOIN seed | Done |
| L7 UI helper tests | Covered (55 cases) |

## 6. Error handling and integration

- **Auth:** missing or invalid token → 401, viewer → 403, both before any host or LLM call (verified live for 401; unit tests cover 403).
- **LLM failures:** they are classified into CONTENT_BLOCKED, LLM_TIMEOUT (45 s abort), LLM_INVALID_OUTPUT and LLM_UNAVAILABLE. Nothing is saved on failure, and a sanitized message is returned. The UI shows the message, a Retry button, and a hint for a blocked card number.
- **Presidio:** the guardrail runs pre-call (work-log: 0 masked for these prompts). CREDIT_CARD BLOCK maps to CONTENT_BLOCKED (unit-tested, not exercised live).
- **Partial failure:** the object is saved, then `setData` runs. If `setData` fails, the route returns HOST_ERROR, but the object is already saved, so the badge and filter would lag. This is documented as a known limitation; acceptable.
- **Cross-iframe refresh:** BroadcastChannel tab→badge was confirmed live (screenshot `g9-badge-updated.png`).

## 7. Pragmatic action plan

| # | Task | Success criteria | Priority | Effort |
|---|---|---|---|---|
| 1 | User confirms the LiteLLM UI Logs page (4.3) | 4 success rows with tag/user `ai-offer-analysis` are visible in `:4000/ui` → Logs; optionally a screenshot is saved to `verification/screenshots/` | High (closes course step) | 5 min |
| 2 | Fix the price estimate's independence from peers (4.1): add a `productType` field before the market range, an explicit "not from peers" rule, and optionally the deterministic MISMATCH guard in `buildAnalysisRecord` with 1 Jest case | One Re-analyze of Vistara gives a market range in the hundreds-to-thousands of USD for 65" OLEDs, not 179.99–279.99, and a deviation below about 100%. LG C4 stays FAIR. Jest stays green. Exactly 2 new LiteLLM rows. | High | 1–1.5 h |
| 3 | Decide the scope of the LiteLLM upstream mapping (4.2): revert it in the commit, or add `OPENROUTER_API_KEY` to `litellm/.env.example` and `config.yaml` | A fresh clone with an OpenAI key, or with an OpenRouter key under the documented variable, works for both AI plugins | Medium | 15–30 min |
| 4 | README known limitations: price check uses LLM knowledge plus catalog context with no live market data; the filter covers only analysed products | Two bullet lines added | Low | 10 min |
| 5 | Commit the plugin, seed and docs (when the user asks) | Clean `git status` for these paths | Medium | 5 min |

## 8. Completion verdict

**⚠️ Gaps. Complete for the course task, with one quality gap and one user confirmation pending.**

- **Works end-to-end:** the backend-integrated plugin on :3011, BAML structured output, all traffic through LiteLLM with per-call attribution persisted in LiteLLM's log store and in Langfuse, and the description and category checks. Tests are green, and the live data in the DB matches the claims exactly.
- **Not production-grade:**
  - The price-vs-market check copies catalog peer ranges and produced a confidently wrong +1117% for the demo TV. Fix it with action item 2 if the price requirement must hold up to scrutiny.
  - "Visible in the LiteLLM panel" is proven at the data layer; the user should look at the UI once to close it.
