import { afterEach, beforeEach, describe, expect, jest, test } from "@jest/globals";
import { createMocks } from "node-mocks-http";
import type { NextApiRequest, NextApiResponse } from "next";
import {
  BamlAbortError,
  BamlClientHttpError,
  BamlTimeoutError,
} from "@boundaryml/baml/errors";

type AsyncMock = (...args: unknown[]) => Promise<unknown>;

const mockAnalyzeOffer = jest.fn<AsyncMock>();

jest.mock("../../baml_client", () => ({
  b: {
    AnalyzeOffer: (...args: unknown[]) => mockAnalyzeOffer(...args),
  },
}));

const mockGetProduct = jest.fn<AsyncMock>();
const mockGetProducts = jest.fn<AsyncMock>();
const mockHostFetch = jest.fn<AsyncMock>();
const mockObjectsSave = jest.fn<AsyncMock>();
const mockSetData = jest.fn<AsyncMock>();

jest.mock("../../../server-sdk", () => ({
  createServerSDK: () => ({
    hostApp: {
      getProduct: (...args: unknown[]) => mockGetProduct(...args),
      getProducts: (...args: unknown[]) => mockGetProducts(...args),
      fetch: (...args: unknown[]) => mockHostFetch(...args),
    },
    thisPlugin: {
      setData: (...args: unknown[]) => mockSetData(...args),
      objects: {
        save: (...args: unknown[]) => mockObjectsSave(...args),
      },
    },
  }),
}));

import handler from "../pages/api/analyze";

function tokenWith(permissions: string[]): string {
  const encode = (value: object) => Buffer.from(JSON.stringify(value)).toString("base64url");
  return `${encode({ alg: "none" })}.${encode({ sub: "editor", permissions })}.signature`;
}

const EDITOR_TOKEN = tokenWith(["READ", "EDIT"]);
const VIEWER_TOKEN = tokenWith(["READ"]);

const PRODUCT = {
  id: 42,
  name: "Vistara 65\" OLED 4K TV",
  description: "The best TV ever. Buy now!",
  price: 2799,
  sku: "VIS-65-OLED",
  category: { id: 3, name: "Smart Home", description: "Connected home devices" },
};

const CATEGORIES = [
  { id: 1, name: "Televisions", description: "TVs and displays" },
  { id: 3, name: "Smart Home", description: "Connected home devices" },
];

const PEERS = [
  { id: 42, name: PRODUCT.name, price: 2799 },
  { id: 7, name: "Philips Hue Starter Kit E26", price: 179.99 },
  { id: 8, name: "Google Nest Learning Thermostat 4th Gen", price: 279.99 },
];

function llmResult(suggestedCategory: string | null = "Televisions") {
  return {
    description: {
      verdict: "POOR",
      score: 2,
      summary: "Vague.",
      issues: ["No specs"],
      suggestions: ["Add specs"],
    },
    category: { verdict: "MISMATCH", suggestedCategory, reasoning: "It is a TV." },
    price: {
      verdict: "ABOVE_MARKET",
      estimatedMarketMinUsd: 1500,
      estimatedMarketMaxUsd: 2000,
      confidence: "LOW",
      reasoning: "Pricier than comparable TVs.",
    },
  };
}

function givenHostData() {
  mockGetProduct.mockResolvedValue(PRODUCT);
  mockGetProducts.mockResolvedValue(PEERS);
  mockHostFetch.mockResolvedValue({ ok: true, status: 200, json: async () => CATEGORIES });
  mockObjectsSave.mockResolvedValue({});
  mockSetData.mockResolvedValue({});
}

interface ResponseBody {
  error?: string;
  code?: string;
  category?: { suggested?: unknown };
}

async function post(body: unknown, token: string | null = EDITOR_TOKEN) {
  const { req, res } = createMocks<NextApiRequest, NextApiResponse>({
    method: "POST",
    headers: token ? { authorization: `Bearer ${token}` } : {},
    body: body as Record<string, unknown>,
  });
  await handler(req, res);
  return { status: res._getStatusCode(), body: res._getJSONData() as ResponseBody, raw: res._getData() as string };
}

