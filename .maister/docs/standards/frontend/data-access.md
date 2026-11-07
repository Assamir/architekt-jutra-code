## Frontend Data Access

### API Module per Resource over Shared Fetch Client

Each `src/api/<resource>.ts` declares TypeScript interfaces mirroring backend records (`<X>Response`, `Create<X>Request`, `Update<X>Request`, `<X>SearchParams`) and exports plain async functions (`getProducts`, `createProduct`, ...) that call the shared `api` object from `api/client.ts`. The client prefixes `/api`, attaches the Bearer token from localStorage `auth_token`, redirects to `/login` on 401 and throws `ApiError(status, statusText, body)`. No data-fetching library (no react-query, no axios).

```typescript
export function getProducts(params?: ProductSearchParams): Promise<ProductResponse[]> { ... }
```

Source: src/main/frontend/src/api/client.ts, products.ts, categories.ts, plugins.ts, footprints.ts

### Data Hooks Return {data, loading, error, refetch, mutations}

`src/hooks/use<Resource>.ts` hooks wrap API functions with `useState`/`useEffect`/`useCallback` and return a typed `Use<X>Result` with data, `loading`, `error` (string), `refetch` and mutation functions (`create`/`update`/`remove`). Import API functions with `as apiX` aliases to avoid name clashes with the mutations.

```typescript
import { createProduct as apiCreateProduct } from "../api/products";
export function useProducts(params?: UseProductsParams): UseProductsResult { ... }
```

Source: src/main/frontend/src/hooks/useProducts.ts, useCategories.ts, useProductFootprint.ts

### Relative /api URLs via Vite Dev Proxy

The Vite dev server proxies `/api` to `http://localhost:8080` (`changeOrigin`), and production serves the SPA from the same Spring Boot origin. Frontend code always calls the backend with relative `/api/...` paths and never hardcodes a host or port.

```typescript
fetch("/api/products"); // not "http://localhost:8080/api/products"
```

Source: src/main/frontend/vite.config.ts, pom.xml (frontend-maven-plugin)
