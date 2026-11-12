# Pragmatic Review: AI Offer Analysis Plugin

## TL;DR
- **Status: ✅ Appropriate.** The plugin is about 1,360 lines of non-test source, against about 310 for the `ai-description` template (roughly 4.4x). The template does one LLM task with no persistence; this plugin does three assessments, two kinds of storage, a badge, a filter, staleness tracking and an EDIT gate. Each user-decided feature is implemented in 10–25 lines.
- **No critical or high findings.** There are 2 Medium and 7 Low findings. None of them blocks completion; all are optional trims or one decision.
- **Biggest code trim:** defensive re-parsing of the plugin's own stored record (`toOfferAnalysis` and its helpers, about 60 LOC). The spec asked for it ("safe defaults"), so trimming it is optional.
- **One decision:** the shared `litellm/config.yaml` default now routes `gpt-4o-mini` to OpenRouter. This affects `ai-description` and anyone who follows `litellm/.env.example` with a real OpenAI key, and the spec lists LiteLLM config changes as out of scope.

## Key Decisions
- The reference scale is a course exercise inside a showcase repo, with `plugins/ai-description` as the template. That puts it at the "Early stage / MVP" level: favor direct code and avoid speculative layers.
- The user-decided features were judged by implementation size only, not by whether they should exist: content fingerprint, visibility gate, BroadcastChannel, deterministic `needsReview`, peer stats.
- The 11-code error taxonomy is accepted as proportional. It is one constant table plus one 16-line classifier, and it delivers the spec's sanitized-error contract.
- Spec-mandated items that are still slightly heavy are reported as Low. They are not presented as defects.

## Open Questions / Risks
- **Committed LiteLLM default (Medium, user decision needed).** `litellm/config.yaml` now maps `gpt-4o-mini` to `openrouter/openai/gpt-4o-mini`, so a fresh clone with a real OpenAI key in `OPENAI_API_KEY` gets an upstream 401 in both AI plugins. The work-log (line 49) records that the user chose this to unblock the live run, but the spec still lists "LiteLLM or Presidio config changes" as out of scope and was not amended.
- The spec is 664 lines (74 KB) for about 1.4k lines of code, and the README repeats the verification runbook from the spec. This is process weight, not a code issue.

---

## 1. Executive Summary

| Item | Value |
|---|---|
| Overall complexity | **Medium**, appropriate for scope |
| Status | ✅ Appropriate |
| Critical / High / Medium / Low | 0 / 0 / 2 / 7 |
| New runtime dependencies vs template | 0 (identical dependency list; BAML pinned exactly instead of `^`) |
| New infrastructure | None (reuses LiteLLM, Langfuse, Presidio and host plugin storage) |
| Abstraction layers | 2 (Next.js route → pure `domain.ts`). No services, repositories or factories. |

The structure is flat and direct. There is one API route, two pages, one pure domain module and one BAML function. Nothing in the plugin is an enterprise pattern, and there is no speculative extensibility: no strategy objects, no provider abstraction, no retries or fallbacks inside BAML.

## 2. Complexity Assessment

**Project scale:** course exercise (Module 3, Enterprise AI Architecture) in a showcase repo, with local-only deployment and no CI.

**Size against the template (non-test source, excluding generated code):**

| File | ai-offer-analysis | ai-description |
|---|---|---|
| `src/domain.ts` | 459 | 21 |
| API route | 177 (`api/analyze.ts`) | 76 (`api/generate.ts`) |
| Tab page | 446 | 165 |
| Badge page | 68 | — |
| BAML (`main` + `clients`) | 187 + 21 | 31 + 19 |
| **Total** | **~1,360** | **~312** |
| Tests (cases) | ~16 across 2 suites (≤ 18 budget) | 8 |

**Why the 4.4x is justified:** the plugin does three assessments instead of one generated text. It persists results twice (bound `plugin_objects` for the full record, `setData` for the badge and filter summary), where the template persists nothing. It adds a second extension-point page and a native filter, fingerprint staleness, a viewer/editor split, an EDIT gate before any host or LLM call, and peer statistics. Most of the extra lines are in `domain.ts`, and about 130 of its 459 lines are input/output shape validation (see M2).

