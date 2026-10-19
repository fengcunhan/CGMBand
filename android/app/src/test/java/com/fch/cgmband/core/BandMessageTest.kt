package com.fch.cgmband.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BandMessageTest {

    private val sample = GlucoseReading(
        value = "6.6",
        unit = "mmol/L",
        mmol = 6.6,
        time = "16:33",
        readingAt = 1_792_398_000_000L,
        postedAt = 1_792_398_021_000L,
        connected = true,
    )

    @Test
    fun `encodes a reading using the shared band protocol`() {
        val json = BandMessage.encodeReading(sample, Trend.FLAT, 0.1)

        assertEquals(
            """{"type":"bg","value":"6.6","unit":"mmol/L","mmol":6.6,"time":"16:33",""" +
                """"ts":1792398000000,"trend":"FLAT","delta":0.1,"connected":true}""",
            json,
        )
    }

    @Test
    fun `encodes missing numbers as null and escapes strings`() {
        val odd = sample.copy(value = "L\"O\\", mmol = null, readingAt = null, connected = false)

        val json = BandMessage.encodeReading(odd, Trend.NONE, null)

        assertEquals(
            """{"type":"bg","value":"L\"O\\","unit":"mmol/L","mmol":null,"time":"16:33",""" +
                """"ts":null,"trend":"NONE","delta":null,"connected":false}""",
            json,
        )
    }

    @Test
    fun `recognises sync requests from the band`() {
        assertTrue(BandMessage.isSyncRequest("""{"type":"sync"}"""))
        assertTrue(BandMessage.isSyncRequest("""{ "type" : "sync" }"""))
        assertTrue(BandMessage.isSyncRequest("""{"data":{"type":"sync"}}"""))
        assertFalse(BandMessage.isSyncRequest("""{"type":"bg"}"""))
        assertFalse(BandMessage.isSyncRequest("garbage"))
    }
}
