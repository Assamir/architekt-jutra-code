# Production Readiness Report

## TL;DR
- **GO WITH MITIGATIONS for the stated target: the local-dev showcase.** No blocker in scope breaks or endangers the local stack. Cost, PII and observability controls work and were checked in a live run.
- **For a real production deployment the verdict would be NO-GO.** There are 6 production-only blockers. Most are platform-wide (localhost URLs, HTTP, LiteLLM master key, no rate limit or budget, no health check or error tracking, no deploy artifact). The repo accepts these for a local-only showcase.
- Two concerns are worth fixing now because they are cheap and touch PII and log hygiene. First, `console.error(..., err)` writes the BAML prompt and raw LLM output to the plugin log, before Presidio masks anything. Second, BAML's own runtime logging (`BAML_LOG`) is not set.
- Tests: 55/55 Jest cases pass (re-run during this check). `npm audit --omit=dev` finds 1 high and 1 moderate issue, both in the `postcss` that `next` bundles at build time. No critical issues.

## Key Decisions
- **How the target was set.** I used the production checklist, but only issues that matter inside the repo's real deployment model (local-only, no CI/CD, per `tech-stack.md`) count as blockers. Production gaps go in a separate "Production-only blockers (accepted for local-dev)" section. They do not count toward the GO decision.
- **EDIT gate counted as effective.** `canEdit()` decodes the JWT without checking its signature. The route still calls the host first (`GET /api/products/{id}`), and the host validates the token, so a forged token fails with 401 before any LLM money is spent. I treat this as a sound cost gate, not a security hole.
- **No retries treated as correct, not as a resilience gap.** The spec deliberately trades retries for exactly one LiteLLM call per analysis, plus manual Retry. That is the right choice when spend must stay controlled.
- **Secrets.** I did not open, read or print any `.env` file. I only checked that `plugins/ai-offer-analysis/.env` and `litellm/.env` are gitignored (`git check-ignore`) and that `.env.example` files are committed.

## Open Questions / Risks
- **Does Presidio fail closed?** If `presidio-analyzer` is unhealthy after LiteLLM has started, does the `default_on` pre-call guardrail reject the request, or let it through unmasked? Compose gates startup on `service_healthy`, but the behaviour at runtime was not tested.
- **BAML runtime logging.** BAML's logger (`BAML_LOG`, default level `info` per BAML docs) normally prints each call's prompt and raw output to stdout. The README says the `llm-call` line holds no prompt. That is true for the plugin's own line, but the `npm run dev` terminal may still show the full prompt from BAML. This was not checked in the live run.
- **Quality of the price verdict.** Work-log G9 found that the Vistara market range copied the Smart Home peer range, which gave a distorted +1117% deviation. The verdict was still correct. This is product quality, not deploy readiness, but it means `priceDeviationPct` can feed a filter or badge with a misleading number.
- **Images not pinned.** `compose.yml` uses `litellm/litellm:main-stable` and `presidio-*:latest`. The attribution behaviour (body `user` + `metadata.tags`) was verified on LiteLLM 1.106.6 only. A later `main-stable` could change it without notice. The README's header fallback covers this case.

**Date**: 2026-11-12
**Path**: `plugins/ai-offer-analysis/**` (excluding `node_modules`, `.next`, `baml_client`), `src/main/resources/db/changelog/2026/013-insert-ai-offer-analysis-plugin.yaml`, `litellm/config.yaml`, `compose.yml` (read-only)
**Target**: production checklist, calibrated to a local-dev-only showcase (no CI/CD)
**Status**: With Concerns

## Executive Summary
- **Recommendation**: GO with mitigations (local-dev showcase) / NO-GO (real production)
- **Overall Readiness**: 70% against the full production checklist; about 90% for the local-dev showcase
- **Deployment Risk**: Low (local-dev) / High (production as-is)
- **Blockers**: 0 in scope (plus 6 production-only blockers, accepted for local-dev). **Concerns**: 8. **Recommendations**: 6

