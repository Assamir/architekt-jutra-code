import { Fragment, useCallback, useEffect, useRef, useState } from "react";
import { getSDK } from "../../../sdk";
import {
  canEdit,
  compareNames,
  errorMessage,
  formatLimits,
  toCarrier,
  toShippingMethod,
  UNKNOWN_CARRIER,
} from "../domain";
import type { Carrier, ShippingMethod, SizeLimits } from "../domain";

interface CarrierDraft {
  name: string;
  active: boolean;
}

interface MethodForm {
  carrierId: string;
  name: string;
  length: string;
  width: string;
  height: string;
}

interface MethodDraft extends MethodForm {
  active: boolean;
}

type Editing =
  | { kind: "carrier"; id: string; draft: CarrierDraft }
  | { kind: "shipping-method"; id: string; draft: MethodDraft }
  | null;

type ValidatedMethod = { error: string } | { data: Record<string, unknown> };

const EMPTY_METHOD_FORM: MethodForm = { carrierId: "", name: "", length: "", width: "", height: "" };
const checkboxLabelStyle = { display: "inline-flex", gap: 8, alignItems: "center" } as const;
const addFormStyle = { flexWrap: "wrap", marginBottom: 12 } as const;

function parseLimits(form: MethodForm): SizeLimits | null {
  const limits: SizeLimits = {};
  const fields = [
    ["maxLengthCm", form.length],
    ["maxWidthCm", form.width],
    ["maxHeightCm", form.height],
  ] as const;
  for (const [key, raw] of fields) {
    const text = raw.trim();
    if (!text) continue;
    const value = Number(text);
    if (!Number.isFinite(value) || value <= 0) return null;
    limits[key] = value;
  }
  return limits;
}

function hasBadNumberInput(container: HTMLElement | null): boolean {
  return Array.from(container?.querySelectorAll<HTMLInputElement>('input[type="number"]') ?? []).some(
    (input) => input.validity.badInput,
  );
}

function validateMethod(
  form: MethodForm,
  carrierOptions: Carrier[],
  active: boolean,
  badLimitInput: boolean,
): ValidatedMethod {
  if (!carrierOptions.some((carrier) => carrier.id === form.carrierId)) return { error: "Select a carrier." };
  const name = form.name.trim();
  if (!name) return { error: "Method name is required." };
  const limits = badLimitInput ? null : parseLimits(form);
  if (!limits) return { error: "Limits must be positive numbers." };
  return { data: { carrierId: form.carrierId, name, active, ...limits } };
}

function blockedDeleteMessage(name: string, count: number): string {
  return count === 1
    ? `Cannot delete "${name}": it still has 1 delivery method. Delete or move it first.`
    : `Cannot delete "${name}": it still has ${count} delivery methods. Delete or move them first.`;
}

