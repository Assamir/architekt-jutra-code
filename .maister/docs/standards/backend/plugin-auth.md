## Plugin Authentication

### Browser SDK Auth

Plugin iframes use `sdk.hostApp.getToken()` to obtain the logged-in user's JWT from the host via postMessage. The host's `PluginMessageHandler` handles the `getToken` message by reading from localStorage.

### Plugin Server-Side Auth

Plugin backends (e.g., Next.js API routes) receive the JWT from their own frontend and forward it to the host API via `createServerSDK`:

```typescript
// Plugin API route — pass the incoming request directly
const sdk = createServerSDK("my-plugin", undefined, req);
```

The SDK extracts the `Authorization` header from the request object automatically and calls the host at `HOST_BASE_URL` (default `http://localhost:8180`). The forwarded token is the user's login session token (`aud` = `${issuer}/api`). It stays valid whatever `Host` header the plugin backend sends, because the host checks `iss` against the configured `app.oauth2.issuer` (`APP_OAUTH2_ISSUER`), never against the request.

### Permission Checking in Plugins

Plugins should check user permissions before showing write UI. Decode the JWT from `hostApp.getToken()` to read the `permissions` claim:

```typescript
const token = await sdk.hostApp.getToken();
if (token) {
  const payload = JSON.parse(atob(token.split(".")[1]));
  const canEdit = payload.permissions?.includes("EDIT");
}
```
