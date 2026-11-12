import type { PluginObject } from "../../sdk";

export const PLUGIN_ID = "ai-offer-analysis";
export const OBJECT_TYPE = "analysis";
export const CHANNEL_NAME = "ai-offer-analysis";
// Must equal the model of the OfferAnalysisLlm client in baml_src/clients.baml.
export const ANALYSIS_MODEL = "gpt-4o-mini";
export const PRICE_DEVIATION_THRESHOLD_PCT = 30;
export const ANALYZE_TIMEOUT_MS = 45000;
export const MAX_PEERS_IN_PROMPT = 10;

const DESCRIPTION_VERDICTS = ["GOOD", "ACCEPTABLE", "POOR"] as const;
const CATEGORY_VERDICTS = ["FITS", "QUESTIONABLE", "MISMATCH"] as const;
const PRICE_VERDICTS = ["FAIR", "ABOVE_MARKET", "BELOW_MARKET", "INSUFFICIENT_DATA"] as const;
const CONFIDENCES = ["LOW", "MEDIUM", "HIGH"] as const;

export type DescriptionVerdict = (typeof DESCRIPTION_VERDICTS)[number];
export type CategoryVerdict = (typeof CATEGORY_VERDICTS)[number];
export type PriceVerdict = (typeof PRICE_VERDICTS)[number];
export type Confidence = (typeof CONFIDENCES)[number];
export type Verdict = DescriptionVerdict | CategoryVerdict | PriceVerdict;

export interface CategoryRef {
  id: number;
  name: string;
}

export interface ProductSnapshot {
  id: string;
  name: string;
  description: string;
  price: number;
  sku: string;
  category: CategoryRef & { description: string | null };
}

export type PeerStats = {
  categoryName: string;
  count: number;
  min: number;
  median: number;
  max: number;
};

export interface PeerProduct {
  name: string;
  price: number;
}

// Type aliases (not interfaces) so records and summaries are assignable to the SDK's Record<string, unknown>.
export type AnalysisRecord = {
  productId: string;
  fingerprint: string;
  analyzedAt: string;
  model: string;
  needsReview: boolean;
  description: {
    verdict: DescriptionVerdict;
    score: number;
    summary: string;
    issues: string[];
    suggestions: string[];
  };
  category: {
    verdict: CategoryVerdict;
    assigned: CategoryRef;
    suggested: CategoryRef | null;
    reasoning: string;
  };
  price: {
    verdict: PriceVerdict;
    confidence: Confidence;
    pricedAs: string;
    productPrice: number;
    marketMin: number | null;
    marketMax: number | null;
    deviationPct: number | null;
    marketRangeUnreliable: boolean;
    peers: PeerStats | null;
    reasoning: string;
  };
};

export type AnalysisSummary = {
  needsReview: boolean;
  descriptionVerdict: DescriptionVerdict;
  categoryVerdict: CategoryVerdict;
  priceVerdict: PriceVerdict;
  priceDeviationPct?: number;
};

// Structural shape of the BAML OfferAnalysis output; verdicts stay plain strings until narrowed.
export interface LlmOfferAnalysis {
  description: { verdict: string; score: number; summary: string; issues: string[]; suggestions: string[] };
  category: { verdict: string; suggestedCategory?: string | null; reasoning: string };
  price: {
    pricedAs?: string | null;
    verdict: string;
    estimatedMarketMinUsd?: number | null;
    estimatedMarketMaxUsd?: number | null;
    confidence: string;
    reasoning: string;
  };
}

export interface AnalysisContext {
  snapshot: ProductSnapshot;
  categories: readonly CategoryRef[];
  peerStats: PeerStats | null;
  fingerprint: string;
  now: Date;
}

const MAX_LIST_ITEMS = 4;
const UNICODE_MINUS = "−";

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

function narrow<T extends string>(value: unknown, allowed: readonly T[], fallback: T): T {
  return allowed.find((item) => item === value) ?? fallback;
}

function toPositivePrice(value: unknown): number | null {
  const price = typeof value === "number" ? value : typeof value === "string" && value.trim() !== "" ? Number(value) : NaN;
  return Number.isFinite(price) && price > 0 ? price : null;
}

function toFiniteOrNull(value: unknown): number | null {
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}

function asString(value: unknown): string {
  return typeof value === "string" ? value : "";
}

function toStringList(value: unknown): string[] {
  return Array.isArray(value) ? value.filter((item): item is string => typeof item === "string").slice(0, MAX_LIST_ITEMS) : [];
}

function roundTo2(n: number): number {
  return Math.round(n * 100) / 100;
}

