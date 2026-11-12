import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { ReactNode } from "react";
import { getSDK } from "../../../sdk";
import {
  CHANNEL_NAME,
  OBJECT_TYPE,
  canEdit,
  computeFingerprint,
  formatAmount,
  isCompactSection,
  priceBadgeLabel,
  toOfferAnalysis,
  toProductSnapshot,
  verdictBadgeClass,
  verdictLabel,
} from "../domain";
import type { AnalysisRecord } from "../domain";

const NOT_ANALYZED_REASON = "Started automatically: this product has not been analyzed yet.";
const CONTENT_CHANGED_REASON = "Started automatically: product content changed since the last analysis.";
const NETWORK_ERROR = "Network error. Please check your connection and try again.";
const UNEXPECTED_ERROR = "Unexpected error.";
const CONTENT_BLOCKED_HINT =
  "The product text may contain data blocked by the gateway (e.g. a card number). Edit the product and try again.";

interface RunError {
  message: string;
  code: string;
}

function isVisible(): boolean {
  return window.innerWidth > 0 && window.innerHeight > 0;
}

function formatAnalyzedAt(iso: string): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return iso;
  return date.toLocaleString("en-GB", {
    day: "numeric",
    month: "short",
    year: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  });
}

async function readRunError(response: Response): Promise<RunError> {
  try {
    const body = (await response.json()) as { error?: unknown; code?: unknown };
    return {
      message: typeof body.error === "string" ? body.error : UNEXPECTED_ERROR,
      code: typeof body.code === "string" ? body.code : "INTERNAL",
    };
  } catch {
    return { message: UNEXPECTED_ERROR, code: "INTERNAL" };
  }
}

function broadcastSaved(productId: string, needsReview: boolean) {
  if (typeof BroadcastChannel === "undefined") return;
  const channel = new BroadcastChannel(CHANNEL_NAME);
  channel.postMessage({ type: "analysis-saved", productId, needsReview });
  channel.close();
}

function SectionCard({ title, badges, children }: { title: string; badges: ReactNode; children: ReactNode }) {
  return (
    <div className="tc-card tc-section" style={{ padding: 16 }}>
      <div className="tc-flex" style={{ justifyContent: "space-between" }}>
        <h4 style={{ margin: 0 }}>{title}</h4>
        <div className="tc-flex">{badges}</div>
      </div>
      {children}
    </div>
  );
}

function BulletList({ title, items, first }: { title: string; items: string[]; first: boolean }) {
  return (
    <>
      <p style={{ margin: first ? "8px 0 4px" : "0 0 4px" }}>
        <strong>{title}</strong>
      </p>
      <ul style={{ margin: "0 0 8px", paddingLeft: 20 }}>
        {items.map((item, i) => (
          <li key={i}>{item}</li>
        ))}
      </ul>
    </>
  );
}

function DescriptionSection({ description }: { description: AnalysisRecord["description"] }) {
  const { verdict, score, summary, issues, suggestions } = description;
  const showSummary = isCompactSection(verdict) || (issues.length === 0 && suggestions.length === 0);
  return (
    <SectionCard
      title="Description quality"
      badges={
        <>
          <span className={verdictBadgeClass(verdict, null)}>{verdictLabel(verdict)}</span>
          <span className="tc-badge">Score {score}/10</span>
        </>
      }
    >
      {showSummary ? (
        <p style={{ margin: "8px 0 0" }}>{summary}</p>
      ) : (
        <>
          {issues.length > 0 && <BulletList title="Issues" items={issues} first />}
          {suggestions.length > 0 && <BulletList title="Suggestions" items={suggestions} first={issues.length === 0} />}
        </>
      )}
    </SectionCard>
  );
}

function CategorySection({ category }: { category: AnalysisRecord["category"] }) {
  const { verdict, assigned, suggested, reasoning } = category;
  return (
    <SectionCard
      title="Category fit"
      badges={<span className={verdictBadgeClass(verdict, null)}>{verdictLabel(verdict)}</span>}
    >
      {isCompactSection(verdict) ? (
        <p style={{ margin: "8px 0 0" }}>
          Assigned: <strong>{assigned.name}</strong> — {reasoning}
        </p>
      ) : (
        <>
          <table className="tc-table" style={{ margin: "8px 0" }}>
            <tbody>
              <tr>
                <td style={{ width: "40%" }}>Assigned category</td>
                <td>{assigned.name}</td>
              </tr>
              {suggested && (
                <tr>
                  <td>Suggested category</td>
                  <td>
                    <strong>{suggested.name}</strong>
                  </td>
                </tr>
              )}
            </tbody>
          </table>
          <p style={{ margin: 0 }}>{reasoning}</p>
        </>
      )}
    </SectionCard>
  );
}

