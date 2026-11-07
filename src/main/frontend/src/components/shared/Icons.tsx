import {
  Package,
  LayoutList,
  Leaf,
  Plug,
  Truck,
  Warehouse,
  Image as ImageIcon,
  type LucideIcon,
} from "lucide-react";
import * as icons from "lucide-react";

export const ProductsIcon = Package;
export const CategoriesIcon = LayoutList;
export const PluginsIcon = Plug;
export const WarehouseIcon = Warehouse;
export const FootprintIcon = Leaf;

const ICON_MAP: Record<string, LucideIcon> = {
  package: Package,
  "layout-list": LayoutList,
  plug: Plug,
  warehouse: Warehouse,
  footprint: Leaf,
  truck: Truck,
};

export function resolveIcon(name?: string): LucideIcon {
  if (!name) return Plug;

  if (ICON_MAP[name]) return ICON_MAP[name];

  // Convert kebab-case "arrow-right" to PascalCase "ArrowRight" for lucide lookup
  const pascalCase = name
    .split("-")
    .map((s) => s.charAt(0).toUpperCase() + s.slice(1))
    .join("");

  const icon = (icons as Record<string, unknown>)[pascalCase];
  if (typeof icon === "function") return icon as LucideIcon;

  return Plug;
}

export function PhotoPlaceholder({ size = 24 }: { size?: number }) {
  return <ImageIcon size={size} stroke="#94a3b8" strokeWidth={1.5} />;
}