export function toProductSnapshot(raw: unknown): ProductSnapshot {
  const invalid = new Error("Invalid product payload");
  if (!isRecord(raw) || !isRecord(raw.category)) throw invalid;
  const { id, name, description, price, sku, category } = raw;
  const parsedPrice = toPositivePrice(price);
  if (
    (typeof id !== "number" && typeof id !== "string") ||
    typeof name !== "string" ||
    (description != null && typeof description !== "string") ||
    parsedPrice === null ||
    typeof sku !== "string" ||
    typeof category.id !== "number" ||
    typeof category.name !== "string"
  ) {
    throw invalid;
  }
  return {
    id: String(id),
    name,
    description: description ?? "",
    price: parsedPrice,
    sku,
    category: {
      id: category.id,
      name: category.name,
      description: typeof category.description === "string" ? category.description : null,
    },
  };
}

// cyrb53 (public domain): a fast 53-bit string hash that behaves identically in the browser and Node.
function cyrb53(str: string, seed = 0): number {
  let h1 = 0xdeadbeef ^ seed;
  let h2 = 0x41c6ce57 ^ seed;
  for (let i = 0; i < str.length; i++) {
    const ch = str.charCodeAt(i);
    h1 = Math.imul(h1 ^ ch, 2654435761);
    h2 = Math.imul(h2 ^ ch, 1597334677);
  }
  h1 = Math.imul(h1 ^ (h1 >>> 16), 2246822507);
  h1 ^= Math.imul(h2 ^ (h2 >>> 13), 3266489909);
  h2 = Math.imul(h2 ^ (h2 >>> 16), 2246822507);
  h2 ^= Math.imul(h1 ^ (h1 >>> 13), 3266489909);
  return 4294967296 * (2097151 & h2) + (h1 >>> 0);
}

export function computeFingerprint(snapshot: ProductSnapshot): string {
  const canonical = JSON.stringify([
    snapshot.name.trim(),
    snapshot.description.trim(),
    snapshot.price.toFixed(2),
    snapshot.sku.trim(),
    snapshot.category.id,
    snapshot.category.name.trim(),
  ]);
  return cyrb53(canonical).toString(16).padStart(14, "0");
}

export function formatUsd(n: number): string {
  return "USD " + n.toFixed(2);
}