What was verified (code reading plus live evidence in `implementation/work-log.md`, G9, 2026-11-12T19:07:31Z):
- The visibility gate works: the hidden Details view made no `llm-call`.
- The run produced 4 `llm-call` lines, all `ok`, which equals 4 × `POST /api/analyze` 200 and a delta of 4 success rows in `LiteLLM_SpendLogs`, each with `end_user=ai-offer-analysis` and `request_tags=["ai-offer-analysis"]`.
- Langfuse shows 4 `AnalyzeOffer` generations.
- Presidio guardrails ran; no false masking of `USD` prices.
- A viewer made no LLM call.

## Category Breakdown
| Category | Score | Status |
|----------|-------|--------|
| Configuration | 70% | With concerns: secrets externalized well; LiteLLM and SDK/CSS URLs hardcoded to localhost |
| Monitoring | 65% | With concerns: LLM observability is strong (one log line per call, spend logs, Langfuse); no health check or error tracking; error log dumps prompt |
| Resilience | 75% | Good: 45 s abort timeout, full error classification, sanitized responses, no-retry by design; host calls have no timeout |
| Performance / Cost | 70% | Good locally: one call per analysis, visibility gate, fingerprint cache, single auto-run, in-flight guard; no server-side rate limit, budget or `max_tokens` |
| Security | 70% | With concerns: EDIT gate before spend, Presidio pre-call guard, sanitized errors; plugin uses the LiteLLM master key; HTTP only; postcss advisory |
| Deployment | 70% | Fine for local-dev: idempotent dev-context seed with rollback, manual manifest PUT documented; no artifact or CI; rollback leaves `plugin_data` keys behind |

## Detailed Findings by Category

### 1. Configuration Management
| Check | Result | Evidence |
|-------|--------|----------|
| Env vars documented | PASS | `.env.example` documents `LITELLM_API_KEY` and an optional `HOST_BASE_URL`. README Setup covers it. |
| Secrets externalized | PASS | `clients.baml` uses `api_key env.LITELLM_API_KEY`. Provider keys exist only in `litellm/.env` (`os.environ/OPENAI_API_KEY`). Both `.env` files are gitignored (`plugins/ai-offer-analysis/.gitignore:4`, root `.gitignore:46`). There is no `api.openai.com` or provider key in the plugin. |
| No hardcoded config | CONCERN | `baml_src/clients.baml:12` `base_url "http://localhost:4000/v1"`. `src/pages/_document.tsx:7-8` loads `http://localhost:8080/assets/plugin-sdk.js` and `plugin-ui.css`. `manifest.json` and seed `url` are `http://localhost:3011`. Server-side host calls do honour `HOST_BASE_URL` (`plugins/server-sdk.ts:55`). This is the inherited `ai-description` template and is spec-sanctioned (spec, Key Decisions). |
| Config validation | CONCERN | There is no startup check. If `LITELLM_API_KEY` is missing, the problem only shows up at call time as 502 `LLM_UNAVAILABLE`. |
| Model name in one place | MINOR | `"gpt-4o-mini"` appears in both `clients.baml:10` and `ANALYSIS_MODEL` (`src/domain.ts:7`), kept in sync only by comments. |
| Provider switch isolated | PASS | Only `litellm/config.yaml` changed (`openai/` → `openrouter/openai/gpt-4o-mini`). The plugin and BAML were untouched, as designed. |

### 2. Monitoring & Observability
| Check | Result | Evidence |
|-------|--------|----------|
| Per-call log line | PASS | `analyze.ts:111-113` writes exactly one `[ai-offer-analysis] llm-call productId= outcome= durationMs=` line per BAML call, in `finally`, with no prompt. The live run confirmed 4 lines. |
| Structured logging | CONCERN | The line is key=value plain text, not JSON. That is fine for grep, not for a log pipeline. |
| No sensitive data in logs | CONCERN | `analyze.ts:174` `console.error(..., err)`: `BamlValidationError` and `BamlClientFinishReasonError` carry `prompt` and `raw_output` (`@boundaryml/baml/errors.d.ts:21-34`), and Node prints them in full. This is the plugin-side prompt, before Presidio, so a phone number or email in a description lands unmasked in the plugin log. Host errors also include the host response body (`server-sdk.ts:76`). No tokens or keys are logged. |
| BAML runtime logs | OPEN | `BAML_LOG` is not set (see Open Questions). |
| Gateway spend logs | PASS | `store_prompts_in_spend_logs: true`, attributed through the body `user` + `metadata.tags`. Verified live: 4 rows, `end_user` and `request_tags` correct. |
| Tracing | PASS | `success_callback`/`failure_callback: ["langfuse"]`. Traces are named `ai-offer-analysis`, generation `AnalyzeOffer` (e.g. trace `2569515598374ce4a271c35bc46a19c7`). |
| Metrics | N/A (local) | No Prometheus or StatsD on the plugin. LiteLLM spend and Langfuse cover LLM cost and latency. |
| Error tracking | PRODUCTION-ONLY | No Sentry or equivalent, which matches the rest of the repo. |
| Health check endpoint | PRODUCTION-ONLY | The plugin has no `/api/health`. LiteLLM has a compose healthcheck (`/health/liveliness`), and so do Presidio and litellm-db. |

