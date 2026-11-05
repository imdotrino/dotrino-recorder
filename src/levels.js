// Nivel, ganancia y formatos de texto: lo mismo que Engine.kt / MainActivity.kt, en funciones puras.

export const SILENCE = -90
export const GAIN_MIN = -12
export const GAIN_MAX = 24
export const METER_FLOOR = -60

/** Amplitud (0..1) → dBFS, con el mismo suelo que Android. */
export function toDb (a) {
  if (!(a > 0)) return SILENCE
  return Math.max(SILENCE, 20 * Math.log10(a))
}

export const gainFactor = (db) => Math.pow(10, db / 20)

/**
 * Ganancia, recorte y picos en una pasada sobre muestras intercaladas (se modifican en sitio).
 * Devuelve `clipped` si alguna llegó al tope. `peaks` acumula el máximo por canal.
 */
export function applyGain (samples, channels, gain, peaks) {
  let clipped = false
  for (let i = 0; i < samples.length; i++) {
    let v = samples[i] * gain
    if (v >= 1) { v = 1; clipped = true } else if (v <= -1) { v = -1; clipped = true }
    samples[i] = v
    const c = channels === 2 ? (i & 1) : 0
    const a = v < 0 ? -v : v
    if (a > peaks[c]) peaks[c] = a
  }
  return clipped
}

/** El medidor sube al instante y baja suave (1,5 dB por cuadro), como `MeterView`. */
export function smooth (prev, next) {
  return next >= prev ? next : Math.max(next, prev - 1.5)
}

/** Posición 0..1 de un nivel en la escala del medidor (-60..0 dBFS). */
export const meterPos = (db) => (Math.min(0, Math.max(METER_FLOOR, db)) - METER_FLOOR) / -METER_FLOOR

export function clock (ms) {
  const s = Math.floor(ms / 1000)
  return `${Math.floor(s / 3600)}:${String(Math.floor(s / 60) % 60).padStart(2, '0')}:${String(s % 60).padStart(2, '0')}`
}

export function size (b) {
  if (b >= 2 ** 30) return (b / 2 ** 30).toFixed(2) + ' GB'
  if (b >= 2 ** 20) return (b / 2 ** 20).toFixed(1) + ' MB'
  return Math.floor(b / 1024) + ' KB'
}

/** Bytes por segundo en disco de una configuración (comprimido: la tasa fija del codificador). */
export function bytesPerSecond ({ format, sampleRate, channels }, compressedBps) {
  if (format === 'WAV24') return sampleRate * channels * 3
  if (format === 'WAV16') return sampleRate * channels * 2
  return compressedBps / 8
}

export const kHz = (rate) => (rate / 1000).toFixed(1).replace(/\.0$/, '') + ' kHz'
