# Implementation Verification: MCP auth refactor (validate-then-exchange)

> Canonical verdict after 2 fix & re-verification cycles (reverify_count = 2). Supersedes the cycle-1 report.

## TL;DR
- Verdict: **⚠️ Passed with Issues** — 0 critical, 5 warning (none fixable in code here: manual run, commit, design/follow-up), ~8 info.
- Plan 104/104 (100%); tests 390/391 — host 252/252, MCP 51/51, frontend 87/88 (the 1 failure and 16 lint problems are pre-existing, untouched).
- 4/4 TDD tests green; RG-1 hashes OK. Token passthrough, confused deputy, audience replay and scope escalation are eliminated and covered by boundary tests.
- All 14 cycle-1 fixable findings and all 9 cycle-2 info items resolved with tests; no regressions.

## Open Questions / Risks
- No live end-to-end run of host + MCP server with a real MCP client (Claude Code / MCP Inspector); the `resource` value real clients send is still unrecorded. An agent attempt was blocked by the permission system and not circumvented.
- RG-1 git baseline needs the two red-test files committed (hash guard passes).
- Committed HMAC fallback `app.jwt.secret` can forge any token for repo readers (pre-existing; user-approved not to generate) — blocker for any public exposure.
- BCrypt per tool call (+1 for unknown clients via the dummy hash) and no rate limiting — acceptable locally, a blocker for public exposure.

## Executive Summary
All 50 (now 51 incl. AS-17) spec requirements are implemented and tested. Two verification cycles fixed 23 findings (grant-type enforcement, PRM flag, encoded redirects, DCR redirect validation, diagnosable but secret-free logging, restartable dev secret isolated from plugins, docs/spec alignment). What remains is a manual integration run, a commit only the user can make, and operational hardening outside this task's scope.

## Results by Check (final)

| Check | Report(s) | Final status |
|---|---|---|
| Completeness | [completeness-report.md](completeness-report.md), [completeness-reverify.md](completeness-reverify.md) | ⚠️ 100% plan; remaining W1 (commit), W2 (manual run) |
| Test suite | [test-suite-results.md](test-suite-results.md) (cycle 2) + Fix Group C full runs | ⚠️ 390/391, failure pre-existing |
| Code review | [code-review-report.md](code-review-report.md), [code-review-reverify.md](code-review-reverify.md) | ⚠️ 0 critical; 2 by-design warnings (HMAC fallback, BCrypt cost) |
| Pragmatic review | [pragmatic-review.md](pragmatic-review.md) | ✅ appropriate; both warnings fixed (stack traces, restartable secret) |
| Production readiness | [production-readiness-report.md](production-readiness-report.md) | ⚠️ GO with mitigations locally; NO-GO for public exposure (TLS, rate limits, persistent tokens, JWKS) |
| Reality check | [reality-check.md](reality-check.md) | ⚠️ goal met in code; needs live run |

### Test suite (final)
| Suite | Total | Pass | Fail |
|---|---|---|---|
| Host | 252 | 252 | 0 |
| MCP server | 51 | 51 | 0 |
| Frontend | 88 | 87 | 1 (pre-existing stale `extension-points.test.tsx`) |
| Frontend lint | — | — | 16 problems, identical to baseline |

### Token-confusion matrix
| Token | `/api` | consent principal | MCP inbound | exchange subject |
|---|---|---|---|---|
| T0 session (aud=`${issuer}/api`) | ✅ | ✅ | ❌ inactive | ❌ |
| T1 MCP (aud=MCP URI) | ❌ 401 | ❌ 403 | ✅ | ✅ |
| T2 delegated (aud=api, act) | ✅ mcp:* | ❌ 403 | ❌ inactive | ❌ |

## Remaining Issues

