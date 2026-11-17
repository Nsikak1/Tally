package ng.name.nsikak.tally.build

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.zip.GZIPOutputStream

/**
 * Pure Kotlin BSDiff 4.0 patch generator.
 * Produces binary delta patch files fully compatible with bspatch / DeltaPatcher.kt.
 */
object BsdiffGenerator {

    private const val BSDIFF_MAGIC = "BSDIFF40"

    fun generatePatch(oldFile: File, newFile: File, patchFile: File) {
        val oldBytes = oldFile.readBytes()
        val newBytes = newFile.readBytes()
        val patchBytes = createPatch(oldBytes, newBytes)
        patchFile.parentFile?.mkdirs()
        patchFile.writeBytes(patchBytes)
    }

    fun createPatch(oldBytes: ByteArray, newBytes: ByteArray): ByteArray {
        val oldSize = oldBytes.size
        val newSize = newBytes.size

        val blockSize = 16
        val oldIndex = HashMap<Int, IntArrayList>(minOf(oldSize / 8, 500_000))

        var i = 0
        while (i <= oldSize - blockSize) {
            val hash = hash16(oldBytes, i)
            var list = oldIndex[hash]
            if (list == null) {
                list = IntArrayList(4)
                oldIndex[hash] = list
            }
            list.add(i)
            i += 8
        }

        val ctrlStream = ByteArrayOutputStream()
        val diffStream = ByteArrayOutputStream()
        val extraStream = ByteArrayOutputStream()

        val lastOldPos = 0L
        var lastNewPos = 0
        var newPos = 0

        var currentMatchOldPos = -1L
        var currentMatchLen = 0

        while (newPos < newSize) {
            var bestMatchOldPos = -1
            var bestMatchLen = 0

            if (newPos <= newSize - blockSize) {
                val hash = hash16(newBytes, newPos)
                val candidates = oldIndex[hash]
                if (candidates != null) {
                    for (idx in 0 until candidates.size) {
                        val candPos = candidates.get(idx)
                        var len = 0
                        while (candPos + len < oldSize && newPos + len < newSize && oldBytes[candPos + len] == newBytes[newPos + len]) {
                            len++
                        }
                        if (len > bestMatchLen) {
                            bestMatchLen = len
                            bestMatchOldPos = candPos
                        }
                    }
                }
            }

            if (bestMatchLen >= blockSize) {
                var matchOldPos = bestMatchOldPos
                var matchNewPos = newPos
                var matchLen = bestMatchLen

                while (matchOldPos > 0 && matchNewPos > lastNewPos && oldBytes[matchOldPos - 1] == newBytes[matchNewPos - 1]) {
                    matchOldPos--
                    matchNewPos--
                    matchLen++
                }

                val extraLen = matchNewPos - lastNewPos
                if (extraLen > 0) {
                    extraStream.write(newBytes, lastNewPos, extraLen)
                }

                if (currentMatchOldPos < 0) {
                    writeOff64(0L, ctrlStream)
                    writeOff64(extraLen.toLong(), ctrlStream)
                    writeOff64(matchOldPos.toLong() - lastOldPos, ctrlStream)
                } else {
                    val seekOff = matchOldPos.toLong() - (currentMatchOldPos + currentMatchLen)
                    writeOff64(currentMatchLen.toLong(), ctrlStream)
                    writeOff64(extraLen.toLong(), ctrlStream)
                    writeOff64(seekOff, ctrlStream)
                }

                for (k in 0 until matchLen) {
                    val diffByte = (newBytes[matchNewPos + k].toInt() - oldBytes[matchOldPos + k].toInt()).toByte()
                    diffStream.write(diffByte.toInt())
                }

                currentMatchOldPos = matchOldPos.toLong()
                currentMatchLen = matchLen
                lastNewPos = matchNewPos + matchLen
                newPos = lastNewPos
            } else {
                newPos++
            }
        }

        val finalExtraLen = newSize - lastNewPos
        if (finalExtraLen > 0) {
            extraStream.write(newBytes, lastNewPos, finalExtraLen)
        }

        if (currentMatchOldPos < 0) {
            writeOff64(0L, ctrlStream)
            writeOff64(finalExtraLen.toLong(), ctrlStream)
            writeOff64(0L, ctrlStream)
        } else {
            writeOff64(currentMatchLen.toLong(), ctrlStream)
            writeOff64(finalExtraLen.toLong(), ctrlStream)
            writeOff64(0L, ctrlStream)
        }

        val ctrlCompressed = compressGzip(ctrlStream.toByteArray())
        val diffCompressed = compressGzip(diffStream.toByteArray())
        val extraCompressed = compressGzip(extraStream.toByteArray())

        val patchBaos = ByteArrayOutputStream()
        patchBaos.write(BSDIFF_MAGIC.toByteArray(Charsets.US_ASCII))
        writeOff64(ctrlCompressed.size.toLong(), patchBaos)
        writeOff64(diffCompressed.size.toLong(), patchBaos)
        writeOff64(newSize.toLong(), patchBaos)

        patchBaos.write(ctrlCompressed)
        patchBaos.write(diffCompressed)
        patchBaos.write(extraCompressed)

        return patchBaos.toByteArray()
    }

    private fun compressGzip(data: ByteArray): ByteArray {
        val baos = ByteArrayOutputStream()
        GZIPOutputStream(baos).use { gzip ->
            gzip.write(data)
        }
        return baos.toByteArray()
    }

    private fun hash16(bytes: ByteArray, offset: Int): Int {
        var h = 1
        for (i in 0 until 16) {
            h = 31 * h + bytes[offset + i]
        }
        return h
    }

    private fun writeOff64(value: Long, out: OutputStream) {
        var v = value
        val negative = v < 0
        if (negative) v = -v
        val buf = ByteArray(8)
        buf[0] = (v and 0xFF).toByte()
        buf[1] = ((v ushr 8) and 0xFF).toByte()
        buf[2] = ((v ushr 16) and 0xFF).toByte()
        buf[3] = ((v ushr 24) and 0xFF).toByte()
        buf[4] = ((v ushr 32) and 0xFF).toByte()
        buf[5] = ((v ushr 40) and 0xFF).toByte()
        buf[6] = ((v ushr 48) and 0xFF).toByte()
        buf[7] = ((v ushr 56) and 0x7F).toByte()
        if (negative) {
            buf[7] = (buf[7].toInt() or 0x80).toByte()
        }
        out.write(buf)
    }

    private class IntArrayList(initialCapacity: Int = 4) {
        var data = IntArray(initialCapacity)
        var size = 0
        fun add(element: Int) {
            if (size == data.size) {
                data = data.copyOf(data.size * 2)
            }
            data[size++] = element
        }
        fun get(index: Int): Int = data[index]
    }
}
