import { test } from 'node:test'
import assert from 'node:assert/strict'
import { wavHeader, parseWavHeader, floatToPcm, wavDurationMs, WAV_HEADER_BYTES } from '../src/wav.js'

test('header round-trips and carries the real sizes', () => {
  const h = wavHeader({ sampleRate: 48000, channels: 2, bits: 24, dataBytes: 288000 })
  assert.equal(h.length, WAV_HEADER_BYTES)
  assert.equal(String.fromCharCode(...h.slice(0, 4)), 'RIFF')
  assert.equal(String.fromCharCode(...h.slice(36, 40)), 'data')
  const p = parseWavHeader(h)
  assert.deepEqual(p, { channels: 2, sampleRate: 48000, byteRate: 288000, bits: 24, dataBytes: 288000 })
  assert.equal(new DataView(h.buffer).getUint32(4, true), 36 + 288000)
  assert.equal(wavDurationMs(p, 44 + 288000), 1000)
})

test('rejects other bit depths and non-WAV bytes', () => {
  assert.throws(() => wavHeader({ sampleRate: 48000, channels: 1, bits: 32, dataBytes: 0 }))
  assert.equal(parseWavHeader(new Uint8Array(44)), null)
  assert.equal(parseWavHeader(new Uint8Array(10)), null)
})

test('16 bit PCM matches the Android rounding', () => {
  const b = floatToPcm(new Float32Array([0, 1, -1, 0.5]), 16)
  assert.deepEqual([...b], [0, 0, 0xff, 0x7f, 0x01, 0x80, 0x00, 0x40])
})

test('24 bit PCM, little-endian, clamped', () => {
  const b = floatToPcm(new Float32Array([1, -1, 2]), 24)
  assert.deepEqual([...b], [0xff, 0xff, 0x7f, 0x01, 0x00, 0x80, 0xff, 0xff, 0x7f])
})
