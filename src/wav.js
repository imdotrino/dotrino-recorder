// WAV PCM entero (16 o 24 bit), lo mismo que `WavSink` de Android (android/.../Sinks.kt).
// Funciones puras: las usa el worker que escribe y las prueban los tests.

/** El tope del formato: los tamaños de la cabecera son de 32 bits (mismo margen que Android). */
export const WAV_MAX_DATA = 0xFFFFFFFF - 36 - 64 * 1024
export const WAV_HEADER_BYTES = 44

/** La cabecera de 44 bytes con los tamaños reales de `dataBytes`. */
export function wavHeader ({ sampleRate, channels, bits, dataBytes }) {
  if (bits !== 16 && bits !== 24) throw new Error(`unsupported bit depth: ${bits}`)
  const bytesPerSample = bits / 8
  const b = new ArrayBuffer(WAV_HEADER_BYTES)
  const v = new DataView(b)
  const ascii = (o, s) => { for (let i = 0; i < s.length; i++) v.setUint8(o + i, s.charCodeAt(i)) }
  ascii(0, 'RIFF'); v.setUint32(4, (36 + dataBytes) >>> 0, true)
  ascii(8, 'WAVE')
  ascii(12, 'fmt '); v.setUint32(16, 16, true)
  v.setUint16(20, 1, true)                                   // PCM
  v.setUint16(22, channels, true)
  v.setUint32(24, sampleRate, true)
  v.setUint32(28, sampleRate * channels * bytesPerSample, true)
  v.setUint16(32, channels * bytesPerSample, true)
  v.setUint16(34, bits, true)
  ascii(36, 'data'); v.setUint32(40, dataBytes >>> 0, true)
  return new Uint8Array(b)
}

/** Lee la cabecera de un WAV: `null` si no lo es. */
export function parseWavHeader (bytes) {
  if (!bytes || bytes.byteLength < WAV_HEADER_BYTES) return null
  const v = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength)
  const tag = (o) => String.fromCharCode(v.getUint8(o), v.getUint8(o + 1), v.getUint8(o + 2), v.getUint8(o + 3))
  if (tag(0) !== 'RIFF' || tag(8) !== 'WAVE' || tag(12) !== 'fmt ') return null
  return {
    channels: v.getUint16(22, true),
    sampleRate: v.getUint32(24, true),
    byteRate: v.getUint32(28, true),
    bits: v.getUint16(34, true),
    dataBytes: v.getUint32(40, true),
  }
}

/**
 * Muestras float intercaladas en [-1, 1] → PCM entero little-endian. Mismo redondeo que
 * Android (`roundToInt` de v * 32767 o v * 8388607), así un mismo audio da los mismos bytes.
 */
export function floatToPcm (samples, bits, out) {
  const n = samples.length
  const bps = bits / 8
  const buf = out && out.length >= n * bps ? out : new Uint8Array(n * bps)
  let o = 0
  if (bits === 16) {
    for (let i = 0; i < n; i++) {
      const s = clamp(samples[i])
      const x = Math.round(s * 32767)
      buf[o++] = x & 0xff; buf[o++] = (x >> 8) & 0xff
    }
  } else if (bits === 24) {
    for (let i = 0; i < n; i++) {
      const s = clamp(samples[i])
      const x = Math.round(s * 8388607)
      buf[o++] = x & 0xff; buf[o++] = (x >> 8) & 0xff; buf[o++] = (x >> 16) & 0xff
    }
  } else {
    throw new Error(`unsupported bit depth: ${bits}`)
  }
  return buf.subarray(0, n * bps)
}

const clamp = (v) => (v > 1 ? 1 : v < -1 ? -1 : v)

/** Duración de un WAV a partir del tamaño del archivo (la cabecera puede ir un segundo atrás). */
export function wavDurationMs (h, fileBytes) {
  if (!h || !h.byteRate) return -1
  return Math.floor(Math.max(0, fileBytes - WAV_HEADER_BYTES) * 1000 / h.byteRate)
}
