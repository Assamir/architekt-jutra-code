import { render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter } from "react-router-dom";
import { ChakraProvider } from "@chakra-ui/react";
import { system } from "../theme";
import { useAuth } from "../auth/AuthContext";
import { OAuth2AuthorizePage } from "../pages/OAuth2AuthorizePage";

const mockFetch = vi.fn();
vi.stubGlobal("fetch", mockFetch);

vi.mock("../auth/AuthContext", () => ({
  useAuth: vi.fn(),
}));

const BASE_QUERY =
  "client_id=claude-client&redirect_uri=http%3A%2F%2Flocalhost%3A6274%2Fcallback&response_type=code" +
  "&state=xyz&code_challenge=abc&code_challenge_method=S256";

function renderWithProviders(ui: React.ReactElement, initialRoute = "/") {
  return render(
    <ChakraProvider value={system}>
      <MemoryRouter initialEntries={[initialRoute]}>{ui}</MemoryRouter>
    </ChakraProvider>,
  );
}

function mockPermissions(permissions: string[]) {
  vi.mocked(useAuth).mockReturnValue({
    token: "t0",
    username: "user",
    permissions,
    login: vi.fn(),
    logout: vi.fn(),
  });
}

beforeEach(() => {
  vi.resetAllMocks();
  mockFetch.mockResolvedValue({
    ok: true,
    json: () => Promise.resolve({ client_name: "Claude", scopes: ["mcp:read", "mcp:edit"] }),
  });
  mockPermissions(["READ", "EDIT"]);
});

describe("OAuth2AuthorizePage", () => {
  it("renders hidden resource input when resource query param is present", async () => {
    const resource = "http://localhost:8181";
    const { container } = renderWithProviders(
      <OAuth2AuthorizePage />,
      `/oauth2/authorize?${BASE_QUERY}&scope=mcp%3Aread&resource=${encodeURIComponent(resource)}`,
    );

    await screen.findByRole("heading", { name: "Claude" });

    const input = container.querySelector<HTMLInputElement>('input[type="hidden"][name="resource"]');
    expect(input).toBeInTheDocument();
    expect(input?.value).toBe(resource);
  });

  it("omits resource input when resource is absent", async () => {
    const { container } = renderWithProviders(
      <OAuth2AuthorizePage />,
      `/oauth2/authorize?${BASE_QUERY}&scope=mcp%3Aread`,
    );

    await screen.findByRole("heading", { name: "Claude" });

    expect(container.querySelector('input[name="resource"]')).toBeNull();
  });

  it("offers only mcp:read to a viewer", async () => {
    mockPermissions(["READ"]);

    renderWithProviders(
      <OAuth2AuthorizePage />,
      `/oauth2/authorize?${BASE_QUERY}&scope=mcp%3Aread%20mcp%3Aedit`,
    );

    expect(await screen.findByText("Read data")).toBeInTheDocument();
    expect(screen.queryByText("Edit data")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Allow" })).toBeEnabled();
  });

  it("offers mcp:read and mcp:edit to an editor", async () => {
    mockPermissions(["READ", "EDIT"]);

    renderWithProviders(
      <OAuth2AuthorizePage />,
      `/oauth2/authorize?${BASE_QUERY}&scope=mcp%3Aedit`,
    );

    expect(await screen.findByText("Read data")).toBeInTheDocument();
    expect(screen.getByText("Edit data")).toBeInTheDocument();
  });
});
