import { test } from 'node:test'
import assert from 'node:assert/strict'
import { toDb, applyGain, gainFactor, smooth, meterPos, clock, size, bytesPerSecond, SILENCE } from '../src/levels.js'

test('dBFS of a peak', () => {
  assert.equal(toDb(1), 0)
  assert.ok(Math.abs(toDb(0.5) - -6.0206) < 1e-3)
  assert.equal(toDb(0), SILENCE)
  assert.equal(toDb(1e-9), SILENCE)
})

test('gain, clip and peaks per channel in one pass', () => {
  const s = new Float32Array([0.25, -0.1, 0.6, 0.05])
  const peaks = [0, 0]
  const clipped = applyGain(s, 2, gainFactor(6.0206), peaks)
  assert.equal(clipped, true)                // 0.6 * 2 = 1.2
  assert.equal(s[2], 1)
  assert.ok(Math.abs(peaks[0] - 1) < 1e-6)
  assert.ok(Math.abs(peaks[1] - 0.2) < 1e-6)
  const mono = [0, 0]
  assert.equal(applyGain(new Float32Array([0.1, -0.3]), 1, 1, mono), false)
  assert.ok(Math.abs(mono[0] - 0.3) < 1e-6)
})

test('meter falls slowly and rises at once', () => {
  assert.equal(smooth(-20, -6), -6)
  assert.equal(smooth(-6, -40), -7.5)
  assert.equal(meterPos(0), 1)
  assert.equal(meterPos(-60), 0)
  assert.equal(meterPos(-90), 0)
})

test('text formats', () => {
  assert.equal(clock(3_723_000), '1:02:03')
  assert.equal(size(2048), '2 KB')
  assert.equal(size(3 * 2 ** 20), '3.0 MB')
  assert.equal(bytesPerSecond({ format: 'WAV24', sampleRate: 48000, channels: 2 }), 288000)
  assert.equal(bytesPerSecond({ format: 'AAC', sampleRate: 48000, channels: 2 }, 256000), 32000)
})
