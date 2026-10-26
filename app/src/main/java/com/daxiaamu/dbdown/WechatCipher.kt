package com.daxiaamu.dbdown

import java.io.File
import java.io.RandomAccessFile

/** Video-channel files XOR their first 128 KiB with a big-endian ISAAC-64 stream. */
internal object WechatCipher {
    fun decrypt(source: File, output: File, key: String): File {
        source.copyTo(output, overwrite = true)
        RandomAccessFile(output, "rw").use { file ->
            val head = ByteArray(minOf(file.length(), 131072).toInt())
            file.readFully(head)
            transform(head, key)
            file.seek(0); file.write(head)
        }
        return output
    }

    internal fun transform(bytes: ByteArray, key: String) {
        val random = Isaac64(key.toULong().toLong())
        var word = 0L
        for(i in 0 until minOf(bytes.size, 131072)) {
            if(i % 8 == 0) word = random.next()
            bytes[i] = (bytes[i].toInt() xor (word ushr (56 - (i % 8) * 8)).toInt()).toByte()
        }
    }
}

/** ISAAC-64 by Bob Jenkins; arithmetic deliberately wraps at 64 bits.
 * Algorithm/reference: oliver-zch/wx-video-channel-download (MIT; docs/licenses/wechat-reference-MIT.txt).
 */
private class Isaac64(seed: Long) {
    private val memory = LongArray(256)
    private val results = LongArray(256)
    private var a = 0L
    private var b = 0L
    private var c = 0L
    private var index = 255
    init {
        results[0] = seed
        val mix = LongArray(8) { 0x9e3779b97f4a7c13UL.toLong() }
        repeat(4) { mix(mix) }
        for(pass in 0..1) for(i in 0 until 256 step 8) {
            for(j in 0..7) mix[j] += if(pass == 0) results[i+j] else memory[i+j]
            mix(mix)
            for(j in 0..7) memory[i+j] = mix[j]
        }
        generate()
    }
    fun next(): Long {
        val value = results[index]
        if(index == 0) { generate(); index = 255 } else index--
        return value
    }
    private fun generate() {
        c++; b += c
        for(i in 0..255) {
            a = when(i % 4) {
                0 -> (a xor (a shl 21)).inv()
                1 -> a xor (a ushr 5)
                2 -> a xor (a shl 12)
                else -> a xor (a ushr 33)
            }
            a += memory[(i+128) and 255]
            val x = memory[i]
            val y = memory[((x ushr 3) and 255).toInt()] + a + b
            memory[i] = y
            b = memory[((y ushr 11) and 255).toInt()] + x
            results[i] = b
        }
    }
    private fun mix(v: LongArray) {
        v[0] -= v[4]; v[5] = v[5] xor (v[7] ushr 9); v[7] += v[0]
        v[1] -= v[5]; v[6] = v[6] xor (v[0] shl 9); v[0] += v[1]
        v[2] -= v[6]; v[7] = v[7] xor (v[1] ushr 23); v[1] += v[2]
        v[3] -= v[7]; v[0] = v[0] xor (v[2] shl 15); v[2] += v[3]
        v[4] -= v[0]; v[1] = v[1] xor (v[3] ushr 14); v[3] += v[4]
        v[5] -= v[1]; v[2] = v[2] xor (v[4] shl 20); v[4] += v[5]
        v[6] -= v[2]; v[3] = v[3] xor (v[5] ushr 17); v[5] += v[6]
        v[7] -= v[3]; v[4] = v[4] xor (v[6] shl 14); v[6] += v[7]
    }
}
