# Phase R03 — `/api/tv/**` + TV device pairing auth (FR-RV3)

## Problem
Ravilo authenticates against **jellystructure only** (control plane), and a TV has no good way to type
a password. We need a **pairing-code flow** that signs a device in as a Jellyfin user via
jellystructure, issues a device session, and brokers the data-plane token the device later uses to
stream from Jellyfin. Unlike the admin console, Ravilo must **allow non-admin** Jellyfin users.

## Current state (as-is)
- jellystructure auth is the Jellyseerr model: admin-only Jellyfin sign-in → opaque session cookie in
  SQLite (`session` table); machine token for background jobs; password never stored.
- All routes live under `/api/**`; there is no `/api/tv/**` namespace and no device concept.

## Requirements

### Namespace & guard
1. Add the **`/api/tv/**`** route namespace. Every route except the pairing endpoints requires a valid
   **device token** (`Authorization: Bearer <deviceToken>` or `X-Ravilo-Device`); missing/expired/
   revoked → **401** (the TV returns to pairing).
2. New SQLite (SQLDelight) table **`ravilo_device(device_id, user_id, jellyfin_user_id, token,
   display_name, is_admin, created, last_seen)`**. Tokens are opaque (`/dev/urandom`), like sessions.

### Pairing flow
3. `POST /api/tv/pair/start` → create a short, human-readable **code** (e.g. 6 chars) + a `pollToken`
   with a TTL (a few minutes); return `PairingChallenge`. The TV shows the code.
4. `POST /api/tv/pair/approve {code}` — called from an **already-signed-in jellystructure web/phone
   session** — binds the code to that Jellyfin user. (Approver may be any Jellyfin user signing in;
   admin gate does **not** apply.) Optionally accept Jellyfin credentials directly for a phone form.
5. `POST /api/tv/pair/poll {pollToken}` — the TV polls; once approved, returns a `TvSession` + a new
   **device token**, and persists a `ravilo_device` row. Codes/poll tokens are single-use and expire.
6. **Non-admin allowed:** `Policy.IsAdministrator == false` users pair successfully (record `is_admin`
   for later, but never block). This is the key divergence from the admin console.

### Data-plane brokering
7. On successful pairing (and refreshable later), jellystructure obtains/【holds server-side】 the
   Jellyfin **access token** for that user and exposes the **Jellyfin base URL** so playback/image
   phases can mint `StreamTicket`s. The **device** receives data-plane tokens only when needed (R08),
   scoped + short-lived; the Jellyfin **password is never** stored or sent to the device.
8. `POST /api/tv/pair/unpair` (or admin revoke) deletes the device row → its token 401s next call.

## Invariants
- The TV "logs into" **only jellystructure**; it never performs Jellyfin sign-in itself.
- **Non-admin Jellyfin users are allowed** (viewer app).
- Password never persisted/forwarded to the device; data-plane tokens are scoped + refreshable.
- Pairing codes & poll tokens are single-use and TTL-bound.

## Out of scope
- The on-device pairing **UI** (R15) — this is the backend + contract.
- Stream-ticket issuance details (R08) — here we only ensure the user's Jellyfin token is obtainable
  server-side.
