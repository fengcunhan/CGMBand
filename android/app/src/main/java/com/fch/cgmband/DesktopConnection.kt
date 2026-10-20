package com.fch.cgmband

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executor

/**
 * One TCP connection carrying newline-delimited frames. A dedicated thread reads; [onLine] and
 * [onClosed] are delivered on [executor]. [open], [send] and [close] must be called on [executor].
 */
class DesktopConnection(
    private val executor: Executor,
    private val onLine: (DesktopConnection, String) -> Unit,
    private val onClosed: (DesktopConnection, String) -> Unit,
) {
    private var socket: Socket? = null
    private var output: OutputStream? = null

    @Volatile
    var lastReceivedAt = 0L
        private set

    var lastSentAt = 0L
        private set

    /** Tries each address in order; throws the last error when none accepts. */
    @Throws(IOException::class)
    fun open(hosts: List<InetAddress>, port: Int) {
        var lastError: IOException = IOException("no address")
        for (host in hosts) {
            try {
                open(host, port)
                return
            } catch (e: IOException) {
                lastError = IOException("${host.hostAddress}: ${e.message}", e)
            }
        }
        throw lastError
    }

    @Throws(IOException::class)
    private fun open(host: InetAddress, port: Int) {
        val s = Socket()
        s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        s.tcpNoDelay = true
        socket = s
        output = s.getOutputStream()
        lastReceivedAt = System.currentTimeMillis()
        Thread({ readLoop(s) }, "cgm-desktop-reader").apply { isDaemon = true }.start()
    }

    @Throws(IOException::class)
    fun send(frame: String) {
        val out = output ?: throw IOException("not connected")
        out.write((frame + "\n").toByteArray(Charsets.UTF_8))
        out.flush()
        lastSentAt = System.currentTimeMillis()
    }

    fun close() {
        runCatching { socket?.close() }
        socket = null
        output = null
    }

    private fun readLoop(s: Socket) {
        var reason = "closed"
        try {
            val input = BufferedInputStream(s.getInputStream())
            val line = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b < 0) break
                if (b == '\n'.code) {
                    val text = line.toString(Charsets.UTF_8.name())
                    line.reset()
                    lastReceivedAt = System.currentTimeMillis()
                    executor.execute { onLine(this, text) }
                } else {
                    line.write(b)
                    if (line.size() > MAX_FRAME_BYTES) throw IOException("frame too large")
                }
            }
        } catch (e: IOException) {
            reason = e.message ?: "io"
        } finally {
            runCatching { s.close() }
            executor.execute { onClosed(this, reason) }
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 5_000
        const val MAX_FRAME_BYTES = 64 * 1024
    }
}