### Warnings
| ID | Description | Owner |
|---|---|---|
| V-W1 | Manual live run with a real MCP client; record `resource` value | manual |
| V-W7 | Commit red-test files as RG-1 git baseline | user |
| V-W8 | Committed HMAC fallback secret (pre-existing, user-approved) | follow-up (JWKS / fail-fast outside dev) |
| V-W9 | BCrypt cost per call, no rate limiting | design / follow-up |
| V-W10 | Secret/issuer mismatch detected only at request time; MCP health ignores the AS | follow-up |

### Info
- N7: bash vs PowerShell parse `.aj-dev.env` slightly differently; Windows file permissions default (cannot cause a mismatch).
- N8 (pre-existing): consent page Cancel redirects to an unvalidated `redirect_uri` (open redirect) — follow-up.
- Unreachable "resource resolves but differs" branch (single MCP resource) — kept by spec (m3).
- Pre-existing AS items: `_token` accepted on any path, PKCE optional for confidential clients, no refresh-token family revocation, plugin iframes receive a consent-capable T0.
- Pre-existing frontend: 16 lint problems, stale `extension-points` test.
- Candidate standards awaiting the user (PRM via customizer + mTLS flag false; REST client error logs status+path; security error logs error_type + sanitised identified client id, stack traces DEBUG only; grant-type enforcement; `.aj-dev.env` local secret; DCR exact-loopback redirect rule; `.gitattributes *.sh eol=lf`; shared `AJ_*` env names).

## Fix & Re-Verification History

| Cycle | Issue | Fix | Re-check |
|---|---|---|---|
| 1 | V-W2 grant types not enforced | `requireGrantType` at authorize/code/refresh; refresh token only for refresh-registered clients | resolved (code-review-reverify; tests in OAuth2GrantPolicyTests) |
| 1 | V-W3 PRM mTLS flag true | `tlsClientCertificateBoundAccessTokens(false)` | resolved; strict assertion added in cycle 2 |
| 1 | V-W4 redirect concatenation | `UriComponentsBuilder` + `UriUtils`, `build(true)` | resolved (reserved-chars test) |
| 1 | V-W5 no stack traces | DEBUG-level stack trace in 4 catch-alls | resolved; documented in README/CLAUDE.md (cycle 2) |
| 1 | V-W6 non-restartable secret | `.aj-dev.env` (gitignored) reuse/generate | resolved (scripted runs bash + PS 5.1) |
| 1 | info a–i | overlap guard, docs wording, identified client id, dummy BCrypt, AjProperties backend, payload logging → DEBUG, secret not exported to plugins, spec deviations, 5 edge tests | resolved |
| 2 | client_id=null on failed Basic | claimed id recorded before lookup (no secret) | resolved (OAuth2LoggingTests) |
| 2 | ASCII-only sanitiser | `[\p{Cc}\p{Cf}\p{Zl}\p{Zp}]` | resolved |
| 2 | DCR redirect URI prefix check / 500s | parsed ASCII URIs, exact loopback hosts, https otherwise; null guards; defaults; build before save | resolved (17 new cases) |
| 2 | docs/spec drift, PRM test, log format | README/CLAUDE.md, spec AS-1/4/5/8/9/15/17 + CFG-2, strict assertion, `{} chars` | resolved |

Test counts across cycles: host 209 → 232 → 252; MCP 49 → 51; frontend unchanged.

## Recommendations
1. Run one manual flow: `start-dev`, connect MCP Inspector / Claude Code to `http://localhost:8181`, consent as editor (all 3 tools) and viewer (`aj_add_product` → insufficient scope), replay T1 at `http://localhost:8180/api/products` → 401; record the `resource` value from the host log.
2. Commit the red-test files first (baseline), then the implementation.
3. Follow-ups: JWKS/asymmetric keys or fail-fast on the committed HMAC secret, rate limiting, AS health indicator, consent Cancel open redirect, pre-existing AS hardening, frontend lint cleanup.

## Verification Checklist
- [x] All declared reports on disk (cycle 1: 6; cycle 2: test-suite-results, code-review-reverify, completeness-reverify)
- [x] Fixes logged in implementation/work-log.md and verification_context
- [x] RG-1 hashes OK after every cycle
- [x] Roadmap: none