**Size of each user-decided feature (judged on size only):**

| Feature | Location | LOC | Verdict |
|---|---|---|---|
| Content fingerprint (`cyrb53` + canonical array) | domain.ts:169-195 | ~25 | Proportional; no dependency, synchronous |
| Visibility gate | product-tab.tsx:31-33, 318-335 | ~20 | Proportional |
| BroadcastChannel tab → badge | product-tab.tsx:59-64, product-badge.tsx:7-11, 37-46 | ~20 | Proportional |
| Deterministic `needsReview` | domain.ts:251-257, 270-280 | ~18 | Proportional |
| Peer stats + prompt peers | domain.ts:205-238 | ~33 | Proportional; the closest-10 cap is a cheap guard (L5) |
| Error contract | analyze.ts:28-76 | ~48 | Proportional to the spec's 11-row contract |

## 3. Key Issues Found

### Critical
None.

### High
None.

### Medium

**M1. Shared LiteLLM default switched to OpenRouter, outside the stated scope**
- **Evidence:** `litellm/config.yaml` (diff) changes `model: openai/gpt-4o-mini` to `model: openrouter/openai/gpt-4o-mini`, still with `api_key: os.environ/OPENAI_API_KEY`. `litellm/.env.example` still presents `OPENAI_API_KEY` as an OpenAI key. The spec's "Out of Scope" section lists "LiteLLM or Presidio config changes". Work-log line 49 records that the user chose the change.
- **Problem:** this is a repo-wide default change made to unblock one local environment. `plugins/ai-description/baml_src/clients.baml:8` also calls `gpt-4o-mini`, so it is affected too. The root README now explains both options, but whatever is committed decides which kind of key works out of the box.
- **Impact:** onboarding friction for other course participants, and a consistency gap between the spec and what was delivered.
- **Recommendation:** pick one default deliberately. Either (a) revert to `openai/gpt-4o-mini` and treat OpenRouter as a local edit (already documented in README.md and the plugin README), or (b) keep OpenRouter and say so in a comment in `litellm/.env.example` and in the spec's Key Decisions. **Effort:** 5 min.

**M2. Defensive re-parsing of the plugin's own stored record**
- **Evidence:** `src/domain.ts:337-396`: `toCategoryRef`, `toPeerStats`, `asString` and `toOfferAnalysis` take about 60 LOC to rebuild an `AnalysisRecord`, field by field with defaults, from a `plugin_objects` row that only `/api/analyze` writes (through `buildAnalysisRecord`, which already narrows and clamps everything).
- **Problem:** the same shape is validated twice, on write and again on read. The spec requires it ("maps a stored object to the record with safe defaults"), so this is spec-level over-defensiveness rather than an implementation slip.
- **Impact:** about 13% of `domain.ts`, with matching cognitive load. Every future field change has to be made in three places (type, builder, reader).
- **Recommendation (optional):** trust your own write and guard only what drives behavior.
  ```ts
  // before: ~60 LOC of toCategoryRef / toPeerStats / asString / toOfferAnalysis
  // after:
  export function toOfferAnalysis(obj: PluginObject): AnalysisRecord | null {
    const data = obj.data as Partial<AnalysisRecord>;
    return typeof data.fingerprint === "string" && data.description && data.category && data.price
      ? (data as AnalysisRecord)
      : null; // treat as "not analyzed" → editor auto-run rewrites it
  }
  ```
  This saves about 45–50 LOC. A malformed row is treated as missing, so for editors it heals itself.
  **Effort:** 15 min, plus adjusting the tab call site to handle `null`.

### Low