function PriceSection({ price }: { price: AnalysisRecord["price"] }) {
  const { verdict, confidence, pricedAs, productPrice, marketMin, marketMax, deviationPct, marketRangeUnreliable, peers, reasoning } =
    price;
  const hasMarket = marketMin !== null && marketMax !== null && !marketRangeUnreliable;
  const noMarketText = marketRangeUnreliable ? "market range unreliable" : "market range unknown";
  return (
    <SectionCard
      title="Price vs market"
      badges={
        <>
          <span className={verdictBadgeClass(verdict, deviationPct)}>{priceBadgeLabel(verdict, deviationPct)}</span>
          <span className="tc-badge">Confidence: {confidence}</span>
        </>
      }
    >
      {isCompactSection(verdict) ? (
        <p style={{ margin: "8px 0 0" }}>
          USD {formatAmount(productPrice)} vs{" "}
          {hasMarket ? `market USD ${formatAmount(marketMin)}–${formatAmount(marketMax)}` : noMarketText}
          {peers &&
            ` · peers ${formatAmount(peers.min)} / ${formatAmount(peers.median)} / ${formatAmount(peers.max)}`}
        </p>
      ) : (
        <>
          <table className="tc-table" style={{ margin: "8px 0" }}>
            <thead>
              <tr>
                <th>Metric</th>
                <th align="right">USD</th>
              </tr>
            </thead>
            <tbody>
              <tr>
                <td>Product price</td>
                <td align="right">{formatAmount(productPrice)}</td>
              </tr>
              <tr>
                <td>Estimated market range</td>
                <td align="right">
                  {hasMarket ? `${formatAmount(marketMin)} – ${formatAmount(marketMax)}` : marketRangeUnreliable ? "unreliable" : "—"}
                </td>
              </tr>
              <tr>
                {peers ? (
                  <>
                    <td>
                      Catalog peers ({peers.categoryName}, {peers.count}) min / median / max
                    </td>
                    <td align="right">
                      {formatAmount(peers.min)} / {formatAmount(peers.median)} / {formatAmount(peers.max)}
                    </td>
                  </>
                ) : (
                  <td colSpan={2}>No other products in this category</td>
                )}
              </tr>
            </tbody>
          </table>
          <p style={{ margin: 0 }}>{reasoning}</p>
        </>
      )}
      {marketRangeUnreliable && (
        <p style={{ margin: "4px 0 0", fontSize: 12 }}>
          Market estimate unreliable — it matched catalog peers from a different category.
        </p>
      )}
      {pricedAs && <p style={{ margin: "4px 0 0", fontSize: 12 }}>Priced as: {pricedAs}</p>}
    </SectionCard>
  );
}

function AnalysisSections({ analysis }: { analysis: AnalysisRecord }) {
  return (
    <>
      <DescriptionSection description={analysis.description} />
      <CategorySection category={analysis.category} />
      <PriceSection price={analysis.price} />
    </>
  );
}