### 3. Error Handling & Resilience
| Check | Result | Evidence |
|-------|--------|----------|
| Critical path wrapped | PASS | The whole handler body is in try/catch with stage tracking (`product → context → llm → store`). `classifyError` maps errors to 11 sanitized codes, and the order is correct: `BamlTimeoutError` (subclass of HTTP 408) is checked first. |
| LLM timeout | PASS | `AbortSignal.timeout(ANALYZE_TIMEOUT_MS=45000)` is passed as the BAML `signal`. Calls aborted at 45 s may still be logged and billed by LiteLLM, as the README documents. |
| Host call timeouts | CONCERN | `plugins/server-sdk.ts` `fetch` has no timeout or abort signal, so a hung host blocks the route indefinitely. The SDK is shared and out of scope for this task. |
| Retry logic | PASS (by design) | No BAML `retry_policy` and no fallback. Routing and fallback belong to LiteLLM, which gives exactly one LLM call per analysis. The UI makes at most one automatic attempt per iframe session, and after that only manual Retry. |
| Nothing saved on LLM failure | PASS | The store stage runs only after a successful LLM call (`analyze.ts:167-170`). |
| Partial failure | CONCERN (documented) | `objects.save` can succeed and `setData` then fail, which returns 502 `HOST_ERROR`. The badge and list filter stay stale until Re-analyze, because the fingerprint matches so no auto-run fires. This is listed in README Known issues. |
| Client error handling | PASS | Tab: `readRunError` and network `TypeError` handling, in-flight `useRef` guard, buttons disabled while running. Badge: `try/catch` falls back to `unavailable`. |
| Graceful shutdown | N/A | Next.js default. The route holds no state or connections. |

### 4. Performance, Scalability & Cost Control
| Check | Result | Evidence |
|-------|--------|----------|
| EDIT gate before spend | PASS | `analyze.ts:127-129`: no token gives 401 and no EDIT gives 403, both before any host or LLM call. The host validates the token signature on the first `getProduct`, which also happens before the LLM call. |
| Single auto-run / visibility gate | PASS | `product-tab.tsx:241,318-335`: `autoRunAttempted` ref plus the `isVisible()`/`resize` gate. Verified live: the hidden tab made no call, and switching tabs made no repeat call. |
| Staleness cache | PASS | A content fingerprint (cyrb53 over name, description, price, SKU, category) skips the LLM call when the analysis is current. |
| Prompt size bounded | PASS | `MAX_PEERS_IN_PROMPT = 10`, and lists in the output are capped at 4 items. |
| Server-side rate limiting / dedupe | PRODUCTION-ONLY | An EDIT user (or a script with an EDIT token) can call `POST /api/analyze` without limit. Two editors on the same product give two calls, and the last write wins. |
| Budget / spend cap | PRODUCTION-ONLY | The plugin authenticates with the LiteLLM **master key** (`.env.example:5-6`), so there is no per-plugin virtual key with `max_budget`/`rpm`. Virtual keys are out of scope in the spec. |
| Output token cap | RECOMMENDATION | `max_tokens` is not set on the client. The schema and the "max 4 items" rule bound the output only softly. |
| Request size limit | PASS | Next.js API default `bodyParser` limit is 1 MB. `productId` is validated with `^\d+$`. |
| Peer fetch | MINOR | `getProducts({category})` fetches the whole category list on every run. Fine at demo scale. |

