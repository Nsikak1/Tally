package ng.name.nsikak.libaccession.data.update

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateRepositoryTest {

    @Test
    fun testParseTotalFromContentRange() {
        val dummyRepo = DummyRepo()

        assertEquals(5000L, dummyRepo.parseTotalFromContentRange("bytes 1000-1999/5000"))
        assertEquals(26214400L, dummyRepo.parseTotalFromContentRange("bytes 0-1023/26214400"))
        assertEquals(-1L, dummyRepo.parseTotalFromContentRange("bytes 1000-1999/*"))
        assertEquals(-1L, dummyRepo.parseTotalFromContentRange(null))
        assertEquals(-1L, dummyRepo.parseTotalFromContentRange(""))
        assertEquals(-1L, dummyRepo.parseTotalFromContentRange("invalid"))
    }

    private class DummyRepo {
        fun parseTotalFromContentRange(header: String?): Long {
            if (header.isNullOrBlank()) return -1L
            return try {
                val slashIndex = header.indexOf('/')
                if (slashIndex != -1) {
                    val totalStr = header.substring(slashIndex + 1).trim()
                    if (totalStr != "*") totalStr.toLong() else -1L
                } else -1L
            } catch (_: Exception) {
                -1L
            }
        }
    }
}
