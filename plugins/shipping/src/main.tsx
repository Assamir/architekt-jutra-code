import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter, Routes, Route } from "react-router-dom";
import { ProductShippingTab } from "./pages/ProductShippingTab";
import { ShippingConfigPage } from "./pages/ShippingConfigPage";

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<ShippingConfigPage />} />
        <Route path="/product-shipping" element={<ProductShippingTab />} />
      </Routes>
    </BrowserRouter>
  </StrictMode>,
);
