import type { PluginObject } from "../../sdk";

export interface BoxDimensions {
  length: number;
  width: number;
  height: number;
}

export interface SizeLimits {
  maxLengthCm?: number;
  maxWidthCm?: number;
  maxHeightCm?: number;
}

export interface Carrier {
  id: string;
  name: string;
  active: boolean;
}

export interface ShippingMethod extends SizeLimits {
  id: string;
  carrierId: string;
  name: string;
  active: boolean;
}

export type SizeCheckResult = "no-dimensions" | "no-limits" | "fits" | "too-large";

export const UNKNOWN_CARRIER = "Unknown carrier";

export function errorMessage(context: string, err: unknown): string {
  const message = (err instanceof Error ? err.message : String(err)).trim();
  return `${context}: ${message || "Unknown error"}`;
}

export function compareNames(a: string, b: string): number {
  return a.toLowerCase().localeCompare(b.toLowerCase());
}

export function formatLimits(method: SizeLimits, noLimitText: string, unit = ""): string {
  const limits = [method.maxLengthCm, method.maxWidthCm, method.maxHeightCm];
  if (limits.every((limit) => limit == null)) return noLimitText;
  return limits.map((limit) => limit ?? "—").join(" × ") + unit;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

function isPositiveNumber(value: unknown): value is number {
  return typeof value === "number" && Number.isFinite(value) && value > 0;
}

export function toBoxDimensions(product: unknown): BoxDimensions | null {
  if (!isRecord(product) || !isRecord(product.pluginData)) return null;
  const data = product.pluginData["box-size"];
  if (!isRecord(data)) return null;
  const { length, width, height } = data;
  if (!isPositiveNumber(length) || !isPositiveNumber(width) || !isPositiveNumber(height)) return null;
  return { length, width, height };
}

const descending = (a: number, b: number) => b - a;

export function sizeCheck(box: BoxDimensions | null, method: SizeLimits): SizeCheckResult {
  if (!box) return "no-dimensions";
  const limits = [method.maxLengthCm, method.maxWidthCm, method.maxHeightCm];
  if (limits.every((limit) => limit == null)) return "no-limits";
  // Comparing sorted dimensions lets the box be rotated to fit.
  const sortedBox = [box.length, box.width, box.height].sort(descending);
  const sortedLimits = limits.map((limit) => limit ?? Infinity).sort(descending);
  return sortedBox.every((value, i) => value <= sortedLimits[i]) ? "fits" : "too-large";
}

// The domain id is objectId (UUID); PluginObject.id is the numeric DB key.
export function toCarrier(obj: PluginObject): Carrier {
  return { id: obj.objectId, name: String(obj.data.name ?? ""), active: obj.data.active !== false };
}

export function toShippingMethod(obj: PluginObject): ShippingMethod {
  const { carrierId, name, active, maxLengthCm, maxWidthCm, maxHeightCm } = obj.data;
  return {
    id: obj.objectId,
    carrierId: String(carrierId ?? ""),
    name: String(name ?? ""),
    active: active !== false,
    ...(isPositiveNumber(maxLengthCm) && { maxLengthCm }),
    ...(isPositiveNumber(maxWidthCm) && { maxWidthCm }),
    ...(isPositiveNumber(maxHeightCm) && { maxHeightCm }),
  };
}

export function toDisabledMethodIds(data: unknown): string[] {
  if (!isRecord(data) || !Array.isArray(data.disabledMethodIds)) return [];
  return data.disabledMethodIds.filter((id): id is string => typeof id === "string");
}

export function pruneDisabledIds(ids: string[], methods: ShippingMethod[]): string[] {
  const existing = new Set(methods.map((method) => method.id));
  return ids.filter((id) => existing.has(id));
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