export default function ProductTab() {
  const sdk = useMemo(() => (typeof window !== "undefined" ? getSDK() : null), []);
  const rawProductId = sdk?.thisPlugin.productId;
  const productId = rawProductId == null ? "" : String(rawProductId);

  const [loading, setLoading] = useState(true);
  const [missingProduct, setMissingProduct] = useState(false);
  const [loadFailed, setLoadFailed] = useState(false);
  const [editor, setEditor] = useState(false);
  const [analysis, setAnalysis] = useState<AnalysisRecord | null>(null);
  const [currentFingerprint, setCurrentFingerprint] = useState<string | null>(null);
  const [running, setRunning] = useState(false);
  const [runReason, setRunReason] = useState<string | null>(null);
  const [runError, setRunError] = useState<RunError | null>(null);

  const inFlight = useRef(false);
  const autoRunAttempted = useRef(false);

  useEffect(() => {
    if (!sdk) return;
    if (!productId) {
      setMissingProduct(true);
      setLoading(false);
      return;
    }
    let active = true;
    async function load() {
      try {
        const [token, objects, product] = await Promise.all([
          sdk!.hostApp.getToken(),
          sdk!.thisPlugin.objects.list(OBJECT_TYPE, { entityType: "PRODUCT", entityId: productId }),
          sdk!.hostApp.getProduct(productId),
        ]);
        const fingerprint = computeFingerprint(toProductSnapshot(product));
        if (!active) return;
        setEditor(canEdit(token));
        setAnalysis(objects.length > 0 ? toOfferAnalysis(objects[0]) : null);
        setCurrentFingerprint(fingerprint);
      } catch {
        if (active) setLoadFailed(true);
      } finally {
        if (active) setLoading(false);
      }
    }
    void load();
    return () => {
      active = false;
    };
  }, [sdk, productId]);

  const run = useCallback(
    async (reason: string | null) => {
      if (!sdk || inFlight.current) return;
      inFlight.current = true;
      setRunning(true);
      setRunReason(reason);
      setRunError(null);
      try {
        const token = await sdk.hostApp.getToken();
        const response = await fetch("/api/analyze", {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
            ...(token ? { Authorization: `Bearer ${token}` } : {}),
          },
          body: JSON.stringify({ productId }),
        });
        if (!response.ok) {
          setRunError(await readRunError(response));
          return;
        }
        const record = (await response.json()) as AnalysisRecord;
        setAnalysis(record);
        setCurrentFingerprint(record.fingerprint);
        broadcastSaved(productId, record.needsReview);
      } catch (err) {
        setRunError({ message: err instanceof TypeError ? NETWORK_ERROR : UNEXPECTED_ERROR, code: "CLIENT" });
      } finally {
        inFlight.current = false;
        setRunning(false);
      }
    },
    [sdk, productId],
  );

  const stale = analysis !== null && analysis.fingerprint !== currentFingerprint;
  const autoRunReason =
    !loading && !loadFailed && !missingProduct && editor && (analysis === null || stale)
      ? analysis === null
        ? NOT_ANALYZED_REASON
        : CONTENT_CHANGED_REASON
      : null;

  useEffect(() => {
    if (!autoRunReason || autoRunAttempted.current) return;
    const start = () => {
      autoRunAttempted.current = true;
      void run(autoRunReason);
    };
    if (isVisible()) {
      start();
      return;
    }
    const onResize = () => {
      if (!isVisible()) return;
      window.removeEventListener("resize", onResize);
      start();
    };
    window.addEventListener("resize", onResize);
    return () => window.removeEventListener("resize", onResize);
  }, [autoRunReason, run]);

  if (loading) {
    return (
      <div className="tc-plugin" style={{ padding: 16 }}>
        <p>Loading analysis…</p>
      </div>
    );
  }

  if (missingProduct) {
    return (
      <div className="tc-plugin" style={{ padding: 16 }}>
        <div className="tc-card" style={{ padding: 24 }}>
          <p className="tc-error" style={{ margin: 0 }}>
            Product ID is missing. This tab must be opened from a product detail page.
          </p>
        </div>
      </div>
    );
  }

  if (loadFailed) {
    return (
      <div className="tc-plugin" style={{ padding: 16 }}>
        <p className="tc-error">Could not load the analysis.</p>
      </div>
    );
  }

  if (!editor && analysis === null) {
    return (
      <div className="tc-plugin" style={{ padding: 16 }}>
        <h2 style={{ margin: "0 0 12px" }}>AI Offer Analysis</h2>
        <div className="tc-card" style={{ padding: 32, textAlign: "center" }}>
          <h4 style={{ margin: "0 0 4px" }}>Not analyzed yet</h4>
          <p style={{ margin: 0 }}>An editor needs to open this tab to run the first analysis.</p>
        </div>
      </div>
    );
  }

  const overall = running
    ? { className: "tc-badge", label: "Analyzing…" }
    : analysis === null
      ? { className: "tc-badge", label: "Not analyzed" }
      : analysis.needsReview
        ? { className: "tc-badge tc-badge--danger", label: "Needs review" }
        : { className: "tc-badge tc-badge--success", label: "Looks good" };

  return (
    <div className="tc-plugin" style={{ padding: 16 }}>
      <div className="tc-flex" style={{ justifyContent: "space-between", marginBottom: 16 }}>
        <div>
          <h2 style={{ margin: "0 0 4px" }}>AI Offer Analysis</h2>
          {analysis && (
            <p style={{ margin: 0, fontSize: 12 }}>
              Analyzed {formatAnalyzedAt(analysis.analyzedAt)} · {analysis.model} via LiteLLM
            </p>
          )}
        </div>
        <div className="tc-flex">
          <span className={overall.className}>{overall.label}</span>
          {editor && !runError && (
            <button className="tc-primary-button" onClick={() => void run(null)} disabled={running}>
              Re-analyze
            </button>
          )}
        </div>
      </div>

      {!editor && stale && (
        <p style={{ margin: "0 0 12px", fontSize: 12 }}>
          Product changed since this analysis. Only editors can run a new analysis.
        </p>
      )}

      <div aria-live="polite">
        {running ? (
          <div className="tc-card" style={{ padding: 32, textAlign: "center" }}>
            <progress aria-label="Analysis in progress" style={{ display: "block", margin: "0 auto 12px" }} />
            <h4 style={{ margin: "0 0 4px" }}>Analyzing this offer…</h4>
            <p style={{ margin: 0 }}>
              Checking description quality, category fit and price against market norms. This usually takes 5–15
              seconds.
            </p>
            {runReason && <p style={{ margin: "8px 0 0", fontSize: 12 }}>{runReason}</p>}
          </div>
        ) : (
          <>
            {runError && (
              <div className="tc-card tc-section" style={{ padding: 24 }}>
                <p className="tc-error" style={{ margin: "0 0 8px" }}>
                  {runError.message}
                </p>
                {runError.code === "CONTENT_BLOCKED" && (
                  <p style={{ margin: "0 0 12px", fontSize: 12 }}>{CONTENT_BLOCKED_HINT}</p>
                )}
                {editor && (
                  <button className="tc-primary-button" onClick={() => void run(null)} disabled={running}>
                    Retry
                  </button>
                )}
              </div>
            )}
            {analysis && <AnalysisSections analysis={analysis} />}
          </>
        )}
      </div>
    </div>
  );
}
