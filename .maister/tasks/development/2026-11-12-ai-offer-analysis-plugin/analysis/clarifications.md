# Phase 1 Clarifications

## TL;DR
Analysis auto-runs when the product tab opens (no stored result or product changed), only for EDIT users; viewers see saved results. A compact summary goes to per-product plugin data for a native "needs review" list filter and an info badge. LLM requests are tagged with plugin metadata so they are filterable in LiteLLM logs and Langfuse.

## Key Decisions
- Trigger: auto-run on tab open when there is no stored analysis or it is stale; "Re-analyze" button always available — matches "automatically checks" without a bulk job.
- Viewers (READ only): see the last saved analysis; the plugin backend refuses LLM calls without EDIT, so no tokens are spent for unauthorized users.
- Surfacing: setData summary → `product.list.filters` "Needs review" filter + `product.detail.info` verdict badge, in addition to the tab.
- LiteLLM attribution: shared key with request metadata/tags (e.g. user/tags = ai-offer-analysis) — no virtual-key setup.

## Q&A
1. **What does "automatically checks" mean?** → Auto-run on tab open (Recommended).
2. **What can a viewer do?** → See saved results only (Recommended).
3. **Surface outside the tab?** → Add list filter + info badge (Recommended).
4. **Identify plugin traffic in LiteLLM?** → Request metadata/tags (Recommended).