### 5. Security Hardening
| Check | Result | Evidence |
|-------|--------|----------|
| PII guardrail | PASS | `litellm/config.yaml`: Presidio `pre_call`, `default_on: true`, CREDIT_CARD BLOCK, PHONE/EMAIL/PL_PESEL MASK. A Presidio BLOCK (HTTP 400) maps to `CONTENT_BLOCKED`. The live run confirmed the guardrail ran with no false masking of `USD 2799.00`. |
| All traffic via gateway | PASS | There is one BAML client, pointed at `:4000`. The plugin holds no provider keys and no direct provider URLs. |
| Error leakage | PASS | Clients get only `{error, code}` with fixed messages; raw details stay server-side (but see the log concern above). |
| Auth on the route | PASS | Bearer token forwarded to the host (`createServerSDK(PLUGIN_ID, undefined, req)`). There are no cookies, so no CSRF surface. A cross-origin call with `Authorization` needs a CORS preflight, and the route does not answer it. |
| Least privilege for the LLM key | PRODUCTION-ONLY | The master key is used as the client key. Anyone with the plugin `.env` has LiteLLM admin rights, including spend logs that contain prompts. |
| HTTPS | PRODUCTION-ONLY | Everything runs over HTTP on localhost, platform-wide. |
| CORS | OUT OF SCOPE | Host `SecurityConfiguration` uses `app.cors.allowed-origins` (`localhost:*` per spec). The plugin route has no CORS headers. |
| Security headers / CSP | PRODUCTION-ONLY | No `headers()` in `next.config.js`. The iframe must allow framing by the host. |
| Dependencies | CONCERN | `npm audit --omit=dev`: 1 high + 1 moderate, both `postcss` through `next@15.5.29`. They are build-time, with no user-controlled CSS, so runtime exposure is low. The full tree has 9 issues (7 low, dev-only). No critical issues. Next.js uses the Pages Router, so the RSC-specific Next advisories do not apply. |
| Dev credentials in docs | ACCEPTED | README shows dev seed credentials (`admin`/`admin123`, `sk-litellm-dev-key` default, Langfuse `admin123`). These are documented local defaults, consistent with the repo convention. |

### 6. Deployment Considerations
| Check | Result | Evidence |
|-------|--------|----------|
| Migration present | PASS | `013-insert-ai-offer-analysis-plugin.yaml` is next in sequence, named per standard, and in the `dev` context. |
| Idempotent / safe | PASS | `ON CONFLICT (id)` / `ON CONFLICT (sku) DO NOTHING`. Demo products use an `INNER JOIN` on category, so a missing category skips the row instead of failing startup. Host `./mvnw test` passed 159/159 (work-log G6). |
| Rollback | PASS with gap | Both changesets have rollbacks (plugin row + `plugin_objects`, demo products). The rollback does not strip `products.plugin_data -> 'ai-offer-analysis'` from other products. |
| Non-dev registration | PASS | Manifest `PUT` (bash + PowerShell) is documented. The seed is dev-only, so production needs a manual registration step. |
| Manifest drift | CONCERN | The seed JSONB copies `manifest.json` by hand. `ON CONFLICT DO NOTHING` never updates an existing row, which the README documents. |
| Deploy artifact / CI | PRODUCTION-ONLY | The plugin is not in `compose.yml`, has no Dockerfile and no CI. `start-dev` scripts pick it up (it has a `package.json`). |
| Rollback plan | PASS (local) | Disable via the plugin's `enabled` flag or Liquibase rollback. There is no stateful infrastructure of its own. |

## Blockers (Must Fix)
None in scope for the local-dev showcase.

### Production-only blockers (accepted for local-dev; must be fixed before any real deployment)
1. **Hardcoded localhost endpoints.** `baml_src/clients.baml:12` (`base_url`), `src/pages/_document.tsx:7-8` (host SDK/CSS), `manifest.json` `url`.
   - Fix: `base_url env.LITELLM_BASE_URL`, plus `NEXT_PUBLIC_HOST_URL` (or a relative proxy) in `_document.tsx`, plus a per-environment manifest.
   - Fixable: yes.
