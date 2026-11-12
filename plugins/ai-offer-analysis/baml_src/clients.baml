// All LLM traffic goes through the LiteLLM proxy, which handles:
// - provider API keys (they live only in litellm/.env, never here)
// - PII filtering via Presidio guardrails
// - observability via Langfuse and LiteLLM spend logs
// `user` and `metadata` attribute every call to this plugin in LiteLLM/Langfuse.
// The model must equal ANALYSIS_MODEL in src/domain.ts.
client<llm> OfferAnalysisLlm {
  provider openai
  options {
    model "gpt-4o-mini"
    api_key env.LITELLM_API_KEY
    base_url "http://localhost:4000/v1"
    temperature 0.1
    user "ai-offer-analysis"
    metadata {
      tags ["ai-offer-analysis"]
      trace_name "ai-offer-analysis"
      generation_name "AnalyzeOffer"
    }
  }
}
