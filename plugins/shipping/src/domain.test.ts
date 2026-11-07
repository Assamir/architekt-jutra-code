import { describe, it, expect } from "vitest";
import type { PluginObject } from "../../sdk";
import { canEdit, pruneDisabledIds, sizeCheck, toBoxDimensions, toCarrier, toDisabledMethodIds, toShippingMethod } from "./domain";
import type { BoxDimensions, ShippingMethod } from "./domain";

const box: BoxDimensions = { length: 120, width: 12, height: 48 };

describe("sizeCheck", () => {
  it("returns fits when every dimension is within the limits", () => {
    expect(sizeCheck({ length: 30, width: 20, height: 10 }, { maxLengthCm: 40, maxWidthCm: 30, maxHeightCm: 20 })).toBe("fits");
  });

  it("returns too-large when a dimension exceeds the limits", () => {
    expect(sizeCheck({ length: 50, width: 20, height: 10 }, { maxLengthCm: 40, maxWidthCm: 30, maxHeightCm: 20 })).toBe("too-large");
  });

  it("returns too-large for the spec example box against a 64x38x41 locker", () => {
    expect(sizeCheck(box, { maxLengthCm: 64, maxWidthCm: 38, maxHeightCm: 41 })).toBe("too-large");
  });

  it("returns fits when the box only fits after rotation", () => {
    expect(sizeCheck({ length: 10, width: 20, height: 30 }, { maxLengthCm: 30, maxWidthCm: 20, maxHeightCm: 10 })).toBe("fits");
  });

  it("treats a missing limit as unlimited", () => {
    expect(sizeCheck(box, { maxLengthCm: 130 })).toBe("fits");
  });

  it("returns no-limits when the method has no limits and no-dimensions when the box is missing", () => {
    expect(sizeCheck(box, {})).toBe("no-limits");
    expect(sizeCheck(null, { maxLengthCm: 10 })).toBe("no-dimensions");
  });
});

describe("toBoxDimensions", () => {
  it("returns dimensions from complete box-size plugin data", () => {
    const product = { id: 1, pluginData: { "box-size": { length: 120, width: 12, height: 48 } } };
    expect(toBoxDimensions(product)).toEqual(box);
  });

  it("returns null for missing, partial or non-numeric box-size data", () => {
    expect(toBoxDimensions(null)).toBeNull();
    expect(toBoxDimensions({ id: 1 })).toBeNull();
    expect(toBoxDimensions({ pluginData: { "box-size": { length: 10, width: 5 } } })).toBeNull();
    expect(toBoxDimensions({ pluginData: { "box-size": { length: "10", width: 5, height: 5 } } })).toBeNull();
    expect(toBoxDimensions({ pluginData: { "box-size": { length: 0, width: 5, height: 5 } } })).toBeNull();
  });
});

function pluginObject(objectType: string, objectId: string, data: Record<string, unknown>): PluginObject {
  return { id: "42", pluginId: "shipping", objectType, objectId, data };
}

function tokenWith(payload: Record<string, unknown>): string {
  const base64Url = btoa(JSON.stringify(payload)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
  return `header.${base64Url}.signature`;
}

describe("toCarrier and toShippingMethod", () => {
  it("takes the domain id from objectId, never from the numeric id", () => {
    expect(toCarrier(pluginObject("carrier", "uuid-carrier", { name: "InPost" })).id).toBe("uuid-carrier");
    expect(toShippingMethod(pluginObject("shipping-method", "uuid-method", { carrierId: "uuid-carrier", name: "Locker" })).id).toBe("uuid-method");
  });

  it("defaults active to true unless it is exactly false", () => {
    expect(toCarrier(pluginObject("carrier", "c1", { name: "DPD" })).active).toBe(true);
    expect(toCarrier(pluginObject("carrier", "c1", { name: "DPD", active: false })).active).toBe(false);
    expect(toShippingMethod(pluginObject("shipping-method", "m1", { carrierId: "c1", name: "Courier", active: "no" })).active).toBe(true);
    expect(toShippingMethod(pluginObject("shipping-method", "m1", { carrierId: "c1", name: "Courier", active: false })).active).toBe(false);
  });

  it("keeps finite positive limits and drops invalid ones", () => {
    const method = toShippingMethod(
      pluginObject("shipping-method", "m1", { carrierId: "c1", name: "Locker", maxLengthCm: 64, maxWidthCm: 0, maxHeightCm: "41" }),
    );
    expect(method).toEqual({ id: "m1", carrierId: "c1", name: "Locker", active: true, maxLengthCm: 64 });
    const invalid = toShippingMethod(
      pluginObject("shipping-method", "m2", { carrierId: "c1", name: "Pallet", maxLengthCm: -5, maxWidthCm: Number.NaN, maxHeightCm: Infinity }),
    );
    expect(invalid).toEqual({ id: "m2", carrierId: "c1", name: "Pallet", active: true });
  });
});

describe("disabled method ids", () => {
  it("reads string ids from plugin data and falls back to an empty list", () => {
    expect(toDisabledMethodIds({ disabledMethodIds: ["m1", 7, null, "m2"] })).toEqual(["m1", "m2"]);
    expect(toDisabledMethodIds({})).toEqual([]);
    expect(toDisabledMethodIds(null)).toEqual([]);
  });

  it("prunes unknown ids and keeps ids of inactive methods", () => {
    const methods: ShippingMethod[] = [
      { id: "m1", carrierId: "c1", name: "Locker", active: true },
      { id: "m2", carrierId: "c1", name: "Courier", active: false },
    ];
    expect(pruneDisabledIds(["m1", "gone", "m2"], methods)).toEqual(["m1", "m2"]);
  });
});

describe("canEdit", () => {
  it("returns true for an EDIT token and false for a READ-only token", () => {
    expect(canEdit(tokenWith({ sub: "admin", permissions: ["READ", "EDIT"] }))).toBe(true);
    expect(canEdit(tokenWith({ sub: "viewer", permissions: ["READ"] }))).toBe(false);
  });

  it("decodes base64url payloads containing - and _ characters", () => {
    const token = tokenWith({ sub: "??>>", permissions: ["EDIT"] });
    expect(token.split(".")[1]).toMatch(/-/);
    expect(token.split(".")[1]).toMatch(/_/);
    expect(canEdit(token)).toBe(true);
  });

  it("returns false for null, malformed or non-array permissions", () => {
    expect(canEdit(null)).toBe(false);
    expect(canEdit("not-a-jwt")).toBe(false);
    expect(canEdit("header.%%%.signature")).toBe(false);
    expect(canEdit(tokenWith({ permissions: "EDIT" }))).toBe(false);
  });
});
