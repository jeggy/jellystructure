package dev.jellystructure.shared.tv

/**
 * R293 (FR-R293-7) — where the device token goes on a WebSocket handshake. Only a browser cannot set a
 * handshake header, so only the browser targets put the token in the URL a proxy logs; every other build
 * sends `Authorization: Bearer`, which the server has accepted on both socket routes since Phase 236.
 * An `expect val`, not a runtime flag, so `scripts/check-events-query-token.sh` can fence it per source set.
 */
internal expect val WS_TOKEN_IN_QUERY: Boolean
