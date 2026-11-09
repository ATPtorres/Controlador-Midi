package com.alemnt.midi

import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LanMidiOutputTest {
    @Test
    fun authenticatesAndFramesMidiWithoutLosingMessageOrder() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            server.soTimeout = 4000
            val ready = CountDownLatch(1)
            LanMidiOutput(server.inetAddress.hostAddress!!, "123456", { ready.countDown() }, {}, server.localPort).use { output ->
                output.start()
                server.accept().use { socket ->
                    socket.soTimeout = 4000
                    val input = socket.getInputStream()
                    val handshake = "MPM1 123456\n".toByteArray()
                    assertArrayEquals(handshake, input.readNBytes(handshake.size))
                    socket.getOutputStream().write(1)
                    assertTrue(ready.await(2, TimeUnit.SECONDS))
                    val noteOn = byteArrayOf(0x90.toByte(), 60, 90)
                    val noteOff = byteArrayOf(0x80.toByte(), 60, 0)
                    assertTrue(output.send(noteOn))
                    assertTrue(output.send(noteOff))
                    assertEquals(3, input.read())
                    assertArrayEquals(noteOn, input.readNBytes(3))
                    assertEquals(3, input.read())
                    assertArrayEquals(noteOff, input.readNBytes(3))
                    assertEquals(0, input.read())
                    socket.getOutputStream().write(1)
                    assertTrue(output.send(noteOn))
                    assertEquals(3, input.read())
                    assertArrayEquals(noteOn, input.readNBytes(3))
                }
            }
        }
    }
}
