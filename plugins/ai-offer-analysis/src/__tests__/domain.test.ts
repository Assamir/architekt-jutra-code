import { describe, expect, test } from "@jest/globals";
import {
  buildAnalysisRecord,
  canEdit,
  computeDeviationPct,
  computeFingerprint,
  computeNeedsReview,
  computePeerStats,
  priceBadgeLabel,
  resolveSuggestedCategory,
  reviewReasons,
  selectPromptPeers,
  toAnalysisSummary,
  toProductSnapshot,
  toSummary,
  verdictBadgeClass,
} from "../domain";
import type {
  AnalysisSummary,
  CategoryVerdict,
  DescriptionVerdict,
  LlmOfferAnalysis,
  PriceVerdict,
  ProductSnapshot,
} from "../domain";

const hostProduct = {
  id: 42,
  name: 'Vistara 65" OLED 4K TV',
  description: "The best TV ever. Buy now!",
  price: 899,
  sku: "VIS-OLED-65",
  category: { id: 3, name: "Smart Home", description: "Connected devices" },
};

const snapshot: ProductSnapshot = toProductSnapshot(hostProduct);

const categories = [
  { id: 1, name: "Televisions" },
  { id: 3, name: "Smart Home" },
];

const llmOutput: LlmOfferAnalysis = {
  description: { verdict: "POOR", score: 2, summary: "Vague.", issues: ["No specs"], suggestions: ["Add specs"] },
  category: { verdict: "MISMATCH", suggestedCategory: "televisions", reasoning: "It is a TV." },
  price: {
    pricedAs: " 65-inch OLED 4K TV ",
    verdict: "ABOVE_MARKET",
    estimatedMarketMinUsd: 500,
    estimatedMarketMaxUsd: 700,
    confidence: "MEDIUM",
    reasoning: "Comparable TVs cost less.",
  },
};

function tokenWith(payload: unknown): string {
  return `header.${Buffer.from(JSON.stringify(payload)).toString("base64url")}.signature`;
}