export function formatAmount(n: number): string {
  return n.toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

// Host product ids are JSON numbers while the snapshot id is a string, hence the String() comparison.
function toPeers(products: unknown[], self: ProductSnapshot): PeerProduct[] {
  const peers: PeerProduct[] = [];
  for (const product of products) {
    if (!isRecord(product) || String(product.id) === String(self.id)) continue;
    const price = toPositivePrice(product.price);
    if (price !== null) peers.push({ name: String(product.name ?? ""), price });
  }
  return peers.sort((a, b) => a.price - b.price);
}

export function computePeerStats(products: unknown[], self: ProductSnapshot): PeerStats | null {
  const prices = toPeers(products, self).map((peer) => peer.price);
  if (prices.length === 0) return null;
  const middle = Math.floor(prices.length / 2);
  const median = prices.length % 2 === 1 ? prices[middle] : roundTo2((prices[middle - 1] + prices[middle]) / 2);
  return {
    categoryName: self.category.name,
    count: prices.length,
    min: prices[0],
    median,
    max: prices[prices.length - 1],
  };
}

export function selectPromptPeers(products: unknown[], self: ProductSnapshot): PeerProduct[] {
  const peers = toPeers(products, self);
  if (peers.length <= MAX_PEERS_IN_PROMPT) return peers;
  const distance = (peer: PeerProduct) => Math.abs(peer.price - self.price);
  return peers
    .sort((a, b) => distance(a) - distance(b) || a.name.localeCompare(b.name))
    .slice(0, MAX_PEERS_IN_PROMPT)
    .sort((a, b) => a.price - b.price);
}

export function computeDeviationPct(
  price: number,
  min: number | null,
  max: number | null,
  verdict: PriceVerdict,
): number | null {
  if (verdict === "INSUFFICIENT_DATA" || min === null || max === null || min <= 0 || max < min) return null;
  const mid = (min + max) / 2;
  return Math.round(((price - mid) / mid) * 100);
}

export function isPriceFlagged(verdict: PriceVerdict, deviationPct: number | null): boolean {
  return (
    (verdict === "ABOVE_MARKET" || verdict === "BELOW_MARKET") &&
    deviationPct !== null &&
    Math.abs(deviationPct) >= PRICE_DEVIATION_THRESHOLD_PCT
  );
}

const PEER_RANGE_TOLERANCE = 0.05;

// A market range that sits inside the peer range of a mismatched category was most likely copied from
// wrong-type peers, so its deviation is meaningless.
function isPeerContaminated(
  categoryVerdict: CategoryVerdict,
  peers: PeerStats | null,
  min: number | null,
  max: number | null,
): boolean {
  return (
    categoryVerdict === "MISMATCH" &&
    peers !== null &&
    min !== null &&
    max !== null &&
    min >= peers.min * (1 - PEER_RANGE_TOLERANCE) &&
    max <= peers.max * (1 + PEER_RANGE_TOLERANCE)
  );
}

export function resolveSuggestedCategory(
  suggested: string | null | undefined,
  categories: readonly CategoryRef[],
  assignedId: number,
): CategoryRef | null {
  const wanted = suggested?.trim().toLowerCase();
  if (!wanted) return null;
  const match = categories.find((category) => category.name.trim().toLowerCase() === wanted);
  return match && match.id !== assignedId ? { id: match.id, name: match.name } : null;
}

export function computeNeedsReview(record: {
  description: { verdict: DescriptionVerdict };
  category: { verdict: CategoryVerdict };
  price: { verdict: PriceVerdict; deviationPct: number | null };
}): boolean {
  return (
    record.description.verdict === "POOR" ||
    record.category.verdict === "MISMATCH" ||
    isPriceFlagged(record.price.verdict, record.price.deviationPct)
  );
}

function clampScore(score: unknown): number {
  const n = typeof score === "number" && Number.isFinite(score) ? Math.round(score) : 1;
  return Math.min(10, Math.max(1, n));
}

export function buildAnalysisRecord(llm: LlmOfferAnalysis, ctx: AnalysisContext): AnalysisRecord {
  const { snapshot } = ctx;
  const priceVerdict = narrow(llm.price.verdict, PRICE_VERDICTS, "INSUFFICIENT_DATA");
  const marketMin = toFiniteOrNull(llm.price.estimatedMarketMinUsd);
  const marketMax = toFiniteOrNull(llm.price.estimatedMarketMaxUsd);
  const categoryVerdict = narrow(llm.category.verdict, CATEGORY_VERDICTS, "QUESTIONABLE");
  const contaminated = isPeerContaminated(categoryVerdict, ctx.peerStats, marketMin, marketMax);
  const analysis = {
    description: {
      verdict: narrow(llm.description.verdict, DESCRIPTION_VERDICTS, "ACCEPTABLE"),
      score: clampScore(llm.description.score),
      summary: llm.description.summary,
      issues: toStringList(llm.description.issues),
      suggestions: toStringList(llm.description.suggestions),
    },
    category: {
      verdict: categoryVerdict,
      assigned: { id: snapshot.category.id, name: snapshot.category.name },
      suggested: resolveSuggestedCategory(llm.category.suggestedCategory, ctx.categories, snapshot.category.id),
      reasoning: llm.category.reasoning,
    },
    price: {
      verdict: priceVerdict,
      confidence: contaminated ? ("LOW" as const) : narrow(llm.price.confidence, CONFIDENCES, "LOW"),
      pricedAs: asString(llm.price.pricedAs).trim(),
      productPrice: snapshot.price,
      marketMin,
      marketMax,
      deviationPct: contaminated ? null : computeDeviationPct(snapshot.price, marketMin, marketMax, priceVerdict),
      marketRangeUnreliable: contaminated,
      peers: ctx.peerStats,
      reasoning: llm.price.reasoning,
    },
  };
  return {
    productId: snapshot.id,
    fingerprint: ctx.fingerprint,
    analyzedAt: ctx.now.toISOString(),
    model: ANALYSIS_MODEL,
    needsReview: computeNeedsReview(analysis),
    ...analysis,
  };
}

export function toSummary(record: AnalysisRecord): AnalysisSummary {
  return {
    needsReview: record.needsReview,
    descriptionVerdict: record.description.verdict,
    categoryVerdict: record.category.verdict,
    priceVerdict: record.price.verdict,
    ...(record.price.deviationPct !== null && { priceDeviationPct: Math.round(record.price.deviationPct) }),
  };
}

function toCategoryRef(value: unknown): CategoryRef | null {
  if (!isRecord(value) || typeof value.id !== "number" || typeof value.name !== "string") return null;
  return { id: value.id, name: value.name };
}

function toPeerStats(value: unknown): PeerStats | null {
  if (!isRecord(value)) return null;
  const { categoryName, count, min, median, max } = value;
  if (
    typeof categoryName !== "string" ||
    typeof count !== "number" ||
    typeof min !== "number" ||
    typeof median !== "number" ||
    typeof max !== "number"
  ) {
    return null;
  }
  return { categoryName, count, min, median, max };
}

export function toOfferAnalysis(obj: PluginObject): AnalysisRecord {
  const { data } = obj;
  const description = isRecord(data.description) ? data.description : {};
  const category = isRecord(data.category) ? data.category : {};
  const price = isRecord(data.price) ? data.price : {};
  return {
    productId: typeof data.productId === "string" ? data.productId : obj.objectId,
    fingerprint: asString(data.fingerprint),
    analyzedAt: asString(data.analyzedAt),
    model: asString(data.model) || ANALYSIS_MODEL,
    needsReview: data.needsReview === true,
    description: {
      verdict: narrow(description.verdict, DESCRIPTION_VERDICTS, "ACCEPTABLE"),
      score: clampScore(description.score),
      summary: asString(description.summary),
      issues: toStringList(description.issues),
      suggestions: toStringList(description.suggestions),
    },
    category: {
      verdict: narrow(category.verdict, CATEGORY_VERDICTS, "QUESTIONABLE"),
      assigned: toCategoryRef(category.assigned) ?? { id: 0, name: "" },
      suggested: toCategoryRef(category.suggested),
      reasoning: asString(category.reasoning),
    },
    price: {
      verdict: narrow(price.verdict, PRICE_VERDICTS, "INSUFFICIENT_DATA"),
      confidence: narrow(price.confidence, CONFIDENCES, "LOW"),
      pricedAs: asString(price.pricedAs),
      productPrice: toFiniteOrNull(price.productPrice) ?? 0,
      marketMin: toFiniteOrNull(price.marketMin),
      marketMax: toFiniteOrNull(price.marketMax),
      deviationPct: toFiniteOrNull(price.deviationPct),
      marketRangeUnreliable: price.marketRangeUnreliable === true,
      peers: toPeerStats(price.peers),
      reasoning: asString(price.reasoning),
    },
  };
}

export function toAnalysisSummary(data: unknown): AnalysisSummary | null {
  if (!isRecord(data) || typeof data.needsReview !== "boolean") return null;
  const deviationPct = toFiniteOrNull(data.priceDeviationPct);
  return {
    needsReview: data.needsReview,
    descriptionVerdict: narrow(data.descriptionVerdict, DESCRIPTION_VERDICTS, "ACCEPTABLE"),
    categoryVerdict: narrow(data.categoryVerdict, CATEGORY_VERDICTS, "QUESTIONABLE"),
    priceVerdict: narrow(data.priceVerdict, PRICE_VERDICTS, "INSUFFICIENT_DATA"),
    ...(deviationPct !== null && { priceDeviationPct: deviationPct }),
  };
}

export function verdictBadgeClass(verdict: Verdict, deviationPct: number | null): string {
  if (verdict === "GOOD" || verdict === "FITS" || verdict === "FAIR") return "tc-badge tc-badge--success";
  if (verdict === "POOR" || verdict === "MISMATCH") return "tc-badge tc-badge--danger";
  if ((verdict === "ABOVE_MARKET" || verdict === "BELOW_MARKET") && isPriceFlagged(verdict, deviationPct)) {
    return "tc-badge tc-badge--danger";
  }
  return "tc-badge";
}

export function verdictLabel(verdict: string): string {
  return verdict.replace(/_/g, " ");
}

function formatSignedPct(pct: number): string {
  const sign = pct > 0 ? "+" : pct < 0 ? UNICODE_MINUS : "";
  return `${sign}${Math.abs(pct)}%`;
}

export function priceBadgeLabel(verdict: PriceVerdict, deviationPct: number | null): string {
  const label = verdictLabel(verdict);
  return deviationPct === null ? label : `${label} ${formatSignedPct(deviationPct)}`;
}

export function reviewReasons(summary: AnalysisSummary): string {
  const reasons: string[] = [];
  const deviationPct = summary.priceDeviationPct ?? null;
  if (deviationPct !== null && isPriceFlagged(summary.priceVerdict, deviationPct)) {
    const direction = summary.priceVerdict === "ABOVE_MARKET" ? "above" : "below";
    reasons.push(`price ${formatSignedPct(deviationPct)} ${direction} market`);
  }
  if (summary.categoryVerdict === "MISMATCH") reasons.push("category mismatch");
  if (summary.descriptionVerdict === "POOR") reasons.push("poor description");
  return reasons.join(" · ");
}

export function isCompactSection(verdict: Verdict): boolean {
  return verdict === "GOOD" || verdict === "FITS" || verdict === "FAIR";
}

export function canEdit(token: string | null): boolean {
  try {
    const base64Url = token?.split(".")[1];
    if (!base64Url) return false;
    const base64 = base64Url.replace(/-/g, "+").replace(/_/g, "/").padEnd(Math.ceil(base64Url.length / 4) * 4, "=");
    const payload: unknown = JSON.parse(atob(base64));
    return isRecord(payload) && Array.isArray(payload.permissions) && payload.permissions.includes("EDIT");
  } catch {
    return false;
  }
}
