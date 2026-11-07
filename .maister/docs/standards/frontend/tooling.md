## Frontend Tooling

### TypeScript Strict Compiler Settings

All TypeScript projects (host app, host node config, plugins) compile with `strict: true` plus `noUnusedLocals`, `noUnusedParameters`, `noFallthroughCasesInSwitch` and `erasableSyntaxOnly`, with `moduleResolution: "bundler"`, target ES2023 and `noEmit` (Vite/Next emits). The host also enables `noUncheckedSideEffectImports`. New plugins copy the host `tsconfig.app.json` compilerOptions.
- `erasableSyntaxOnly` forbids enums, namespaces and constructor parameter properties — use union types and `as const` objects.
- `verbatimModuleSyntax` requires `import type` for type-only imports.

```typescript
import type { PluginObject } from "../../sdk";
type Status = "ACTIVE" | "INACTIVE"; // not an enum
```

Source: src/main/frontend/tsconfig.app.json, tsconfig.node.json, plugins/*/tsconfig.json

### ESLint Flat Config for Host Frontend

The host frontend uses ESLint 9 flat config on `**/*.{ts,tsx}`: js recommended, typescript-eslint recommended, react-hooks recommended (Rules of Hooks enforced) and react-refresh `vite`. Because of react-refresh, component files export only React components — move constants and helpers into separate modules. Run `npm run lint` before committing. Plugins have no ESLint setup and rely on tsc strictness.

Source: src/main/frontend/eslint.config.js, src/main/frontend/package.json

### Type-Check Gated Builds and Host Bundle Output

Every Vite build script runs `tsc -b` before `vite build`, so type errors fail the build. The host builds into `../resources/static` (`emptyOutDir: true`), then the plugin SDK is built as a separate IIFE bundle (`PluginSDK`) into `static/assets/plugin-sdk.js` (`emptyOutDir: false`) — keep `npm run build` ordered app then SDK. `src/main/resources/static/` is generated and gitignored: never edit or commit it.

```bash
"build": "tsc -b && vite build && vite build -c vite.sdk.config.ts"
```

Source: src/main/frontend/package.json, vite.config.ts, vite.sdk.config.ts, .gitignore, plugins/*/package.json