export function ShippingConfigPage() {
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [editable, setEditable] = useState(false);
  const [carriers, setCarriers] = useState<Carrier[]>([]);
  const [methods, setMethods] = useState<ShippingMethod[]>([]);
  const [carrierError, setCarrierError] = useState<string | null>(null);
  const [methodError, setMethodError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [newCarrierName, setNewCarrierName] = useState("");
  const [newMethod, setNewMethod] = useState<MethodForm>(EMPTY_METHOD_FORM);
  const [editing, setEditing] = useState<Editing>(null);
  const addMethodFormRef = useRef<HTMLDivElement>(null);
  const editMethodRowRef = useRef<HTMLTableRowElement>(null);

  const reload = useCallback(async () => {
    const sdk = getSDK();
    const [carrierObjects, methodObjects] = await Promise.all([
      sdk.thisPlugin.objects.list("carrier"),
      sdk.thisPlugin.objects.list("shipping-method"),
    ]);
    const nextCarriers = carrierObjects.map(toCarrier);
    const nextMethods = methodObjects.map(toShippingMethod);
    setCarriers(nextCarriers);
    setMethods(nextMethods);
    setEditing((prev) => {
      if (!prev) return prev;
      const items = prev.kind === "carrier" ? nextCarriers : nextMethods;
      return items.some((item) => item.id === prev.id) ? prev : null;
    });
  }, []);

  useEffect(() => {
    async function load() {
      try {
        const [token] = await Promise.all([getSDK().hostApp.getToken(), reload()]);
        setEditable(canEdit(token));
      } catch (err) {
        setLoadError(errorMessage("Failed to load shipping data", err));
      } finally {
        setLoading(false);
      }
    }
    void load();
  }, [reload]);

  const carrierById = new Map(carriers.map((carrier) => [carrier.id, carrier]));
  const carrierName = (method: ShippingMethod) => carrierById.get(method.carrierId)?.name ?? UNKNOWN_CARRIER;
  const isCarrierInactive = (method: ShippingMethod) => carrierById.get(method.carrierId)?.active !== true;
  const sortedCarriers = [...carriers].sort((a, b) => compareNames(a.name, b.name));
  const activeCarriers = sortedCarriers.filter((carrier) => carrier.active);
  const sortedMethods = [...methods].sort(
    (a, b) => compareNames(carrierName(a), carrierName(b)) || compareNames(a.name, b.name),
  );
  const editCarrierOptions = (method: ShippingMethod) =>
    sortedCarriers.filter((carrier) => carrier.active || carrier.id === method.carrierId);

  async function mutate(
    setError: (message: string | null) => void,
    context: string,
    action: () => Promise<unknown>,
  ): Promise<boolean> {
    setBusy(true);
    setError(null);
    try {
      try {
        await action();
      } catch (err) {
        setError(errorMessage(context, err));
        return false;
      }
      try {
        await reload();
      } catch (err) {
        setError(errorMessage("Failed to reload shipping data", err));
        return false;
      }
      return true;
    } finally {
      setBusy(false);
    }
  }

  async function handleAddCarrier() {
    const name = newCarrierName.trim();
    if (!name) {
      setCarrierError("Carrier name is required.");
      return;
    }
    const saved = await mutate(setCarrierError, "Failed to add carrier", () =>
      getSDK().thisPlugin.objects.save("carrier", crypto.randomUUID(), { name, active: true }),
    );
    if (saved) setNewCarrierName("");
  }

  async function handleToggleCarrier(carrier: Carrier) {
    await mutate(setCarrierError, "Failed to save carrier", () =>
      getSDK().thisPlugin.objects.save("carrier", carrier.id, { name: carrier.name, active: !carrier.active }),
    );
  }

  async function handleSaveCarrierEdit(id: string, draft: CarrierDraft) {
    const name = draft.name.trim();
    if (!name) {
      setCarrierError("Carrier name is required.");
      return;
    }
    const saved = await mutate(setCarrierError, "Failed to save carrier", () =>
      getSDK().thisPlugin.objects.save("carrier", id, { name, active: draft.active }),
    );
    if (saved) setEditing(null);
  }

  async function handleDeleteCarrier(carrier: Carrier) {
    setBusy(true);
    setCarrierError(null);
    let count: number;
    try {
      const latest = (await getSDK().thisPlugin.objects.list("shipping-method")).map(toShippingMethod);
      setMethods(latest);
      count = latest.filter((method) => method.carrierId === carrier.id).length;
    } catch (err) {
      setCarrierError(errorMessage("Failed to delete carrier", err));
      setBusy(false);
      return;
    }
    if (count > 0) {
      setCarrierError(blockedDeleteMessage(carrier.name, count));
      setBusy(false);
      return;
    }
    await mutate(setCarrierError, "Failed to delete carrier", () =>
      getSDK().thisPlugin.objects.delete("carrier", carrier.id),
    );
  }

  async function handleAddMethod() {
    const result = validateMethod(newMethod, activeCarriers, true, hasBadNumberInput(addMethodFormRef.current));
    if ("error" in result) {
      setMethodError(result.error);
      return;
    }
    const saved = await mutate(setMethodError, "Failed to add method", () =>
      getSDK().thisPlugin.objects.save("shipping-method", crypto.randomUUID(), result.data),
    );
    if (saved) setNewMethod(EMPTY_METHOD_FORM);
  }

  async function handleToggleMethod(method: ShippingMethod) {
    const { id, ...data } = method;
    await mutate(setMethodError, "Failed to save method", () =>
      getSDK().thisPlugin.objects.save("shipping-method", id, { ...data, active: !data.active }),
    );
  }

  async function handleSaveMethodEdit(method: ShippingMethod, draft: MethodDraft) {
    const result = validateMethod(
      draft,
      editCarrierOptions(method),
      draft.active,
      hasBadNumberInput(editMethodRowRef.current),
    );
    if ("error" in result) {
      setMethodError(result.error);
      return;
    }
    const saved = await mutate(setMethodError, "Failed to save method", () =>
      getSDK().thisPlugin.objects.save("shipping-method", method.id, result.data),
    );
    if (saved) setEditing(null);
  }

  async function handleDeleteMethod(method: ShippingMethod) {
    await mutate(setMethodError, "Failed to delete method", () =>
      getSDK().thisPlugin.objects.delete("shipping-method", method.id),
    );
  }

  function startMethodEdit(method: ShippingMethod) {
    setEditing({
      kind: "shipping-method",
      id: method.id,
      draft: {
        carrierId: carrierById.has(method.carrierId) ? method.carrierId : "",
        name: method.name,
        length: method.maxLengthCm?.toString() ?? "",
        width: method.maxWidthCm?.toString() ?? "",
        height: method.maxHeightCm?.toString() ?? "",
        active: method.active,
      },
    });
  }

  function updateCarrierDraft(patch: Partial<CarrierDraft>) {
    setEditing((prev) => (prev?.kind === "carrier" ? { ...prev, draft: { ...prev.draft, ...patch } } : prev));
  }

  function updateMethodDraft(patch: Partial<MethodDraft>) {
    setEditing((prev) => (prev?.kind === "shipping-method" ? { ...prev, draft: { ...prev.draft, ...patch } } : prev));
  }

  function renderLimitInputs(form: MethodForm, onChange: (patch: Partial<MethodForm>) => void, separator: string) {
    const fields = [
      ["length", "L", "Max length cm"],
      ["width", "W", "Max width cm"],
      ["height", "H", "Max height cm"],
    ] as const;
    return fields.map(([key, placeholder, label], i) => (
      <Fragment key={key}>
        {i > 0 && separator}
        <input
          className="tc-input"
          type="number"
          placeholder={placeholder}
          aria-label={label}
          style={{ width: 64 }}
          value={form[key]}
          onChange={(e) => onChange({ [key]: e.target.value })}
        />
      </Fragment>
    ));
  }

  function renderCarrierRow(carrier: Carrier) {
    const methodCount = methods.filter((method) => method.carrierId === carrier.id).length;
    if (editing?.kind === "carrier" && editing.id === carrier.id) {
      const { draft } = editing;
      return (
        <tr key={carrier.id}>
          <td>
            <input
              className="tc-input"
              aria-label="Carrier name"
              style={{ width: 220 }}
              value={draft.name}
              onChange={(e) => updateCarrierDraft({ name: e.target.value })}
            />
          </td>
          <td>{methodCount}</td>
          <td>
            <label style={checkboxLabelStyle}>
              <input
                type="checkbox"
                checked={draft.active}
                onChange={(e) => updateCarrierDraft({ active: e.target.checked })}
              />{" "}
              Active
            </label>
          </td>
          <td align="right">
            <button
              className="tc-primary-button"
              disabled={busy}
              onClick={() => void handleSaveCarrierEdit(carrier.id, draft)}
            >
              Save
            </button>{" "}
            <button className="tc-ghost-button" disabled={busy} onClick={() => setEditing(null)}>
              Cancel
            </button>
          </td>
        </tr>
      );
    }
    return (
      <tr key={carrier.id}>
        <td>{carrier.name}</td>
        <td>{methodCount}</td>
        <td>
          {editable ? (
            <label style={checkboxLabelStyle}>
              <input
                type="checkbox"
                aria-label={`Active: ${carrier.name}`}
                checked={carrier.active}
                disabled={busy}
                onChange={() => void handleToggleCarrier(carrier)}
              />{" "}
              Active
            </label>
          ) : carrier.active ? (
            <span className="tc-badge tc-badge--success">Active</span>
          ) : (
            <span className="tc-badge">Inactive</span>
          )}
        </td>
        <td align="right">
          {editable && (
            <>
              <button
                className="tc-ghost-button"
                disabled={busy}
                onClick={() => setEditing({ kind: "carrier", id: carrier.id, draft: { name: carrier.name, active: carrier.active } })}
              >
                Edit
              </button>
              <button
                className="tc-ghost-button tc-ghost-button--danger"
                disabled={busy}
                onClick={() => void handleDeleteCarrier(carrier)}
              >
                Delete
              </button>
            </>
          )}
        </td>
      </tr>
    );
  }

  function renderMethodStatus(method: ShippingMethod) {
    const carrierInactive = isCarrierInactive(method);
    if (!editable) {
      if (!method.active) return <span className="tc-badge">Inactive</span>;
      if (carrierInactive) return <span className="tc-badge">Inactive (carrier)</span>;
      return <span className="tc-badge tc-badge--success">Active</span>;
    }
    return (
      <>
        <label style={checkboxLabelStyle}>
          <input
            type="checkbox"
            aria-label={`Active: ${method.name}`}
            checked={method.active}
            disabled={busy}
            onChange={() => void handleToggleMethod(method)}
          />{" "}
          Active
        </label>
        {carrierInactive && (
          <>
            {" "}
            <span className="tc-badge">Carrier inactive</span>
          </>
        )}
      </>
    );
  }

  function renderMethodRow(method: ShippingMethod) {
    if (editing?.kind === "shipping-method" && editing.id === method.id) {
      const { draft } = editing;
      return (
        <tr key={method.id} ref={editMethodRowRef}>
          <td>
            <select
              className="tc-select"
              aria-label="Carrier"
              value={draft.carrierId}
              onChange={(e) => updateMethodDraft({ carrierId: e.target.value })}
            >
              {!carrierById.has(method.carrierId) && <option value="">-- Carrier --</option>}
              {editCarrierOptions(method).map((carrier) => (
                <option key={carrier.id} value={carrier.id}>
                  {carrier.name}
                </option>
              ))}
            </select>
          </td>
          <td>
            <input
              className="tc-input"
              aria-label="Method name"
              style={{ width: 200 }}
              value={draft.name}
              onChange={(e) => updateMethodDraft({ name: e.target.value })}
            />
          </td>
          <td>
            <div className="tc-flex">{renderLimitInputs(draft, updateMethodDraft, "×")}</div>
          </td>
          <td>
            <label style={checkboxLabelStyle}>
              <input
                type="checkbox"
                checked={draft.active}
                onChange={(e) => updateMethodDraft({ active: e.target.checked })}
              />{" "}
              Active
            </label>
          </td>
          <td align="right">
            <button
              className="tc-primary-button"
              disabled={busy}
              onClick={() => void handleSaveMethodEdit(method, draft)}
            >
              Save
            </button>{" "}
            <button className="tc-ghost-button" disabled={busy} onClick={() => setEditing(null)}>
              Cancel
            </button>
          </td>
        </tr>
      );
    }
    return (
      <tr key={method.id}>
        <td>{carrierName(method)}</td>
        <td>{method.name}</td>
        <td>{formatLimits(method, "No limit", " cm")}</td>
        <td>{renderMethodStatus(method)}</td>
        <td align="right">
          {editable && (
            <>
              <button className="tc-ghost-button" disabled={busy} onClick={() => startMethodEdit(method)}>
                Edit
              </button>
              <button
                className="tc-ghost-button tc-ghost-button--danger"
                disabled={busy}
                onClick={() => void handleDeleteMethod(method)}
              >
                Delete
              </button>
            </>
          )}
        </td>
      </tr>
    );
  }

  if (loading) return <p>Loading...</p>;

  return (
    <div className="tc-plugin" style={{ padding: "1rem", maxWidth: 900 }}>
      <h1>Shipping</h1>
      {loadError ? (
        <p className="tc-error">{loadError}</p>
      ) : (
        <>
          {!editable && <p>You have read-only access. Ask an editor to change carriers or delivery methods.</p>}

          <section className="tc-section">
            <h2>Carriers</h2>
            {editable && (
              <div className="tc-flex" style={addFormStyle}>
                <input
                  className="tc-input"
                  placeholder="Carrier name (e.g. GLS)"
                  aria-label="Carrier name"
                  style={{ width: 260 }}
                  value={newCarrierName}
                  onChange={(e) => setNewCarrierName(e.target.value)}
                />
                <button className="tc-primary-button" disabled={busy} onClick={() => void handleAddCarrier()}>
                  Add carrier
                </button>
              </div>
            )}
            {carrierError && <p className="tc-error">{carrierError}</p>}
            <table className="tc-table">
              <thead>
                <tr>
                  <th>Name</th>
                  <th>Methods</th>
                  <th>Status</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>{sortedCarriers.map(renderCarrierRow)}</tbody>
            </table>
          </section>

          <section className="tc-section">
            <h2>Delivery methods</h2>
            {editable && (
              <div className="tc-flex" style={addFormStyle} ref={addMethodFormRef}>
                <select
                  className="tc-select"
                  aria-label="Carrier"
                  value={newMethod.carrierId}
                  onChange={(e) => setNewMethod({ ...newMethod, carrierId: e.target.value })}
                >
                  <option value="">-- Carrier --</option>
                  {activeCarriers.map((carrier) => (
                    <option key={carrier.id} value={carrier.id}>
                      {carrier.name}
                    </option>
                  ))}
                </select>
                <input
                  className="tc-input"
                  placeholder="Method name"
                  aria-label="Method name"
                  style={{ width: 180 }}
                  value={newMethod.name}
                  onChange={(e) => setNewMethod({ ...newMethod, name: e.target.value })}
                />
                <span>Max (cm, optional):</span>
                {renderLimitInputs(newMethod, (patch) => setNewMethod({ ...newMethod, ...patch }), "")}
                <button className="tc-primary-button" disabled={busy} onClick={() => void handleAddMethod()}>
                  Add method
                </button>
              </div>
            )}
            {methodError && <p className="tc-error">{methodError}</p>}
            <table className="tc-table">
              <thead>
                <tr>
                  <th>Carrier</th>
                  <th>Method</th>
                  <th>Max size (L×W×H)</th>
                  <th>Status</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>{sortedMethods.map(renderMethodRow)}</tbody>
            </table>
          </section>
        </>
      )}
    </div>
  );
}