2. **LiteLLM master key used as the plugin key.** `.env.example:5-6`.
   - Fix: issue a LiteLLM virtual key scoped to `gpt-4o-mini`, with `max_budget`, `rpm_limit` and `tpm_limit`.
   - Fixable: no (infrastructure and process).
3. **No server-side rate limit on `POST /api/analyze`.**
   - Fix: a per-user or per-product throttle, or rely on the virtual key's rpm.
   - Fixable: no.
4. **No health check endpoint or error tracking for the plugin.**
   - Fix: add `/api/health` and an error tracker. Fixable: partly.
5. **No HTTPS or security headers.** This is platform-wide. Fixable: no.
6. **No deploy artifact or CI.** The plugin runs only via `npm run dev`. Fixable: no.

## Concerns (Should Fix)
1. **Prompt and LLM output dumped to the plugin log.** `src/pages/api/analyze.ts:174`.
   - Recommendation: log `err.name`, `status_code` and `message` only, never the whole error object.
   - Why: the plugin-side prompt is pre-Presidio, so it bypasses the PII masking intent. This also makes the README claim "no prompt" hold for errors too.
   - Fixable: yes, a one-line change.
2. **`BAML_LOG` not set.**
   - Recommendation: set `BAML_LOG=warn` (or `off`) in `.env.example` and the README so prompts and outputs are not echoed to stdout, after confirming the BAML 0.220 default behaviour.
   - Fixable: yes.
3. **No timeout on host calls.** `plugins/server-sdk.ts:59-70` (shared).
   - Recommendation: pass `signal: AbortSignal.timeout(...)` to `fetch`. Track it as a shared-SDK follow-up together with the known 204 bug.
   - Fixable: yes (outside this task's scope).
4. **No fail-fast on missing `LITELLM_API_KEY`.**
   - Recommendation: return a clear 500 `INTERNAL`/config message at the start of the route, or check at startup.
   - Fixable: yes.
5. **`setData` after `objects.save` partial failure.** `analyze.ts:169-170`.
   - Recommendation: write the summary first, or have the tab treat a missing or mismatched summary as stale. Already documented.
   - Fixable: yes.
6. **`postcss` advisory via `next@15.5.29`.**
   - Recommendation: bump `next` when a patched release is available. Build-time only.
   - Fixable: yes.
7. **Unpinned gateway images.** `compose.yml`: `main-stable`, `:latest`.
   - Recommendation: pin a LiteLLM tag (≥ 1.106.6, where attribution was verified) and Presidio tags. Pre-existing, read-only scope.
   - Fixable: yes.
8. **Seed and manifest kept in sync by hand.**
   - Recommendation: a small test asserting that the 013 JSONB deep-equals `manifest.json`. A one-off check was done in G1/G6.
   - Fixable: yes.

## Recommendations (Nice to Have)
1. Set `max_tokens` on `OfferAnalysisLlm` to put a hard limit on output cost.
2. Have the `llm-call` line output JSON (or add `model` and `tokens` once BAML exposes usage) for log pipelines.
3. Derive `ANALYSIS_MODEL` from one source, or add a test that checks `clients.baml` against `domain.ts`.
4. Make the rollback also remove the `plugin_data -> 'ai-offer-analysis'` key (`UPDATE products SET plugin_data = plugin_data - 'ai-offer-analysis'`).
5. Add server-side dedupe, so a second request for the same product and fingerprint while one is in flight returns 409 or reuses the result.
6. Check that Presidio fails closed when it is down: stop `presidio-analyzer` and run Re-analyze. The expected result is 502, not an unmasked call.

## Next Steps
1. Fix concerns 1 and 2 (the error-log dump and `BAML_LOG`) before merging. Both are small, keep PII out of local logs, and make the README's "no prompt in logs" claim hold.
2. Add `base_url env.LITELLM_BASE_URL` (default kept in `.env.example`) so the plugin is no longer bound to `localhost:4000`. This is a cheap step towards production-only blocker 1.
3. Record follow-ups for the shared `server-sdk.ts` (timeouts, 204 bug) and for the platform-wide production gaps (virtual keys, rate limits, health checks, TLS, CI) in the project backlog. Do not fix them inside this task.
4. Optionally run the Presidio fail-closed check (recommendation 6) and pin the LiteLLM image version.
