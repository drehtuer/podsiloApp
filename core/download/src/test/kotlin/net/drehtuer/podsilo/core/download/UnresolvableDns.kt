// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.download

import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * An [OkHttpClient] on which no host name resolves — a dead or mistyped host, without a DNS query.
 *
 * Resolving a real `.invalid` name instead asks whatever resolver the host has, which is neither
 * offline nor deterministic (CLAUDE.md §7): a slow resolver makes the test slow, a hijacking one
 * makes it pass for the wrong reason.
 */
internal fun unresolvableHttpClient(): OkHttpClient =
    OkHttpClient
        .Builder()
        .dns(
            object : Dns {
                override fun lookup(hostname: String): List<InetAddress> =
                    throw UnknownHostException("$hostname: no such host (test DNS)")
            },
        ).build()
