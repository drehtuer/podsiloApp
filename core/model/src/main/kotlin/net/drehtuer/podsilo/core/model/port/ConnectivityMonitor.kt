// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.model.port

import kotlinx.coroutines.flow.Flow

/**
 * Port for "is there a network right now", implemented over `ConnectivityManager` in an Android
 * module (`UI.adoc` §12.10).
 *
 * The point is that connectivity is checked **before** a request is started, never inferred from a
 * timeout afterwards: a pull-to-refresh with no network returns immediately with a banner instead
 * of spinning for thirty seconds against each feed in turn. Being offline is a *precondition*, not
 * a failure — it is deliberately **not** written to [LogRepository].
 *
 * This does not gate downloads. A download requested while offline is accepted and left `QUEUED`
 * with a wait reason; WorkManager's own network constraint releases it later. Podsilo never
 * refuses a decision because of a condition that will fix itself.
 */
fun interface ConnectivityMonitor {
    fun observe(): Flow<Connectivity>
}

/**
 * @property metered Drives the *Download over mobile data* constraint (off by default), which is a
 *   WorkManager `NetworkType` and a UI wait reason ("waiting for Wi-Fi") — not a download rule.
 */
data class Connectivity(
    val online: Boolean,
    val metered: Boolean,
)