describe("POST /api/analyze", () => {
  beforeEach(() => {
    jest.resetAllMocks();
    jest.spyOn(console, "info").mockImplementation(() => undefined);
    jest.spyOn(console, "error").mockImplementation(() => undefined);
  });

  afterEach(() => {
    jest.restoreAllMocks();
  });

  test("analyze_getMethod_returns405WithAllowHeader", async () => {
    const { req, res } = createMocks<NextApiRequest, NextApiResponse>({ method: "GET" });

    await handler(req, res);

    expect(res._getStatusCode()).toBe(405);
    expect(res.getHeader("Allow")).toBe("POST");
    expect(res._getJSONData() as ResponseBody).toEqual({ error: "Method not allowed.", code: "METHOD_NOT_ALLOWED" });
  });

  test.each([[{}], [{ productId: "abc" }]])("analyze_invalidProductId_returns400 (%j)", async (body) => {
    const { status, body: json } = await post(body);

    expect(status).toBe(400);
    expect(json).toEqual({ error: "Invalid productId.", code: "BAD_REQUEST" });
  });

  test.each([
    ["readOnlyToken", VIEWER_TOKEN, 403, "FORBIDDEN"],
    ["missingBearer", null, 401, "UNAUTHENTICATED"],
  ])("analyze_%s_returns%sWithoutHostOrLlmCalls", async (_case, token, expectedStatus, code) => {
    const { status, body } = await post({ productId: "42" }, token);

    expect(status).toBe(expectedStatus);
    expect(body.code).toBe(code);
    expect(mockGetProduct).not.toHaveBeenCalled();
    expect(mockAnalyzeOffer).not.toHaveBeenCalled();
    expect(console.info).not.toHaveBeenCalled();
  });

  test.each([
    [
      "hostRejectsToken",
      () => mockGetProduct.mockRejectedValue(new Error("Host API error 401 GET /api/products/42: {\"message\":\"invalid token\"}")),
      401,
      { error: "Your session has expired. Please log in again.", code: "UNAUTHENTICATED" },
      0,
    ],
    [
      "productNotFound",
      () => mockGetProduct.mockRejectedValue(new Error("Host API error 404 GET /api/products/42: {\"message\":\"not found\"}")),
      404,
      { error: "Product not found.", code: "PRODUCT_NOT_FOUND" },
      0,
    ],
    [
      "invalidCategoriesPayload",
      () => {
        givenHostData();
        mockHostFetch.mockResolvedValue({ ok: true, status: 200, json: async () => ({ content: CATEGORIES }) });
      },
      502,
      { error: "The host application could not provide or store the analysis data.", code: "HOST_ERROR" },
      0,
    ],
    [
      "setDataFailsAfterSave",
      () => {
        givenHostData();
        mockAnalyzeOffer.mockResolvedValue(llmResult());
        mockSetData.mockRejectedValue(new Error("Host API error 500 PUT /api/plugins/ai-offer-analysis/products/42/data"));
      },
      502,
      { error: "The host application could not provide or store the analysis data.", code: "HOST_ERROR" },
      1,
    ],
  ])("analyze_hostFailure_%s_returnsMappedError", async (_case, arrange, expectedStatus, expectedBody, llmCalls) => {
    arrange();

    const { status, body } = await post({ productId: "42" });

    expect(status).toBe(expectedStatus);
    expect(body).toEqual(expectedBody);
    expect(mockAnalyzeOffer).toHaveBeenCalledTimes(llmCalls);
    expect(mockObjectsSave).toHaveBeenCalledTimes(llmCalls);
    expect(console.info).toHaveBeenCalledTimes(llmCalls);
  });

  test("analyze_happyPath_savesBoundRecordAndSummaryAndReturns200", async () => {
    givenHostData();
    mockAnalyzeOffer.mockResolvedValue(llmResult());

    const { status, body } = await post({ productId: 42 });

    expect(status).toBe(200);
    expect(body).toEqual(
      expect.objectContaining({
        productId: "42",
        fingerprint: expect.stringMatching(/^[0-9a-f]{14}$/),
        analyzedAt: expect.any(String),
        model: "gpt-4o-mini",
        needsReview: true,
      }),
    );
    expect(body.category?.suggested).toEqual({ id: 1, name: "Televisions" });
    expect(mockObjectsSave).toHaveBeenCalledWith("analysis", "42", body, { entityType: "PRODUCT", entityId: "42" });
    expect(mockSetData).toHaveBeenCalledWith(
      "42",
      expect.objectContaining({ needsReview: true, descriptionVerdict: "POOR", categoryVerdict: "MISMATCH" }),
    );
    expect(mockGetProducts).toHaveBeenCalledWith({ category: 3 });
    expect(mockHostFetch).toHaveBeenCalledWith("/api/categories");

    const [product, categories, peerStats, peers, options] = mockAnalyzeOffer.mock.calls[0];
    expect(product).toEqual({
      name: PRODUCT.name,
      description: PRODUCT.description,
      price: "USD 2799.00",
      categoryName: "Smart Home",
    });
    expect(categories).toEqual([
      { name: "Televisions", description: "TVs and displays" },
      { name: "Smart Home", description: "Connected home devices" },
    ]);
    expect(peerStats).toEqual({
      categoryName: "Smart Home",
      count: 2,
      min: "USD 179.99",
      median: "USD 229.99",
      max: "USD 279.99",
    });
    expect(peers).toEqual([
      { name: "Philips Hue Starter Kit E26", price: "USD 179.99" },
      { name: "Google Nest Learning Thermostat 4th Gen", price: "USD 279.99" },
    ]);
    expect((options as { signal?: unknown }).signal).toBeInstanceOf(AbortSignal);
    expect(console.info).toHaveBeenCalledTimes(1);
    expect(console.info).toHaveBeenCalledWith(
      expect.stringMatching(/^\[ai-offer-analysis\] llm-call productId=42 outcome=ok durationMs=\d+$/),
    );
  });

  test("analyze_gatewayRejects400_returns502ContentBlockedWithoutRawDetailOrSave", async () => {
    givenHostData();
    mockAnalyzeOffer.mockRejectedValue(
      new BamlClientHttpError("OfferAnalysisLlm", "Presidio blocked: SSN detected", 400, "raw litellm detail"),
    );

    const { status, body, raw } = await post({ productId: "42" });

    expect(status).toBe(502);
    expect(body).toEqual({
      error: "The AI gateway rejected the request (content policy). No analysis was saved.",
      code: "CONTENT_BLOCKED",
    });
    expect(raw).not.toContain("Presidio");
    expect(raw).not.toContain("raw litellm detail");
    expect(mockObjectsSave).not.toHaveBeenCalled();
    expect(console.error).toHaveBeenCalledWith(
      '[ai-offer-analysis] analyze failed productId=42 stage=llm code=CONTENT_BLOCKED error=BamlClientHttpError status=400 message="Presidio blocked: SSN detected"',
    );
    expect(console.info).toHaveBeenCalledTimes(1);
    expect(console.info).toHaveBeenCalledWith(expect.stringContaining("outcome=CONTENT_BLOCKED"));
  });

  test.each([
    ["BamlAbortError", new BamlAbortError("Operation was aborted"), "LLM_TIMEOUT"],
    ["BamlTimeoutError", new BamlTimeoutError("OfferAnalysisLlm", "Request timed out"), "LLM_TIMEOUT"],
    ["generic BamlClientHttpError", new BamlClientHttpError("OfferAnalysisLlm", "Bad gateway", 503, "upstream"), "LLM_UNAVAILABLE"],
    [
      "non-guardrail 400",
      new BamlClientHttpError("OfferAnalysisLlm", "Invalid model name passed in model=gpt-4o-mini", 400, "BadRequestError"),
      "LLM_UNAVAILABLE",
    ],
    [
      "generic 'blocked' 400",
      new BamlClientHttpError("OfferAnalysisLlm", "Request blocked: parameter not allowed for this model", 400, "BadRequestError"),
      "LLM_UNAVAILABLE",
    ],
  ])("analyze_%s_returns502WithClassifiedCode", async (_name, error, code) => {
    givenHostData();
    mockAnalyzeOffer.mockRejectedValue(error);

    const { status, body } = await post({ productId: "42" });

    expect(status).toBe(502);
    expect(body.code).toBe(code);
    expect(mockObjectsSave).not.toHaveBeenCalled();
    expect(console.info).toHaveBeenCalledTimes(1);
    expect(console.info).toHaveBeenCalledWith(expect.stringContaining(`outcome=${code}`));
  });

  test("analyze_suggestedCategoryNotInList_storesSuggestedNull", async () => {
    givenHostData();
    mockAnalyzeOffer.mockResolvedValue(llmResult("Gaming Consoles"));

    const { status, body } = await post({ productId: "42" });

    expect(status).toBe(200);
    expect(body.category).toEqual(
      expect.objectContaining({ verdict: "MISMATCH", suggested: null, assigned: { id: 3, name: "Smart Home" } }),
    );
  });
});
