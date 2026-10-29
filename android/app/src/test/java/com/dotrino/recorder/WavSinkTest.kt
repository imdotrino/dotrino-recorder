package com.dotrino.recorder

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/** Un tono conocido entra, y el WAV que sale tiene la cabecera y las muestras exactas. */
class WavSinkTest {
    private fun tone(frames: Int, ch: Int) = FloatArray(frames * ch) { i -> (0.5 * sin(2 * PI * 1000 * (i / ch) / 48000.0)).toFloat() }

    private fun check(bits: Int) {
        val f = File.createTempFile("wav", ".wav")
        val frames = 48000; val ch = 2
        val buf = tone(frames, ch)
        WavSink(f, 48000, ch, bits).apply { write(buf, frames); close() }
        val d = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        val bps = bits / 8
        assertEquals(44 + frames * ch * bps, d.capacity())
        assertEquals("RIFF", String(f.readBytes(), 0, 4))
        assertEquals(36 + frames * ch * bps, d.getInt(4))
        assertEquals(ch, d.getShort(22).toInt())
        assertEquals(48000, d.getInt(24))
        assertEquals(bits, d.getShort(34).toInt())
        assertEquals(frames * ch * bps, d.getInt(40))
        val full = if (bits == 16) 32767f else 8388607f
        for (i in listOf(0, 7, 12, 100, 4801, frames * ch - 1)) {
            val p = 44 + i * bps
            val got = if (bits == 16) d.getShort(p).toInt()
                      else (d.get(p).toInt() and 0xff) or ((d.get(p + 1).toInt() and 0xff) shl 8) or (d.get(p + 2).toInt() shl 16)
            assertEquals("sample $i", (buf[i] * full).roundToInt(), got)
        }
        f.delete()
    }

    @Test fun wav16() = check(16)
    @Test fun wav24() = check(24)
}
