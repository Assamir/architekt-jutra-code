# Connecting AI Assistants to aj Safely: MCP Sign-in Guide

*Last updated: 2026-11-14. Applies to the MCP authentication refactor (course module 4).*

## TL;DR
- The MCP server no longer passes your login token on to aj. It checks the token and trades it for a short-lived token of its own (5 minutes at most) that only the aj API accepts.
- New addresses: the aj app is at **http://localhost:8180** and the MCP server at **http://localhost:8181** (management port 9181). Point Claude Code or MCP Inspector at `http://localhost:8181`.
- `start-dev.sh` / `start-dev.ps1` sets everything up for you. It keeps a shared secret in `.aj-dev.env`, a file git ignores.
- After you upgrade you will have to log in again once, and every MCP client has to be authorized again.
- Viewers can only grant "Read data". If a viewer calls `aj_add_product`, it fails with "Insufficient scope: requires mcp:edit".

---

## Contents
1. [What changed and why](#1-what-changed-and-why)
2. [New addresses (ports)](#2-new-addresses-ports)
3. [Starting aj on your computer](#3-starting-aj-on-your-computer)
4. [Connecting an MCP client](#4-connecting-an-mcp-client)
5. [What you will notice after upgrading](#5-what-you-will-notice-after-upgrading)
6. [Troubleshooting](#6-troubleshooting)
7. [Quick reference](#7-quick-reference)
8. [Notes about this guide](#8-notes-about-this-guide)

---

## Who this guide is for

- **Course participants and developers** who run aj on their own computer and connect an AI assistant (Claude Code, MCP Inspector) to it.
- **aj users** who sign in to the aj web app and approve AI assistants to work with their products and categories.

You don't need to know OAuth to follow it. A few terms are explained along the way.

---

## 1. What changed and why

### The short version

When an AI assistant uses aj tools, three parties are involved:

- **The AI assistant**, also called the **MCP client** (for example Claude Code)
- **The MCP server**, a small helper app that offers the aj tools to the assistant
- **The aj app** (the "host"), which stores your products and categories

**Before:** after you signed in, the assistant got a pass (a *token*). The MCP server **handed that same pass straight to aj**. Anyone who copied the pass from the assistant could use it against aj directly.

**Now:** the MCP server never passes your token on. On every tool call it:

1. **Checks** the assistant's token with aj. It asks: "Is this token real, still valid, and made for *me*?"
2. **Trades** it for a fresh token that **only the aj API accepts** and that **expires within 5 minutes**.
3. Calls aj with that new token only.

### Why this is safer

| Risk | What protects you now |
|---|---|
| A stolen assistant token is replayed directly against aj | Every token says who it is for. aj rejects tokens issued to the MCP server. |
| A token meant for another app is accepted by the MCP server | The MCP server only accepts tokens issued for its own address. |
| An assistant gets more rights than you have | Rights are cut down to what you actually hold: when you approve, and again on every tool call. |
| A leaked short-lived token stays useful for a long time | The token the MCP server uses at aj lasts 5 minutes at most. |
| Tokens and secrets end up in log files | Neither app writes tokens, codes or secrets to its logs. |

**Tip:** none of this changes what the tools do. `aj_list_products`, `aj_add_product` and `aj_list_categories` have the same names, inputs and results as before.

---

## 2. New addresses (ports)

All local addresses moved to a new range, so they don't collide with other apps that often use 8080/8081.

| What | Old address | New address |
|---|---|---|
| aj web app and API (host) | http://localhost:8080 | **http://localhost:8180** |
| MCP server (point AI assistants here) | http://localhost:8081 | **http://localhost:8181** |
| MCP server management (health checks) | port 9081 | **port 9181** |

Plugin ports (3001, 3002, 3003, 3010, 3011) did not change.

**Important:** update every saved MCP client setup that still points at the old MCP address. Change it to `http://localhost:8181` (see [Section 4](#4-connecting-an-mcp-client)).

**Note:** to approve an assistant, use the aj app served at **http://localhost:8180**. The frontend development server (`npm run dev`) does not pass the sign-in approval pages through.

---

## 3. Starting aj on your computer

**What you need:**
- JDK 25, Docker and Node.js (as before)
- A terminal open in the repository folder

### Option A: the start script (recommended)

Run the script for your system.

- **macOS / Linux / Git Bash:**
  ```bash
  ./start-dev.sh
  ```
- **Windows PowerShell:**
  ```powershell
  ./start-dev.ps1
  ```

The script does the following for you:

1. Starts the database and the other Docker services.
2. Prints the two addresses it will use, for example:
   ```
   OAuth issuer: http://localhost:8180
   MCP base URL: http://localhost:8181
   ```
3. Sets up the **shared secret** that the aj app and the MCP server use to recognise each other (explained below).
4. Starts the aj app on port 8180, the MCP server on port 8181, and all plugins.

Press `Ctrl+C` to stop everything.

**What you should see:** after a minute or two, http://localhost:8180 shows the aj login page.

### The shared secret and the `.aj-dev.env` file

The MCP server has to prove to the aj app that it really is the MCP server. It does that with a secret value called `AJ_OAUTH_CLIENT_SECRET`. **Both apps must use exactly the same value.**

You don't have to create it yourself:

- **First run:** the script creates a random secret and saves it in **`.aj-dev.env`** in the repository's root folder. It prints `Generated AJ_OAUTH_CLIENT_SECRET and stored it in .aj-dev.env (gitignored).` The secret itself is never printed.
- **Later runs:** the script reuses the secret from `.aj-dev.env`. You can therefore restart just one of the apps and the two still match.
- **Your own value wins:** if `AJ_OAUTH_CLIENT_SECRET` is already set in your terminal, the script uses it instead.
- **To get a new secret (rotate it):** delete `.aj-dev.env`, run the script again, and make sure **both** apps restart.
- **Plugins never see it:** the secret goes only to the aj app and the MCP server.

**Warning:** `.aj-dev.env` is listed in `.gitignore`. Never commit it and never share it.

### What about `APP_JWT_SECRET`?

`APP_JWT_SECRET` signs your aj login tokens. The start script **does not generate it**:

- If you have already set `APP_JWT_SECRET` in your terminal, the aj app uses it.
- If you haven't, the aj app uses its built-in development default. That keeps your web login working across restarts.

Nothing changes for you unless you set it on purpose.

### Option B: starting the apps by hand

Use this when you want to run each app in its own terminal.

1. **Start the database:**
   ```bash
   docker compose up -d
   ```

2. **Set the same secret in every terminal that starts the aj app or the MCP server.** If `.aj-dev.env` exists, reuse the value saved there so both apps keep matching. Otherwise pick any random value.
   ```bash
   # bash / Git Bash
   export AJ_OAUTH_CLIENT_SECRET=<value of AJ_OAUTH_CLIENT_SECRET in .aj-dev.env>
   ```
   ```powershell
   # Windows PowerShell
   $env:AJ_OAUTH_CLIENT_SECRET = "<value of AJ_OAUTH_CLIENT_SECRET in .aj-dev.env>"
   ```

3. **Start the aj app** (http://localhost:8180):
   ```bash
   ./mvnw spring-boot:run
   ```

4. **Start the MCP server in a second terminal** (http://localhost:8181), after setting the same secret there:
   ```bash
   cd mcp-server && ./mvnw spring-boot:run
   ```

5. **Optionally start a plugin** in another terminal with `npm run dev` in its folder.

**Warning:** if the two terminals use **different** secrets, nothing obvious happens at start-up. Every request from an AI assistant then fails. See [Troubleshooting](#61-the-secrets-dont-match).

**Note:** if `AJ_OAUTH_CLIENT_SECRET` is not set at all:
- The **MCP server refuses to start** and says `aj.oauth.client-secret is not set — export AJ_OAUTH_CLIENT_SECRET (start-dev does this)`.
- The **aj app still starts**, but it does not register the MCP server. MCP requests are then rejected with `invalid_client`.

---

## 4. Connecting an MCP client

This section walks through connecting an AI assistant and using the aj tools.

### What you'll need
- aj and the MCP server running (see [Section 3](#3-starting-aj-on-your-computer))
- An aj account. The development data includes three accounts:

| Account | Permissions in aj | What you can approve for an assistant |
|---|---|---|
| `viewer` | Read | **Read data** only |
| `editor` | Read + Edit | **Read data** and **Edit data** |
| `admin` | Read + Edit (+ admin rights) | **Read data** and **Edit data** |

### Step 1: Add the MCP server to your assistant

Give your assistant the MCP server address: **`http://localhost:8181`** (HTTP transport).

- **Claude Code:** add an HTTP MCP server with the URL `http://localhost:8181`, for example:
  ```bash
  claude mcp add --transport http aj http://localhost:8181
  ```
  If you already have an `aj` entry that points at the old address, remove it or edit it first.
- **MCP Inspector:** choose the *Streamable HTTP* transport and enter `http://localhost:8181` as the URL.

**What happens next:** the assistant contacts the MCP server, which tells it where to sign in. The assistant then opens your browser at the aj app. This automatic discovery needs no setup from you.

### Step 2: Log in to aj

If you aren't logged in yet, the browser shows the aj **login page** at http://localhost:8180. Sign in with your aj account.

**Tip:** if you were logged in before the upgrade, you will be asked to log in again once (see [Section 5](#5-what-you-will-notice-after-upgrading)).

### Step 3: Approve access on the consent page

Next you see a **consent page**. It shows:

- The assistant's name, with the text **"wants to access your account"**
- A list titled **"Permissions requested:"**
- **Cancel** and **Allow** buttons
- A reminder to only approve applications you trust

What the list offers depends on your account:

| You are logged in as | Options shown |
|---|---|
| **Viewer** | **Read data** ("View and access your data (always required)"). This option is already ticked and can't be unticked. |
| **Editor** or **Admin** | **Read data** (always ticked) and **Edit data** ("Create, modify, and delete your data"). |

**Important (editors):** **Edit data is not ticked by default.** If you want the assistant to be able to add products, click **Edit data** to tick it before you click **Allow**.

Then:
- Click **Allow** to approve. The browser returns to your assistant, which can now use the tools.
- Click **Cancel** to refuse. The assistant gets "access denied" and can do nothing.

**Note:** the page only offers what you can actually grant. Even if the assistant asks for more, aj itself also checks and never grants more than your account allows.

**If the page shows "You do not have permission to grant the requested access":** your account can't grant anything the assistant asked for. The **Allow** button stays disabled. Ask an administrator for the permission you need.

### Step 4: Use the aj tools

Once you've approved access, ask your assistant to work with aj. Three tools are available:

| Tool | What it does | Permission needed |
|---|---|---|
| `aj_list_products` | Lists products, optionally filtered by a search term | Read data |
| `aj_list_categories` | Lists all categories | Read data |
| `aj_add_product` | Creates a new product | **Edit data** |

Example requests you could type in Claude Code:
- "List all aj products that contain *chair*."
- "Show me the aj categories."
- "Add a product called *Oak Desk Lamp* to the *Lighting* category, priced at 49.99."

**What you should see:** the assistant shows the results exactly as before the upgrade.

### What happens when you lack a permission

| Situation | What the assistant reports |
|---|---|
| A viewer (or an editor who didn't tick **Edit data**) calls `aj_add_product` | Tool error **"Insufficient scope: requires mcp:edit"**. Nothing is created. |
| Your Edit permission was removed in aj **after** you approved | The next tool call fails with **"Insufficient permissions"**. Permissions are checked again on every call. |

**Tip:** to give an assistant more rights later, authorize it again (sign out or remove the server in the assistant, then reconnect) and tick **Edit data** this time.

---

## 5. What you will notice after upgrading

### You have to log in to the aj web app once more

Old aj login tokens don't say which service they are meant for (they have no *audience*). The new version doesn't accept them:
- When you open http://localhost:8180, the app quietly discards the old token and shows the **login page**.
- Log in once and everything works as usual.

### Every MCP client has to be authorized again

Tokens that assistants received before the upgrade no longer work. The MCP server has also moved to a new address. To fix both:

1. Change the assistant's MCP server address to **`http://localhost:8181`**.
2. Connect again and go through **login → consent → Allow** once (see [Section 4](#4-connecting-an-mcp-client)).

### Things that do NOT change
- Tool names, inputs and results
- Your aj accounts and their permissions
- Plugin addresses and how plugins work

---

## 6. Troubleshooting

### 6.1 The secrets don't match

**Symptoms**
- The MCP client shows a **server error (5xx)** as soon as it connects, or every tool call fails with **"Backend authorization failed"**.
- The **aj app's log** shows rejected requests from the MCP server like this:
  ```
  client_id=aj-mcp-server | error=invalid_client
  ```
  (The log contains the client name only, never the secret.)

**Why it happens:** the aj app and the MCP server were started with different `AJ_OAUTH_CLIENT_SECRET` values. Usually one was started by hand without the value from `.aj-dev.env`.

**How to fix it**
1. Stop both apps.
2. Do one of the following:
   - Start everything with `./start-dev.sh` / `./start-dev.ps1`, **or**
   - In **both** terminals, set `AJ_OAUTH_CLIENT_SECRET` to the value from `.aj-dev.env`, then start each app ([Option B](#option-b-starting-the-apps-by-hand)).
3. If you deleted `.aj-dev.env` to get a new secret, make sure **both** apps were restarted afterwards.

### 6.2 The MCP server won't start

**Symptom:** the MCP server stops at start-up with `aj.oauth.client-secret is not set — export AJ_OAUTH_CLIENT_SECRET (start-dev does this)`.

**Fix:** set `AJ_OAUTH_CLIENT_SECRET` in that terminal (same value as the aj app), or use the start script.

### 6.3 The assistant can't connect at all

**Symptoms:** "connection refused", a timeout, or the assistant talks to the wrong service.

**Check:**
- Is the assistant pointed at **`http://localhost:8181`**? Old setups often still use the previous MCP address.
- Are both apps running? Open http://localhost:8180 in the browser. You should see aj.
- Did you log in at **http://localhost:8180**, and not at the frontend development server?

### 6.4 The consent page or a sign-in step shows an error

Sign-in steps report problems with a short error code. Common ones:

| Error | Usual meaning | What to do |
|---|---|---|
| `access_denied` "User must be authenticated" | You weren't logged in to the aj app, or your login expired | Log in at http://localhost:8180 and try again |
| `invalid_scope` | You (or the assistant) asked only for permissions your account doesn't have | Approve only what your account allows, or ask for the permission |
| `invalid_target` | The assistant asked for access to an address that isn't the MCP server | Check the assistant's MCP URL is exactly `http://localhost:8181` |
| `invalid_client` | The MCP server's secret doesn't match (see 6.1), or the assistant's saved registration no longer exists | Fix the secret; or remove and re-add the MCP server in the assistant |

### 6.5 "Server error" from a sign-in step: getting more detail

When something unexpected goes wrong in sign-in, the aj app logs only a short error type, without details. Details could contain request data, so they are hidden by default.

To see the full details while you investigate, start the aj app with extra logging turned on:

```bash
# bash / Git Bash
LOGGING_LEVEL_PL_DEVSTYLE_AJ_CORE_OAUTH2=DEBUG ./mvnw spring-boot:run
```
```powershell
# Windows PowerShell
$env:LOGGING_LEVEL_PL_DEVSTYLE_AJ_CORE_OAUTH2 = "DEBUG"; ./mvnw spring-boot:run
```

(This is the same as setting `logging.level.pl.devstyle.aj.core.oauth2=DEBUG`.)

**Warning:** turn it off again when you're done. Remember to set the shared `AJ_OAUTH_CLIENT_SECRET` in that terminal too.

### 6.6 "Insufficient scope" or "Insufficient permissions"

This is expected behaviour, not a fault. See [What happens when you lack a permission](#what-happens-when-you-lack-a-permission).

### 6.7 I keep being sent back to the login page

If this happens right after the upgrade, it's the one-time re-login described in [Section 5](#5-what-you-will-notice-after-upgrading). If it keeps happening, check whether `APP_JWT_SECRET` changes between restarts. A new value signs you out each time.

---

## 7. Quick reference

| Item | Value |
|---|---|
| aj web app / API | http://localhost:8180 |
| MCP server (for AI assistants) | http://localhost:8181 |
| MCP management port | 9181 |
| Shared secret | `AJ_OAUTH_CLIENT_SECRET`, same value in both apps |
| Where `start-dev` keeps the secret | `.aj-dev.env` in the repository root (gitignored; delete to rotate) |
| `APP_JWT_SECRET` | used if you set it; never generated by the script |
| Permissions an assistant can get | **Read data** (`mcp:read`), **Edit data** (`mcp:edit`) |
| Viewer can grant | Read data only |
| Editor / admin can grant | Read data + Edit data (tick "Edit data" yourself) |
| Max lifetime of the token the MCP server uses at aj | 5 minutes |
| Mismatch log line (aj app) | `client_id=aj-mcp-server \| error=invalid_client` |
| Extra sign-in diagnostics | `LOGGING_LEVEL_PL_DEVSTYLE_AJ_CORE_OAUTH2=DEBUG` |

### Related documentation
- Root `README.md`: sections "start-dev", "Manual Start" and "Troubleshooting"
- `CLAUDE.md`: architecture overview of the host, its sign-in server and the MCP server
- `plugins/CLAUDE.md`: plugin development (now uses port 8180)
- `.maister/docs/standards/mcp-server/conventions.md`: the "No Token Passthrough" rules, for developers

---

## 8. Notes about this guide

- **No screenshots.** When this guide was written, the aj app (http://localhost:8180) and the MCP server (http://localhost:8181) were not running. Screens are described in words, using the text exactly as it appears in the app.
- **Not yet checked with a real assistant.** All automated tests pass, but no one has yet run the full flow end to end with Claude Code or MCP Inspector. If your assistant behaves differently from what this guide describes (for example a different error on the consent step), please report it.
- **Local use only.** This setup is meant for running aj on your own computer. It is not hardened for a public server.
