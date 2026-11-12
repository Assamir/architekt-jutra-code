import { useEffect, useMemo, useState } from "react";
import { getSDK } from "../../../sdk";
import { CHANNEL_NAME, reviewReasons, toAnalysisSummary } from "../domain";
import type { AnalysisSummary } from "../domain";

type BadgeState = { kind: "loading" } | { kind: "unavailable" } | { kind: "ready"; summary: AnalysisSummary | null };

function isSavedMessage(data: unknown, productId: string): boolean {
  if (typeof data !== "object" || data === null) return false;
  const message = data as { type?: unknown; productId?: unknown };
  return message.type === "analysis-saved" && String(message.productId) === productId;
}

export default function ProductBadge() {
  const sdk = useMemo(() => (typeof window !== "undefined" ? getSDK() : null), []);
  const rawProductId = sdk?.thisPlugin.productId;
  const productId = rawProductId == null ? "" : String(rawProductId);

  const [state, setState] = useState<BadgeState>({ kind: "loading" });

  useEffect(() => {
    if (!sdk) return;
    if (!productId) {
      setState({ kind: "unavailable" });
      return;
    }
    let active = true;
    async function load() {
      try {
        const data = await sdk!.thisPlugin.getData(productId);
        if (active) setState({ kind: "ready", summary: toAnalysisSummary(data) });
      } catch {
        if (active) setState({ kind: "unavailable" });
      }
    }
    void load();

    const channel = typeof BroadcastChannel !== "undefined" ? new BroadcastChannel(CHANNEL_NAME) : null;
    if (channel) {
      channel.onmessage = (event: MessageEvent) => {
        if (isSavedMessage(event.data, productId)) void load();
      };
    }
    return () => {
      active = false;
      channel?.close();
    };
  }, [sdk, productId]);

  if (state.kind === "loading") return null;

  return (
    <div className="tc-plugin tc-flex" style={{ padding: "12px 16px" }}>
      {state.kind === "unavailable" ? (
        <span className="tc-badge">AI review: unavailable</span>
      ) : state.summary === null ? (
        <span className="tc-badge">AI review: not analyzed yet</span>
      ) : state.summary.needsReview ? (
        <>
          <span className="tc-badge tc-badge--danger">AI review: Needs review</span>
          <span style={{ fontSize: 12 }}>{reviewReasons(state.summary)}</span>
        </>
      ) : (
        <span className="tc-badge tc-badge--success">AI review: Looks good</span>
      )}
    </div>
  );
}