**L1. Tab state is spread over 9 `useState` hooks and 2 refs, while the badge uses a discriminated union**
- **Evidence:** `product-tab.tsx:230-241` (`loading`, `missingProduct`, `loadFailed`, `editor`, `analysis`, `currentFingerprint`, `running`, `runReason`, `runError`) against `product-badge.tsx:5` (`BadgeState` union). The two styles were written in the same task.
- **Recommendation:** fold `loading`, `missingProduct` and `loadFailed` into one `status: "loading" | "missing" | "failed" | "ready"`. The `autoRunReason` nested ternary (lines 311-316) then reads more simply. About −8 LOC; better readability. **Effort:** 15 min.

**L2. Small redundancies in the domain and route**
- `domain.ts:413`: `(verdict === "ABOVE_MARKET" || verdict === "BELOW_MARKET") && isPriceFlagged(...)`. `isPriceFlagged` already checks the verdict.
- `domain.ts:333`: `Math.round(record.price.deviationPct)`. The value is already rounded by `computeDeviationPct` (line 248).
- `domain.ts:411` and `445-447`: the "positive verdict" set (GOOD/FITS/FAIR) is written twice. `verdictBadgeClass` could call `isCompactSection`.
- `analyze.ts:143-144`: `computePeerStats` and `selectPromptPeers` each call `toPeers` on the same list. This is negligible at catalog scale.
- **Recommendation:** collapse these opportunistically. About −5 LOC. **Effort:** 5 min.

**L3. BroadcastChannel payload field that is never read**
- **Evidence:** `product-tab.tsx:62` posts `needsReview`. `product-badge.tsx:7-11` checks only `type` and `productId` and then re-reads `getData` (the spec says the payload is only a trigger).
- **Recommendation:** drop `needsReview` from the message, or leave it, since it is a spec-defined contract and harmless. **Effort:** 1 min.

**L4. Exports used only inside `domain.ts`**
- **Evidence:** `ANALYSIS_MODEL`, `PRICE_DEVIATION_THRESHOLD_PCT`, `MAX_PEERS_IN_PROMPT`, `Verdict`, `PeerStats`, `AnalysisContext` and `isPriceFlagged` have no importer outside `domain.ts`, and `computeDeviationPct`/`computeNeedsReview`/`resolveSuggestedCategory` are imported only by tests. The test-only exports are fine. The first group is cosmetic: the spec listed the constants as "domain constants".
- **Recommendation:** none required; drop `export` from the internal-only ones if you are touching the file anyway.

**L5. Closest-10 prompt-peer selection is never triggered on seed data**
- **Evidence:** `domain.ts:230-238`. Seed categories hold only a few products each (the TV demo has 3 peers), so the `> MAX_PEERS_IN_PROMPT` branch never runs in the demo.
- **Assessment:** it is 9 lines, it is tested, and it bounds prompt size for a real catalog. **Keep it.** It is noted only for completeness.

**L6. Second copy of `canEdit`**
- **Evidence:** `domain.ts:449-459` is a port of `plugins/shipping/src/domain.ts:104`. Two copies are fine. The `plugin-auth` standard keeps permission decoding per plugin.
- **Recommendation:** if a third plugin needs it, move it into the shared `plugins/sdk.ts` instead of copying again. No action now.

**L7. Documentation weight**
- **Evidence:** the plugin README is 196 lines; about 85 of them are the LiteLLM verification runbook, with bash and PowerShell variants, which largely repeats spec.md §"LiteLLM Verification Procedure". The spec is 664 lines (74 KB) and the implementation plan is 595 lines, for about 1.4k LOC of code.
- **Assessment:** FR9/FR10 explicitly require the runbook in the README, and as a course deliverable it has value. The duplication matters because the two copies can drift. If anything, the README should be the single source of the runbook and the spec should link to it. **Effort:** none required.

## 4. Developer Experience

