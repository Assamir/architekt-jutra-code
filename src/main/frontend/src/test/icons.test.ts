import { describe, it, expect } from "vitest";
import { Plug, Truck, Warehouse } from "lucide-react";
import { resolveIcon } from "../components/shared/Icons";

describe("resolveIcon", () => {
  it("resolves truck to the Truck icon", () => {
    expect(resolveIcon("truck")).toBe(Truck);
  });

  it("resolves warehouse to the Warehouse icon", () => {
    expect(resolveIcon("warehouse")).toBe(Warehouse);
  });

  it("falls back to the Plug icon for an unknown name", () => {
    expect(resolveIcon("definitely-not-an-icon")).toBe(Plug);
  });
});
