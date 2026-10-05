// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.gpodder

import java.net.ConnectException
import java.net.InetAddress
import java.net.Socket
import java.net.SocketAddress
import javax.net.SocketFactory

/**
 * Hands OkHttp sockets that refuse every connect, as a closed port on a normal host would.
 *
 * Used instead of dialling a closed port (`localhost:1`, or a shut-down MockWebServer): under WSL2's
 * mirrored networking such a port is silently dropped rather than refused, the connect times out,
 * and a test asserting UNREACHABLE reports TIMED_OUT — the host deciding the result of a Tier 1 test
 * (CLAUDE.md §7). The refusal still travels OkHttp's real connect path as a `ConnectException`.
 */
internal object ConnectionRefusingSocketFactory : SocketFactory() {
    private class RefusingSocket : Socket() {
        override fun connect(
            endpoint: SocketAddress?,
            timeout: Int,
        ): Unit = throw ConnectException("Connection refused")
    }

    override fun createSocket(): Socket = RefusingSocket()

    override fun createSocket(
        host: String?,
        port: Int,
    ): Socket = RefusingSocket()

    override fun createSocket(
        host: String?,
        port: Int,
        localHost: InetAddress?,
        localPort: Int,
    ): Socket = RefusingSocket()

    override fun createSocket(
        host: InetAddress?,
        port: Int,
    ): Socket = RefusingSocket()

    override fun createSocket(
        address: InetAddress?,
        port: Int,
        localAddress: InetAddress?,
        localPort: Int,
    ): Socket = RefusingSocket()
}
