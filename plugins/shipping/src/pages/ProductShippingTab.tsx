import { useCallback, useEffect, useRef, useState } from "react";
import { getSDK } from "../../../sdk";
import {
  canEdit,
  compareNames,
  errorMessage,
  formatLimits,
  pruneDisabledIds,
  sizeCheck,
  toBoxDimensions,
  toCarrier,
  toDisabledMethodIds,
  toShippingMethod,
  UNKNOWN_CARRIER,
} from "../domain";
import type { BoxDimensions, Carrier, ShippingMethod } from "../domain";

const tabStyle = { padding: "1.5rem" } as const;

function tooLargeHint(names: string[]): string | null {
  if (names.length === 0) return null;
  const quoted = names.map((name) => `"${name}"`).join(", ");
  return names.length === 1
    ? `${quoted} is still available although the box exceeds its limits. Consider unticking it.`
    : `${quoted} are still available although the box exceeds their limits. Consider unticking them.`;
}

export function ProductShippingTab() {
  const productId = String(getSDK().thisPlugin.productId ?? "");

  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [refreshError, setRefreshError] = useState<string | null>(null);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [editable, setEditable] = useState(false);
  const [carriers, setCarriers] = useState<Carrier[]>([]);
  const [methods, setMethods] = useState<ShippingMethod[]>([]);
  const [box, setBox] = useState<BoxDimensions | null>(null);
  const [disabledIds, setDisabledIds] = useState<string[]>([]);
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState(false);
  const refreshingRef = useRef(false);
  const savingRef = useRef(false);
  const savedTimerRef = useRef<ReturnType<typeof setTimeout>>(undefined);

  useEffect(() => () => clearTimeout(savedTimerRef.current), []);

  useEffect(() => {
    if (!productId) {
      setLoading(false);
      return;
    }
    async function load() {
      refreshingRef.current = true;
      try {
        const sdk = getSDK();
        const [token, carrierObjects, methodObjects, data, product] = await Promise.all([
          sdk.hostApp.getToken(),
          sdk.thisPlugin.objects.list("carrier"),
          sdk.thisPlugin.objects.list("shipping-method"),
          sdk.thisPlugin.getData(productId),
          sdk.hostApp.getProduct(productId),
        ]);
        setEditable(canEdit(token));
        setCarriers(carrierObjects.map(toCarrier));
        setMethods(methodObjects.map(toShippingMethod));
        setDisabledIds(toDisabledMethodIds(data));
        setBox(toBoxDimensions(product));
      } catch (err) {
        setLoadError(errorMessage("Failed to load shipping data", err));
      } finally {
        refreshingRef.current = false;
        setLoading(false);
      }
    }
    void load();
  }, [productId]);

  const refresh = useCallback(async () => {
    if (refreshingRef.current || savingRef.current) return;
    refreshingRef.current = true;
    try {
      const sdk = getSDK();
      const [product, carrierObjects, methodObjects] = await Promise.all([
        sdk.hostApp.getProduct(productId),
        sdk.thisPlugin.objects.list("carrier"),
        sdk.thisPlugin.objects.list("shipping-method"),
      ]);
      setBox(toBoxDimensions(product));
      setCarriers(carrierObjects.map(toCarrier));
      setMethods(methodObjects.map(toShippingMethod));
      setRefreshError(null);
    } catch (err) {
      setRefreshError(errorMessage("Failed to refresh shipping data", err));
    } finally {
      refreshingRef.current = false;
    }
  }, [productId]);

  useEffect(() => {
    if (!productId) return;
    const onFocus = () => void refresh();
    const onVisibilityChange = () => {
      if (document.visibilityState === "visible") void refresh();
    };
    window.addEventListener("focus", onFocus);
    document.addEventListener("visibilitychange", onVisibilityChange);
    return () => {
      window.removeEventListener("focus", onFocus);
      document.removeEventListener("visibilitychange", onVisibilityChange);
    };
  }, [productId, refresh]);

  async function handleSave() {
    const ids = pruneDisabledIds(disabledIds, methods);
    savingRef.current = true;
    setSaving(true);
    setSaveError(null);
    try {
      const sdk = getSDK();
      if (ids.length > 0) {
        await sdk.thisPlugin.setData(productId, { disabledMethodIds: ids });
      } else {
        await sdk.thisPlugin.removeData(productId);
      }
      setDisabledIds(ids);
      setSaved(true);
      clearTimeout(savedTimerRef.current);
      savedTimerRef.current = setTimeout(() => setSaved(false), 2000);
    } catch (err) {
      setSaveError(errorMessage("Failed to save", err));
    } finally {
      savingRef.current = false;
      setSaving(false);
    }
  }

  function toggleMethod(id: string, available: boolean) {
    setDisabledIds((prev) => (available ? prev.filter((disabledId) => disabledId !== id) : [...prev, id]));
  }

  if (!productId) {
    return (
      <div className="tc-plugin" style={tabStyle}>
        <p>No product context available.</p>
      </div>
    );
  }

  if (loading) {
    return (
      <div className="tc-plugin" style={tabStyle}>
        Loading...
      </div>
    );
  }

  const heading = <h3 style={{ margin: "0 0 1rem" }}>Delivery methods for this product</h3>;

  if (loadError) {
    return (
      <div className="tc-plugin" style={tabStyle}>
        {heading}
        <p className="tc-error">{loadError}</p>
      </div>
    );
  }

  const carrierById = new Map(carriers.map((carrier) => [carrier.id, carrier]));
  const carrierName = (method: ShippingMethod) => carrierById.get(method.carrierId)?.name ?? UNKNOWN_CARRIER;
  const isActive = (method: ShippingMethod) => method.active && carrierById.get(method.carrierId)?.active === true;
  const sortedMethods = [...methods].sort(
    (a, b) => compareNames(carrierName(a), carrierName(b)) || compareNames(a.name, b.name),
  );
  const disabled = new Set(disabledIds);
  const disabledCount = methods.filter((method) => disabled.has(method.id)).length;
  const hint = tooLargeHint(
    sortedMethods
      .filter((method) => isActive(method) && !disabled.has(method.id) && sizeCheck(box, method) === "too-large")
      .map((method) => method.name),
  );

  function renderSizeCheck(method: ShippingMethod) {
    if (!isActive(method)) return <span className="tc-badge">Inactive</span>;
    switch (sizeCheck(box, method)) {
      case "no-dimensions":
        return "No dimensions";
      case "no-limits":
        return "No limits";
      case "fits":
        return <span className="tc-badge tc-badge--success">Fits</span>;
      case "too-large":
        return <span className="tc-badge tc-badge--danger">Too large</span>;
    }
  }

  return (
    <div className="tc-plugin" style={tabStyle}>
      {heading}
      <p>
        {box ? (
          <>
            <strong>Box:</strong> {box.length} × {box.width} × {box.height} cm (from Box Size)
          </>
        ) : (
          <>
            No box dimensions — size check unavailable. Set them in the <strong>Box Size</strong> tab.
          </>
        )}
      </p>
      {refreshError && <p className="tc-error">{refreshError}</p>}
      {methods.length === 0 ? (
        <p>No delivery methods configured. Add them in the Shipping section.</p>
      ) : (
        <>
          {!editable && <p>Read-only: you can see which methods are available but not change them.</p>}
          {hint && <p className="tc-error">{hint}</p>}
          <table className="tc-table">
            <thead>
              <tr>
                <th>Available</th>
                <th>Carrier</th>
                <th>Method</th>
                <th>Max (cm)</th>
                <th>Size check</th>
              </tr>
            </thead>
            <tbody>
              {sortedMethods.map((method) => (
                <tr key={method.id}>
                  <td>
                    <input
                      type="checkbox"
                      aria-label={`Available: ${method.name}`}
                      checked={!disabled.has(method.id)}
                      disabled={!editable || !isActive(method) || saving}
                      onChange={(e) => toggleMethod(method.id, e.target.checked)}
                    />
                  </td>
                  <td>{carrierName(method)}</td>
                  <td>{method.name}</td>
                  <td>{formatLimits(method, "—")}</td>
                  <td>{renderSizeCheck(method)}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {saveError && <p className="tc-error">{saveError}</p>}
          {editable && (
            <div className="tc-flex" style={{ marginTop: "1rem" }}>
              <button className="tc-primary-button" disabled={saving} onClick={() => void handleSave()}>
                {saved ? "Saved!" : "Save"}
              </button>
              <span>
                {disabledCount} of {methods.length} methods disabled for this product
              </span>
            </div>
          )}
        </>
      )}
    </div>
  );
}
