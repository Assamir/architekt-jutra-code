## Frontend Testing

### Vitest as Test Runner

Use Vitest with `globals: true` (describe/it/expect available without imports). Use jsdom as the test environment for browser-like DOM APIs.

```typescript
// vitest.config.ts
export default defineConfig({
  plugins: [react()],
  test: {
    globals: true,
    environment: "jsdom",
    setupFiles: ["./src/test/setup.ts"],
  },
});
```

### Testing Library for Components

Use `@testing-library/react` for rendering and querying components. Use `@testing-library/jest-dom` for extended DOM matchers. Import jest-dom in the setup file:

```typescript
// src/test/setup.ts
import "@testing-library/jest-dom/vitest";
```

### renderWithProviders Helper

Define a `renderWithProviders()` helper per test file that wraps components in required providers (ChakraProvider, MemoryRouter). Keep helpers per-file, not shared globally.

```typescript
function renderWithProviders(ui: React.ReactElement, initialRoute = "/") {
  return render(
    <ChakraProvider value={system}>
      <MemoryRouter initialEntries={[initialRoute]}>{ui}</MemoryRouter>
    </ChakraProvider>,
  );
}
```

### API Module Mocking

Mock API modules at the module level using `vi.mock()` with factory functions. Reset mocks between tests with `vi.resetAllMocks()` in `beforeEach`. Configure return values per test or in `beforeEach`.

```typescript
vi.mock("../api/categories", () => ({
  getCategories: vi.fn(),
}));

beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(categoriesApi.getCategories).mockResolvedValue(mockCategories);
});
```

### Test Organization

Use `describe()` blocks named after the page or feature being tested. Place test files in `src/test/` directory.

### Query Chakra asChild Links by Selector

Under jsdom, `getByRole("link")` does not reliably find Chakra `asChild`-wrapped anchors. Query them with `container.querySelector('a[href=...]')` instead.

```typescript
expect(container.querySelector('a[href="/carbon-footprint"]')).toBeInTheDocument();
```

Source: .maister/tasks/development/2026-05-20-footprint-calc-engine-ui/implementation/work-log.md
