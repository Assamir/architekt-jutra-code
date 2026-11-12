import type { NextApiRequest, NextApiResponse } from "next";
import {
  BamlAbortError,
  BamlClientFinishReasonError,
  BamlClientHttpError,
  BamlError,
  BamlTimeoutError,
  BamlValidationError,
} from "@boundaryml/baml/errors";
import { b } from "../../../baml_client";
import type { CategoryOption, OfferAnalysis, PeerPriceStats, PeerProduct as BamlPeerProduct, ProductInput } from "../../../baml_client";
import { createServerSDK } from "../../../../server-sdk";
import {
  ANALYZE_TIMEOUT_MS,
  OBJECT_TYPE,
  PLUGIN_ID,
  buildAnalysisRecord,
  canEdit,
  computeFingerprint,
  computePeerStats,
  formatUsd,
  selectPromptPeers,
  toProductSnapshot,
  toSummary,
} from "../../domain";
import type { AnalysisRecord, CategoryRef } from "../../domain";

type Stage = "product" | "context" | "llm" | "store";

const FAILURES = {
  BAD_REQUEST: { status: 400, error: "Invalid productId." },
  UNAUTHENTICATED: { status: 401, error: "Your session has expired. Please log in again." },
  FORBIDDEN: { status: 403, error: "Only editors can run an analysis." },
  PRODUCT_NOT_FOUND: { status: 404, error: "Product not found." },
  METHOD_NOT_ALLOWED: { status: 405, error: "Method not allowed." },
  CONTENT_BLOCKED: {
    status: 502,
    error: "The AI gateway rejected the request (content policy). No analysis was saved.",
  },
  LLM_TIMEOUT: { status: 502, error: "The AI gateway did not respond in time. No analysis was saved." },
  LLM_INVALID_OUTPUT: { status: 502, error: "The AI returned an unreadable result. No analysis was saved." },
  LLM_UNAVAILABLE: { status: 502, error: "The AI gateway is unavailable. No analysis was saved." },
  HOST_ERROR: { status: 502, error: "The host application could not provide or store the analysis data." },
  INTERNAL: { status: 500, error: "Unexpected error." },
} as const;

type FailureCode = keyof typeof FAILURES;

interface ErrorBody {
  error: string;
  code: FailureCode;
}

const INVALID_PAYLOAD = /^Invalid \w+ payload$/;
// LiteLLM answers 400 for config errors too (unknown model, context overflow); only guardrail rejections are content blocks.
// Presidio BLOCK responses read "Blocked PII entity detected: <TYPE> by Guardrail: <name>".
const GUARDRAIL_MARKER = /guardrail|presidio|blocked pii|pii entit/i;
const MAX_LOGGED_MESSAGE_LENGTH = 200;

function sendFailure(res: NextApiResponse<ErrorBody>, code: FailureCode) {
  return res.status(FAILURES[code].status).json({ error: FAILURES[code].error, code });
}

// detailed_message is not scanned: it may carry request context, i.e. product text that could contain the markers.
function isGuardrailRejection(err: BamlClientHttpError): boolean {
  return (
    err.status_code === 400 &&
    [err.message, err.raw_response].some((text) => !!text && GUARDRAIL_MARKER.test(text))
  );
}

// First match wins: BamlTimeoutError extends BamlClientHttpError (status 408), so timeouts are checked first.
function classifyError(err: unknown, stage: Stage): FailureCode {
  if (err instanceof BamlAbortError || err instanceof BamlTimeoutError) return "LLM_TIMEOUT";
  if (err instanceof BamlClientHttpError && isGuardrailRejection(err)) return "CONTENT_BLOCKED";
  if (err instanceof BamlValidationError || err instanceof BamlClientFinishReasonError) return "LLM_INVALID_OUTPUT";
  if (err instanceof BamlError) return "LLM_UNAVAILABLE";
  if (!(err instanceof Error)) return "INTERNAL";
  const hostStatus = err.message.match(/Host API error (\d+)/);
  if (hostStatus) {
    const status = Number(hostStatus[1]);
    if (status === 401) return "UNAUTHENTICATED";
    if (status === 403) return "FORBIDDEN";
    if (status === 404 && stage === "product") return "PRODUCT_NOT_FOUND";
    return "HOST_ERROR";
  }
  return INVALID_PAYLOAD.test(err.message) ? "HOST_ERROR" : "INTERNAL";
}

