package io.legado.app.model.checkalgo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckUrlBloomTest {

    @Test
    fun putThenMightContain() {
        val bloom = CheckUrlBloom(expectedInsertions = 100, falsePositiveRate = 0.01)
        val url = "https://example.com/book/1"
        assertFalse(bloom.mightContain(url))
        bloom.put(url)
        assertTrue(bloom.mightContain(url))
    }

    @Test
    fun putAllAddsEveryUrl() {
        val bloom = CheckUrlBloom(expectedInsertions = 100, falsePositiveRate = 0.01)
        val urls = listOf(
            "https://a.com/1",
            "https://b.com/2",
            "https://c.com/3",
        )
        bloom.putAll(urls)
        for (url in urls) {
            assertTrue(bloom.mightContain(url))
        }
    }

    @Test
    fun emptyUrlIsIgnored() {
        val bloom = CheckUrlBloom()
        bloom.put("")
        assertFalse(bloom.mightContain(""))
    }

    @Test
    fun unseenUrlReturnsFalse() {
        val bloom = CheckUrlBloom()
        bloom.put("https://seen.com")
        assertFalse(bloom.mightContain("https://unseen.com"))
    }

    @Test
    fun crossBatchDedupWithinSameFilter() {
        val bloom = CheckUrlBloom(expectedInsertions = 50, falsePositiveRate = 0.01)
        val batch1 = listOf("https://host/a", "https://host/b")
        val batch2 = listOf("https://host/b", "https://host/c")
        bloom.putAll(batch1)
        assertTrue(bloom.mightContain("https://host/b"))
        bloom.putAll(batch2.filterNot { bloom.mightContain(it) })
        assertTrue(bloom.mightContain("https://host/c"))
    }

    @Test
    fun falsePositiveRateWithinBudget() {
        val n = 5_000
        val p = 0.01
        val bloom = CheckUrlBloom(expectedInsertions = n, falsePositiveRate = p)
        val inserted = (0 until n).map { "https://site.example/path/$it" }
        bloom.putAll(inserted)
        var falsePositives = 0
        val probes = 10_000
        for (i in n until n + probes) {
            if (bloom.mightContain("https://site.example/path/$i")) {
                falsePositives++
            }
        }
        val rate = falsePositives.toDouble() / probes
        assertTrue("false positive rate $rate exceeded budget", rate < p * 3)
    }

    @Test
    fun optimalSizingHelpers() {
        val bits = CheckUrlBloom.optimalNumOfBits(10_000, 0.01)
        val k = CheckUrlBloom.optimalNumOfHashFunctions(10_000, bits)
        assertTrue(bits > 10_000)
        assertTrue(k in 1..bits)
    }

    @Test
    fun murmurIsDeterministic() {
        val bytes = "https://example.com".toByteArray(Charsets.UTF_8)
        assertTrue(
            CheckUrlBloom.murmur3_32(bytes, 0) == CheckUrlBloom.murmur3_32(bytes, 0)
        )
    }
}
