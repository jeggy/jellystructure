package dev.jellystructure.support

import dev.jellystructure.config.ConfigStore
import dev.jellystructure.jobs.JobEvent
import dev.jellystructure.tv.TvEventBus

/**
 * Phase 320 (FR-320-1, FR-320-10) — the two pushes and the one config hook, in one place so `Main` and the tests wire the
 * same thing:
 *  - a change a viewer can see → `support_changed` to that viewer's opted-in sockets only;
 *  - a change the admin should see → the `support_ticket` event on `/ws` ([adminBroadcast]);
 *  - the lane becoming available or unavailable (the AI key saved or removed, or `[support] enabled` flipped) → the
 *    `config_changed` Ravilo already re-fetches on, to every device, so the entry appears or goes without a restart.
 */
fun wireSupport(service: SupportService, configStore: ConfigStore, bus: TvEventBus, adminBroadcast: (JobEvent) -> Unit) {
    service.pushViewer = { userId, ticket -> bus.notifySupportChanged(userId, ticket) }
    service.pushAdmin = adminBroadcast
    configStore.onSupportAvailabilityChanged = { bus.notifyGlobalConfigChanged() }
}
