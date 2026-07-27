package io.legado.app.model.checkalgo

import java.util.BitSet
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.pow

/**
 * Probabilistic set for cross-batch URL deduplication during bulk source checks.
 * False positives are possible; negatives never occur once [put] has been called.
 */
class CheckUrlBloom(
    expectedInsertions: Int = 10_000,
    falsePositiveRate: Double = 0.01,
) {

    private val bitSet: BitSet
    private val numBits: Int
    private val numHashFunctions: Int

    init {
        require(expectedInsertions > 0) { "expectedInsertions must be positive" }
        require(falsePositiveRate in 0.0..1.0) { "falsePositiveRate must be in [0, 1]" }
        numBits = optimalNumOfBits(expectedInsertions, falsePositiveRate)
        numHashFunctions = optimalNumOfHashFunctions(expectedInsertions, numBits)
        bitSet = BitSet(numBits)
    }

    fun mightContain(url: String): Boolean {
        if (url.isEmpty()) return false
        val bytes = url.toByteArray(Charsets.UTF_8)
        val h1 = murmur3_32(bytes, 0)
        val h2 = murmur3_32(bytes, 0x9747_b28c.toInt())
        var combined = h1
        synchronized(bitSet) {
            for (i in 0 until numHashFunctions) {
                if (!bitSet.get(indexOf(combined))) {
                    return false
                }
                combined += h2
            }
            return true
        }
    }

    fun put(url: String) {
        if (url.isEmpty()) return
        val bytes = url.toByteArray(Charsets.UTF_8)
        val h1 = murmur3_32(bytes, 0)
        val h2 = murmur3_32(bytes, 0x9747_b28c.toInt())
        var combined = h1
        synchronized(bitSet) {
            for (i in 0 until numHashFunctions) {
                bitSet.set(indexOf(combined))
                combined += h2
            }
        }
    }

    fun putAll(urls: Iterable<String>) {
        for (url in urls) {
            put(url)
        }
    }

    fun clear() {
        synchronized(bitSet) { bitSet.clear() }
    }

    private fun indexOf(hash: Int): Int {
        var n = hash
        n += n shl 15
        n = n ushr 10
        return (n and Int.MAX_VALUE) % numBits
    }

    companion object {
        internal fun optimalNumOfBits(n: Int, p: Double): Int {
            if (p == 0.0) return Int.MAX_VALUE
            return ceil(-n * ln(p) / ln(2.0).pow(2)).toInt().coerceAtLeast(1)
        }

        internal fun optimalNumOfHashFunctions(n: Int, m: Int): Int {
            return ceil(m / n.toDouble() * ln(2.0)).toInt().coerceIn(1, m)
        }

        /** MurmurHash3 32-bit; deterministic across JVM for UTF-8 URL bytes. */
        internal fun murmur3_32(data: ByteArray, seed: Int): Int {
            var h = seed
            val c1 = 0xcc9e2d51.toInt()
            val c2 = 0x1b873593.toInt()
            val len = data.size
            var i = 0
            while (i + 4 <= len) {
                var k = (data[i].toInt() and 0xff)
                k = k or ((data[i + 1].toInt() and 0xff) shl 8)
                k = k or ((data[i + 2].toInt() and 0xff) shl 16)
                k = k or ((data[i + 3].toInt() and 0xff) shl 24)
                k *= c1
                k = (k shl 15) or (k ushr 17)
                k *= c2
                h = h xor k
                h = (h shl 13) or (h ushr 19)
                h = h * 5 + 0xe654_6b64.toInt()
                i += 4
            }
            var k1 = 0
            val rem = len - i
            if (rem >= 3) {
                k1 = k1 xor ((data[i + 2].toInt() and 0xff) shl 16)
            }
            if (rem >= 2) {
                k1 = k1 xor ((data[i + 1].toInt() and 0xff) shl 8)
            }
            if (rem >= 1) {
                k1 = k1 xor (data[i].toInt() and 0xff)
            }
            if (rem > 0) {
                k1 *= c1
                k1 = (k1 shl 15) or (k1 ushr 17)
                k1 *= c2
                h = h xor k1
            }
            h = h xor len
            h = fmix32(h)
            return h
        }

        private fun fmix32(h: Int): Int {
            var n = h
            n = n xor (n ushr 16)
            n *= 0x85eb_ca6b.toInt()
            n = n xor (n ushr 13)
            n *= 0xc2b2_ae35.toInt()
            n = n xor (n ushr 16)
            return n
        }
    }
}
