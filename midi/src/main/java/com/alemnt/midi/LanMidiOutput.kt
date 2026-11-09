package com.alemnt.midi

import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class LanMidiOutput(
    private val host: String,
    private val code: String,
    private val onReady: () -> Unit,
    private val onClosed: (String) -> Unit,
    private val port: Int = 31459
) : Closeable {
    private val socket = Socket()
    private val closed = AtomicBoolean(false)
    private val frames = ArrayBlockingQueue<ByteArray>(256)
    @Volatile private var ready = false

    fun start() {
        Thread(::stream, "MIDI bridge").apply { isDaemon = true }.start()
    }

    fun send(bytes: ByteArray): Boolean {
        if (!ready || closed.get()) return false
        val frame = ByteArray(bytes.size + 1)
        frame[0] = bytes.size.toByte()
        bytes.copyInto(frame, 1)
        if (frames.offer(frame)) return true
        close()
        return false
    }

    private fun stream() {
        var failure = "Puente MIDI desconectado"
        try {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, port), 4000)
            socket.soTimeout = 4000
            val output = socket.getOutputStream()
            val input = socket.getInputStream()
            output.write("MPM1 $code\n".toByteArray(Charsets.US_ASCII))
            if (input.read() != 1) {
                failure = "Código incorrecto o puente no compatible"
                return
            }
            ready = true
            onReady()
            while (!closed.get()) {
                val frame = frames.poll(2, TimeUnit.SECONDS)
                if (!closed.get()) {
                    if (frame != null) {
                        output.write(frame)
                    } else {
                        output.write(0)
                        if (input.read() != 1) break
                    }
                }
            }
        } catch (_: IOException) {
            if (!ready) failure = "No se pudo conectar al puente en $host"
        } catch (_: IllegalArgumentException) {
            failure = "Dirección del puente no válida"
        } finally {
            close()
            onClosed(failure)
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            ready = false
            runCatching { socket.close() }
        }
    }

}
