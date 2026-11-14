import { describe, expect, it, vi, beforeEach, afterEach } from "vitest";

describe("Plugin SDK", () => {
  beforeEach(() => {
    vi.resetAllMocks();
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  describe("context parsing from window.name", () => {
    it("extracts extensionPoint prefix and JSON metadata", async () => {
      const { parseContext } = await import("../plugin-sdk/context");

      const windowName = 'RENDER{"pluginId":"warehouse","pluginName":"Warehouse","hostOrigin":"http://localhost:8180"}';

      const ctx = parseContext(windowName);

      expect(ctx.extensionPoint).toBe("RENDER");
      expect(ctx.pluginId).toBe("warehouse");
      expect(ctx.pluginName).toBe("Warehouse");
      expect(ctx.hostOrigin).toBe("http://localhost:8180");
      expect(ctx.productId).toBeUndefined();
    });

    it("extracts productId from PRODUCT_DETAIL context", async () => {
      const { parseContext } = await import("../plugin-sdk/context");

      const windowName = 'PRODUCT_DETAIL{"pluginId":"warehouse","pluginName":"Warehouse","hostOrigin":"http://localhost:8180","productId":"prod-123"}';

      const ctx = parseContext(windowName);

      expect(ctx.extensionPoint).toBe("PRODUCT_DETAIL");
      expect(ctx.productId).toBe("prod-123");
    });
  });

  describe("sendMessageAndWait", () => {
    it("correlates response by requestId", async () => {
      const { sendMessageAndWait, handleResponse } = await import("../plugin-sdk/messaging");

      const mockPostMessage = vi.fn();
      vi.stubGlobal("parent", { postMessage: mockPostMessage });

      const resultPromise = sendMessageAndWait("pluginFetch", { url: "/api/products" }, "http://localhost:8180");

      // Extract the requestId from the posted message
      expect(mockPostMessage).toHaveBeenCalledOnce();
      const postedMessage = mockPostMessage.mock.calls[0][0];
      expect(postedMessage.requestId).toMatch(/^aj\.plugin\./);
      expect(postedMessage.type).toBe("pluginFetch");

      // Simulate host responding with matching responseId
      handleResponse({ responseId: postedMessage.requestId, payload: { data: "test" } });

      const result = await resultPromise;
      expect(result).toEqual({ data: "test" });

      vi.unstubAllGlobals();
    });

    it("times out after 10 seconds", async () => {
      const { sendMessageAndWait } = await import("../plugin-sdk/messaging");

      const mockPostMessage = vi.fn();
      vi.stubGlobal("parent", { postMessage: mockPostMessage });

      const resultPromise = sendMessageAndWait("pluginFetch", { url: "/api/products" }, "http://localhost:8180");

      // Advance timers past the 10s timeout
      vi.advanceTimersByTime(10_001);

      await expect(resultPromise).rejects.toThrow("timeout");

      vi.unstubAllGlobals();
    });
  });

  describe("hostApp.fetch", () => {
    it("strips credentials and mode from options, sends pluginFetch message", async () => {
      const { hostApp } = await import("../plugin-sdk/host-app");

      // Set up host origin
      (window as any).__pluginHostOrigin = "http://localhost:8180";

      const mockPostMessage = vi.fn();
      vi.stubGlobal("parent", { postMessage: mockPostMessage });

      // hostApp.fetch should call sendMessageAndWait with pluginFetch type
      // It passes url and options but NOT credentials/mode
      void hostApp.fetch("/api/products", {
        method: "GET",
        headers: { "Content-Type": "application/json" },
      });

      expect(mockPostMessage).toHaveBeenCalledOnce();
      const postedMessage = mockPostMessage.mock.calls[0][0];
      expect(postedMessage.type).toBe("pluginFetch");
      expect(postedMessage.payload.url).toBe("/api/products");
      expect(postedMessage.payload.method).toBe("GET");
      // credentials and mode should NOT be present in the payload
      expect(postedMessage.payload.credentials).toBeUndefined();
      expect(postedMessage.payload.mode).toBeUndefined();

      vi.unstubAllGlobals();
      delete (window as any).__pluginHostOrigin;
    });
  });

  describe("thisPlugin.getContext", () => {
    it("returns parsed context with productId from window.name", async () => {
      // Reset the cached context by re-importing fresh module
      vi.resetModules();

      const windowName = 'PRODUCT_DETAIL{"pluginId":"warehouse","pluginName":"Warehouse","hostOrigin":"http://localhost:8180","productId":"prod-456"}';
      Object.defineProperty(window, "name", { value: windowName, writable: true });

      const { thisPlugin } = await import("../plugin-sdk/this-plugin");

      const ctx = thisPlugin.getContext();

      expect(ctx.extensionPoint).toBe("PRODUCT_DETAIL");
      expect(ctx.pluginId).toBe("warehouse");
      expect(ctx.pluginName).toBe("Warehouse");
      expect(ctx.hostOrigin).toBe("http://localhost:8180");
      expect(ctx.productId).toBe("prod-456");

      Object.defineProperty(window, "name", { value: "", writable: true });
    });
  });

  describe("sendFireAndForget", () => {
    it("posts message without creating pending promise", async () => {
      const { sendFireAndForget, getPendingCount } = await import("../plugin-sdk/messaging");

      const mockPostMessage = vi.fn();
      vi.stubGlobal("parent", { postMessage: mockPostMessage });

      const countBefore = getPendingCount();
      sendFireAndForget("filterChange", { stock: true }, "http://localhost:8180");

      expect(mockPostMessage).toHaveBeenCalledOnce();
      const postedMessage = mockPostMessage.mock.calls[0][0];
      expect(postedMessage.requestId).toMatch(/^aj\.plugin\./);
      expect(postedMessage.type).toBe("filterChange");

      // No pending promise was added
      expect(getPendingCount()).toBe(countBefore);

      vi.unstubAllGlobals();
    });
  });
});