// Never log the error object: BAML errors carry the prompt and raw LLM output, i.e. product text not yet masked by Presidio.
function describeError(err: unknown): string {
  if (!(err instanceof Error)) return `error=${typeof err}`;
  const status = err instanceof BamlClientHttpError ? ` status=${err.status_code}` : "";
  const message = err.message.replace(/\s+/g, " ").slice(0, MAX_LOGGED_MESSAGE_LENGTH);
  return `error=${err.name}${status} message=${JSON.stringify(message)}`;
}

function toCategories(raw: unknown): (CategoryRef & { description: string | null })[] {
  if (!Array.isArray(raw)) throw new Error("Invalid categories payload");
  return raw.flatMap((item) =>
    typeof item?.id === "number" && typeof item?.name === "string"
      ? [{ id: item.id, name: item.name, description: typeof item.description === "string" ? item.description : null }]
      : [],
  );
}

function bearerToken(req: NextApiRequest): string | null {
  const header = req.headers.authorization;
  if (!header?.startsWith("Bearer ")) return null;
  const token = header.slice(7).trim();
  return token || null;
}

async function analyzeWithLlm(
  productId: string,
  product: ProductInput,
  categories: CategoryOption[],
  peerStats: PeerPriceStats | null,
  peers: BamlPeerProduct[],
): Promise<OfferAnalysis> {
  const startedAt = Date.now();
  let outcome = "ok";
  try {
    return await b.AnalyzeOffer(product, categories, peerStats, peers, {
      signal: AbortSignal.timeout(ANALYZE_TIMEOUT_MS),
    });
  } catch (err) {
    outcome = classifyError(err, "llm");
    throw err;
  } finally {
    console.info(
      `[ai-offer-analysis] llm-call productId=${productId} outcome=${outcome} durationMs=${Date.now() - startedAt}`,
    );
  }
}

export default async function handler(req: NextApiRequest, res: NextApiResponse<AnalysisRecord | ErrorBody>) {
  if (req.method !== "POST") {
    res.setHeader("Allow", "POST");
    return sendFailure(res, "METHOD_NOT_ALLOWED");
  }

  const rawId: unknown = req.body?.productId;
  const productId = rawId == null ? "" : String(rawId).trim();
  if (!/^\d+$/.test(productId)) return sendFailure(res, "BAD_REQUEST");

  const token = bearerToken(req);
  if (!token) return sendFailure(res, "UNAUTHENTICATED");
  if (!canEdit(token)) return sendFailure(res, "FORBIDDEN");

  let stage: Stage = "product";
  try {
    const sdk = createServerSDK(PLUGIN_ID, undefined, req);
    const snapshot = toProductSnapshot(await sdk.hostApp.getProduct(productId));

    stage = "context";
    const [categoriesResponse, products] = await Promise.all([
      sdk.hostApp.fetch("/api/categories"),
      sdk.hostApp.getProducts({ category: snapshot.category.id }),
    ]);
    if (!categoriesResponse.ok) throw new Error(`Host API error ${categoriesResponse.status} GET /api/categories`);
    const categories = toCategories(await categoriesResponse.json());
    const peerStats = computePeerStats(products, snapshot);
    const promptPeers = selectPromptPeers(products, snapshot);
    const fingerprint = computeFingerprint(snapshot);

    stage = "llm";
    const llm = await analyzeWithLlm(
      productId,
      {
        name: snapshot.name,
        description: snapshot.description.trim() || "(no description)",
        price: formatUsd(snapshot.price),
        categoryName: snapshot.category.name,
      },
      categories.map(({ name, description }) => ({ name, description })),
      peerStats && {
        categoryName: peerStats.categoryName,
        count: peerStats.count,
        min: formatUsd(peerStats.min),
        median: formatUsd(peerStats.median),
        max: formatUsd(peerStats.max),
      },
      promptPeers.map((peer) => ({ name: peer.name, price: formatUsd(peer.price) })),
    );

    stage = "store";
    const record = buildAnalysisRecord(llm, { snapshot, categories, peerStats, fingerprint, now: new Date() });
    await sdk.thisPlugin.objects.save(OBJECT_TYPE, productId, record, { entityType: "PRODUCT", entityId: productId });
    await sdk.thisPlugin.setData(productId, toSummary(record));
    return res.status(200).json(record);
  } catch (err) {
    const code = classifyError(err, stage);
    console.error(
      `[ai-offer-analysis] analyze failed productId=${productId} stage=${stage} code=${code} ${describeError(err)}`,
    );
    return sendFailure(res, code);
  }
}