| Dimension | Assessment |
|---|---|
| Setup | Same as the template: `cp .env.example .env`, `npm install` (postinstall runs `baml-cli generate`), `npm run dev`. The single env var is `LITELLM_API_KEY`. ✅ |
| Feedback loop | Jest runs through `node node_modules/jest/bin/jest.js`, which works on Windows; BAML and server-sdk are mocked, so there are no network calls. ✅ |
| Errors | Sanitized `{error, code}` for users. Raw detail goes only to the server log, plus one greppable `llm-call` line per BAML call. ✅ |
| Debuggability | The `stage` variable in the route ties failures to product/context/llm/store. ✅ |
| Consistency | The tab and the badge use different state styles (L1). The registration seed follows the `012-insert-shipping-sample-data.yaml` pattern. ✅ |
| Intrusive automation | Auto-run is gated to editors, to a visible tab and to missing or stale content, at most once per iframe session, with no auto-retry. That is a reasonable cost-control design, not intrusive. ✅ |
| Shared-environment friction | The LiteLLM default change (M1). ⚠️ |

## 5. Requirements Alignment

- FR1–FR8 are implemented as specified. The code tracks the spec closely; the route's processing order matches spec steps 1–12 line for line.
- **No requirement inflation in the code.** No features were found beyond the spec: no bulk analysis, no menu entry, no deletion, no retry policy, no provider abstraction.
- **Scope deviation:** `litellm/config.yaml` (M1). It is user-approved according to the work-log, but the spec was not amended.
- **Migration** (`013-insert-ai-offer-analysis-plugin.yaml`): it is minimal and safe. It uses `ON CONFLICT DO NOTHING` (backed by `uk_products_sku`), an inner join that skips demo rows whose category is missing, a dev-only context and a rollback. The manifest in the JSONB copy matches `manifest.json`. ✅
- **Template simplifications noted:** the unused `@sdk` path alias was dropped from `tsconfig.json`, and BAML is pinned exactly. ✅

## 6. Context Consistency

| Check | Result |
|---|---|
| Same functionality implemented in multiple ways | Two state-modeling styles (L1); the positive-verdict set appears twice (L2) |
| Dead code / unused imports | None found. Every exported function has a caller in production code or tests. |
| Unused parameters / fields | `needsReview` in the BroadcastChannel message is unread (L3) |
| Half-implemented patterns | None |
| Error handling consistency | Consistent: one `FAILURES` table, one classifier, and sanitized output everywhere |
| Spec vs delivered artifacts | LiteLLM config change not reflected in the spec (M1) |

## 7. Recommended Simplifications (Top 3)

1. **Decide the committed LiteLLM default (M1).** Revert to `openai/gpt-4o-mini`, or document OpenRouter in `litellm/.env.example` and the spec. Impact: prevents a 401 on fresh clones for both AI plugins. 5 min.
2. **Trust the plugin's own stored record (M2).** Replace the about 60 LOC `toOfferAnalysis` family with a cast plus a minimal shape guard that returns `null`. Impact: −45–50 LOC in `domain.ts`, and the record shape is maintained in one fewer place. 15 min. It deviates from the spec's "safe defaults" wording, so get user agreement first.
3. **Unify tab state (L1) and collapse the small redundancies (L2).** Impact: −13 LOC, a simpler `autoRunReason`, and state handled the same way in both pages. 20 min.

## 8. Summary Statistics

| Metric | Current | After top-3 |
|---|---|---|
| Non-test source LOC (TS + BAML) | ~1,360 | ~1,300 |
| `domain.ts` LOC | 459 | ~405 |
| Tab `useState` hooks | 9 | 7 |
| Runtime dependencies | 4 (same as template) | 4 |
| Shared config files changed outside scope | 1 | 0 or 1 (documented) |

## 9. Conclusion

The implementation is **appropriately sized** for a course exercise that deliberately goes beyond the template. Complexity sits where the requirements put it: three verdicts, two storage forms, staleness, and an attributable LiteLLM path. Each user-decided feature is implemented compactly. There are no enterprise patterns, no new infrastructure and no speculative abstractions.

**Action items**
1. (User decision, 5 min) Settle the `litellm/config.yaml` default and record it (M1).
2. (Optional, 15 min) Slim down `toOfferAnalysis` (M2). This needs user agreement because the spec requires safe defaults.
3. (Optional, 20 min) Clean up L1/L2.

**Total optional effort:** about 40 minutes. Nothing here blocks verification sign-off.