describe("domain", () => {
  // 1
  test("computeFingerprint_normalizedEqualSnapshots_returnsSameHash", () => {
    const variant = toProductSnapshot({
      ...hostProduct,
      id: "42",
      name: `  ${hostProduct.name} `,
      price: "899.00",
      sku: " VIS-OLED-65\n",
    });
    const noDescription = toProductSnapshot({ ...hostProduct, description: null });
    const emptyDescription = toProductSnapshot({ ...hostProduct, description: "" });

    const fingerprint = computeFingerprint(snapshot);
    expect(fingerprint).toMatch(/^[0-9a-f]{14}$/);
    expect(computeFingerprint(snapshot)).toBe(fingerprint);
    expect(computeFingerprint(variant)).toBe(fingerprint);
    expect(computeFingerprint(noDescription)).toBe(computeFingerprint(emptyDescription));
    expect(() => toProductSnapshot({ ...hostProduct, price: "abc" })).toThrow("Invalid product payload");
  });

  // 2
  test.each([
    ["name", { name: "Other TV" }],
    ["description", { description: "A detailed description." }],
    ["price", { price: 899.01 }],
    ["sku", { sku: "VIS-OLED-55" }],
    ["category id", { category: { ...hostProduct.category, id: 4 } }],
    ["category name", { category: { ...hostProduct.category, name: "Smart Home Devices" } }],
  ])("computeFingerprint_%sChanged_returnsDifferentHash", (_field, change) => {
    const changed = toProductSnapshot({ ...hostProduct, ...change });
    expect(computeFingerprint(changed)).not.toBe(computeFingerprint(snapshot));
  });

  // 3
  test.each<[DescriptionVerdict, CategoryVerdict, PriceVerdict, number | null, boolean]>([
    ["POOR", "FITS", "FAIR", null, true],
    ["GOOD", "MISMATCH", "FAIR", null, true],
    ["GOOD", "FITS", "ABOVE_MARKET", 35, true],
    ["GOOD", "FITS", "BELOW_MARKET", -30, true],
    ["GOOD", "FITS", "ABOVE_MARKET", 29, false],
    ["ACCEPTABLE", "FITS", "FAIR", null, false],
    ["GOOD", "QUESTIONABLE", "FAIR", null, false],
    ["GOOD", "FITS", "INSUFFICIENT_DATA", null, false],
  ])(
    "computeNeedsReview_%s_%s_%s_%s_returns%s",
    (description, category, price, deviationPct, expected) => {
      const result = computeNeedsReview({
        description: { verdict: description },
        category: { verdict: category },
        price: { verdict: price, deviationPct },
      });
      expect(result).toBe(expected);
    },
  );

  // 4
  test("computePeerStatsAndSelectPromptPeers_numericHostIds_excludeSelfAndKeepClosest", () => {
    const self = { ...snapshot, id: "42", price: 550 };
    const oddPeers = [
      { id: 42, name: "Self", price: 550 },
      { id: 1, name: "Echo Hub", price: 179.99 },
      { id: 2, name: "Hue Kit", price: "179.99" },
      { id: 3, name: "Nest", price: 279.99 },
      { id: 4, name: "Broken", price: 0 },
      { id: 5, name: "No price", price: null },
    ];
    expect(computePeerStats(oddPeers, self)).toEqual({
      categoryName: "Smart Home",
      count: 3,
      min: 179.99,
      median: 179.99,
      max: 279.99,
    });

    const evenPeers = [...oddPeers, { id: 6, name: "Extra", price: 300.02 }];
    expect(computePeerStats(evenPeers, self)?.median).toBe(229.99);

    expect(computePeerStats([{ id: 42, name: "Self", price: 550 }], self)).toBeNull();
    expect(computePeerStats([], self)).toBeNull();

    const manyPeers = Array.from({ length: 12 }, (_, i) => ({ id: i + 100, name: `P${i + 1}`, price: (i + 1) * 100 }));
    const selected = selectPromptPeers([{ id: 42, name: "Self", price: 550 }, ...manyPeers].reverse(), self);
    expect(selected.map((peer) => peer.price)).toEqual([100, 200, 300, 400, 500, 600, 700, 800, 900, 1000]);
    expect(selected.find((peer) => peer.name === "Self")).toBeUndefined();
    expect(computePeerStats(manyPeers, self)?.count).toBe(12);

    const tiedAtCutoff = [
      { id: 200, name: "Zulu", price: 650 },
      ...Array.from({ length: 9 }, (_, i) => ({ id: i + 300, name: `Near${i + 1}`, price: 541 + i })),
      { id: 201, name: "Alpha", price: 450 },
    ];
    const tieBroken = selectPromptPeers(tiedAtCutoff, self);
    expect(tieBroken.map((peer) => peer.name)).toContain("Alpha");
    expect(tieBroken.map((peer) => peer.name)).not.toContain("Zulu");
    expect(tieBroken.map((peer) => peer.price)).toEqual([450, 541, 542, 543, 544, 545, 546, 547, 548, 549]);
  });

  // 5
  test.each<[number, number | null, number | null, PriceVerdict, number | null]>([
    [2799, 1500, 2300, "ABOVE_MARKET", 47],
    [1950, 1500, 2300, "FAIR", 3],
    [1000, 1500, 2300, "BELOW_MARKET", -47],
    [1000, 2300, 1500, "BELOW_MARKET", null],
    [1000, null, 2300, "FAIR", null],
    [1000, 1500, null, "FAIR", null],
    [1000, 0, 2300, "FAIR", null],
    [1000, 1500, 2300, "INSUFFICIENT_DATA", null],
  ])("computeDeviationPct_%s_%s_%s_%s_returns%s", (price, min, max, verdict, expected) => {
    expect(computeDeviationPct(price, min, max, verdict)).toBe(expected);
  });

  // 6
  test("resolveSuggestedCategory_variousSuggestions_matchesCaseInsensitiveExceptAssigned", () => {
    expect(resolveSuggestedCategory("  TELEVISIONS ", categories, 3)).toEqual({ id: 1, name: "Televisions" });
    expect(resolveSuggestedCategory("Gaming", categories, 3)).toBeNull();
    expect(resolveSuggestedCategory("smart home", categories, 3)).toBeNull();
    expect(resolveSuggestedCategory(null, categories, 3)).toBeNull();
  });

  // 7
  test("canEdit_variousTokens_trueOnlyForEditPermission", () => {
    expect(canEdit(tokenWith({ sub: "editor?>", permissions: ["READ", "EDIT"] }))).toBe(true);
    expect(canEdit(tokenWith({ sub: "viewer", permissions: ["READ"] }))).toBe(false);
    expect(canEdit("not-a-jwt")).toBe(false);
    expect(canEdit("a.%%%.c")).toBe(false);
    expect(canEdit(null)).toBe(false);
  });

  // 8
  test("buildAnalysisRecordAndToSummary_rawLlmOutput_narrowsClampsAndFlattens", () => {
    const fingerprint = computeFingerprint(snapshot);
    const peerStats = { categoryName: "Smart Home", count: 3, min: 179.99, median: 179.99, max: 279.99 };
    const now = new Date("2026-11-12T18:42:00Z");

    const record = buildAnalysisRecord(llmOutput, { snapshot, categories, peerStats, fingerprint, now });
    expect(record).toMatchObject({
      productId: "42",
      fingerprint,
      analyzedAt: "2026-11-12T18:42:00.000Z",
      model: "gpt-4o-mini",
      needsReview: true,
      category: { verdict: "MISMATCH", assigned: { id: 3, name: "Smart Home" }, suggested: { id: 1, name: "Televisions" } },
      price: {
        verdict: "ABOVE_MARKET",
        confidence: "MEDIUM",
        pricedAs: "65-inch OLED 4K TV",
        productPrice: 899,
        marketMin: 500,
        marketMax: 700,
        deviationPct: 50,
        peers: peerStats,
      },
    });
    expect(toSummary(record)).toEqual({
      needsReview: true,
      descriptionVerdict: "POOR",
      categoryVerdict: "MISMATCH",
      priceVerdict: "ABOVE_MARKET",
      priceDeviationPct: 50,
    });

    const peerCopiedPrice = { ...llmOutput.price, estimatedMarketMinUsd: 175, estimatedMarketMaxUsd: 290 };
    const contaminated = buildAnalysisRecord({ ...llmOutput, price: peerCopiedPrice }, {
      snapshot, categories, peerStats, fingerprint, now,
    });
    expect(contaminated.price).toMatchObject({ verdict: "ABOVE_MARKET", confidence: "LOW", marketMin: 175, marketMax: 290, deviationPct: null, marketRangeUnreliable: true });
    expect(contaminated.needsReview).toBe(true);
    expect("priceDeviationPct" in toSummary(contaminated)).toBe(false);
    const sameRangeFits = buildAnalysisRecord(
      { ...llmOutput, category: { ...llmOutput.category, verdict: "FITS" }, price: peerCopiedPrice },
      { snapshot, categories, peerStats, fingerprint, now },
    );
    expect(sameRangeFits.price).toMatchObject({ confidence: "MEDIUM", deviationPct: 287, marketRangeUnreliable: false });

    const odd = buildAnalysisRecord(
      {
        description: { ...llmOutput.description, verdict: "MEDIOCRE", score: 14.6, issues: ["a", "b", "c", "d", "e"], suggestions: ["1", "2", "3", "4", "5", "6"] },
        category: { ...llmOutput.category, verdict: "MAYBE", suggestedCategory: "Unknown" },
        price: { ...llmOutput.price, pricedAs: null, verdict: "CHEAP", confidence: "SURE" },
      },
      { snapshot, categories, peerStats: null, fingerprint, now },
    );
    expect(odd.description).toMatchObject({ verdict: "ACCEPTABLE", score: 10 });
    expect(odd.description.issues).toHaveLength(4);
    expect(odd.description.suggestions).toHaveLength(4);
    expect(odd.category).toMatchObject({ verdict: "QUESTIONABLE", suggested: null });
    expect(odd.price).toMatchObject({ verdict: "INSUFFICIENT_DATA", confidence: "LOW", pricedAs: "", deviationPct: null, peers: null });
    expect(odd.needsReview).toBe(false);
    expect(buildAnalysisRecord({ ...llmOutput, description: { ...llmOutput.description, score: -3 } }, {
      snapshot, categories, peerStats, fingerprint, now,
    }).description.score).toBe(1);

    const summary = toSummary(odd);
    expect(summary).toEqual({
      needsReview: false,
      descriptionVerdict: "ACCEPTABLE",
      categoryVerdict: "QUESTIONABLE",
      priceVerdict: "INSUFFICIENT_DATA",
    });
    expect("priceDeviationPct" in summary).toBe(false);
  });

  // 9
  test.each([
    ["verdictBadgeClass GOOD", () => verdictBadgeClass("GOOD", null), "tc-badge tc-badge--success"],
    ["verdictBadgeClass ACCEPTABLE", () => verdictBadgeClass("ACCEPTABLE", null), "tc-badge"],
    ["verdictBadgeClass POOR", () => verdictBadgeClass("POOR", null), "tc-badge tc-badge--danger"],
    ["verdictBadgeClass FITS", () => verdictBadgeClass("FITS", null), "tc-badge tc-badge--success"],
    ["verdictBadgeClass QUESTIONABLE", () => verdictBadgeClass("QUESTIONABLE", null), "tc-badge"],
    ["verdictBadgeClass MISMATCH", () => verdictBadgeClass("MISMATCH", null), "tc-badge tc-badge--danger"],
    ["verdictBadgeClass FAIR", () => verdictBadgeClass("FAIR", -2), "tc-badge tc-badge--success"],
    ["verdictBadgeClass INSUFFICIENT_DATA", () => verdictBadgeClass("INSUFFICIENT_DATA", null), "tc-badge"],
    ["verdictBadgeClass ABOVE_MARKET +46", () => verdictBadgeClass("ABOVE_MARKET", 46), "tc-badge tc-badge--danger"],
    ["verdictBadgeClass ABOVE_MARKET +29", () => verdictBadgeClass("ABOVE_MARKET", 29), "tc-badge"],
    ["verdictBadgeClass BELOW_MARKET -30", () => verdictBadgeClass("BELOW_MARKET", -30), "tc-badge tc-badge--danger"],
    ["priceBadgeLabel ABOVE_MARKET", () => priceBadgeLabel("ABOVE_MARKET", 46), "ABOVE MARKET +46%"],
    ["priceBadgeLabel FAIR", () => priceBadgeLabel("FAIR", -2), "FAIR −2%"],
    ["priceBadgeLabel INSUFFICIENT_DATA", () => priceBadgeLabel("INSUFFICIENT_DATA", null), "INSUFFICIENT DATA"],
  ])("badgeHelpers_%s_returnsExpected", (_case, actual, expected) => {
    expect(actual()).toBe(expected);
  });

  // 10
  test("toAnalysisSummaryAndReviewReasons_storedData_mapsAndOrdersReasons", () => {
    expect(toAnalysisSummary({})).toBeNull();
    expect(toAnalysisSummary(null)).toBeNull();
    expect(toAnalysisSummary({ descriptionVerdict: "POOR" })).toBeNull();
    expect(toAnalysisSummary({ needsReview: "true" })).toBeNull();

    const stored: AnalysisSummary = {
      needsReview: true,
      descriptionVerdict: "POOR",
      categoryVerdict: "MISMATCH",
      priceVerdict: "ABOVE_MARKET",
      priceDeviationPct: 46,
    };
    const summary = toAnalysisSummary(stored);
    expect(summary).toEqual(stored);
    expect(reviewReasons(stored)).toBe("price +46% above market · category mismatch · poor description");
    expect(reviewReasons({ ...stored, descriptionVerdict: "GOOD", priceVerdict: "BELOW_MARKET", priceDeviationPct: -35 }))
      .toBe("price −35% below market · category mismatch");
    expect(reviewReasons({
      needsReview: false,
      descriptionVerdict: "ACCEPTABLE",
      categoryVerdict: "QUESTIONABLE",
      priceVerdict: "ABOVE_MARKET",
      priceDeviationPct: 29,
    })).toBe("");
  });
});
