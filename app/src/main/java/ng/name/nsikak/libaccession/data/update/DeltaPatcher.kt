package ng.name.nsikak.libaccession.data.update

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.zip.GZIPInputStream

/**
 * Pure Kotlin BSDiff patch engine (bspatch).
 * Combines an installed source APK file + downloaded binary patch file -> output target APK file.
 */
object DeltaPatcher {

    private const val BSDIFF_MAGIC = "BSDIFF40"

    /**
     * Applies a BSDiff patch file to [sourceApk] and writes the reconstructed APK to [outputApk].
     * Returns true if successfully reconstructed, false otherwise.
     */
    fun applyPatch(sourceApk: File, patchFile: File, outputApk: File): Boolean {
        if (!sourceApk.exists() || !patchFile.exists()) return false

        try {
            RandomAccessFile(sourceApk, "r").use { oldRaf ->
                DataInputStream(BufferedInputStream(FileInputStream(patchFile))).use { patchStream ->
                    // 1. Read header (32 bytes)
                    val magic = ByteArray(8)
                    patchStream.readFully(magic)
                    if (String(magic, Charsets.US_ASCII) != BSDIFF_MAGIC) {
                        return false
                    }

                    val ctrlLen = readOff64(patchStream)
                    val diffLen = readOff64(patchStream)
                    val newSize = readOff64(patchStream)

                    if (ctrlLen < 0 || diffLen < 0 || newSize < 0) {
                        return false
                    }

                    // 2. Read control block, diff block, extra block bytes
                    val ctrlBlockBytes = ByteArray(ctrlLen.toInt())
                    patchStream.readFully(ctrlBlockBytes)

                    val diffBlockBytes = ByteArray(diffLen.toInt())
                    patchStream.readFully(diffBlockBytes)

                    val extraBlockBytes = patchStream.readBytes()

                    // Wrap in streams (support GZIP stream decompression if needed)
                    val ctrlStream = createDecompressedStream(ctrlBlockBytes)
                    val diffStream = createDecompressedStream(diffBlockBytes)
                    val extraStream = createDecompressedStream(extraBlockBytes)

                    var oldPos = 0L
                    var newPos = 0L

                    FileOutputStream(outputApk).use { fos ->
                        BufferedOutputStream(fos).use { out ->
                            val ctrlReader = DataInputStream(ctrlStream)

                            val bufDiff = ByteArray(8192)
                            val bufOld = ByteArray(8192)

                            while (newPos < newSize) {
                                // Read 3 control tuple entries: diffLen, extraLen, seekOffset
                                val ctrlDiffLen = readOff64(ctrlReader)
                                val ctrlExtraLen = readOff64(ctrlReader)
                                val ctrlSeekOff = readOff64(ctrlReader)

                                // Sanity check
                                if (newPos + ctrlDiffLen > newSize) {
                                    return false
                                }

                                // Add diff bytes to old bytes
                                var remainingDiff = ctrlDiffLen
                                while (remainingDiff > 0) {
                                    val chunkSize = minOf(remainingDiff, bufDiff.size.toLong()).toInt()
                                    diffStream.readFully(bufDiff, 0, chunkSize)

                                    oldRaf.seek(oldPos)
                                    val readOld = oldRaf.read(bufOld, 0, chunkSize)
                                    if (readOld < chunkSize) {
                                        // Padding with 0 if past old EOF
                                        for (i in readOld until chunkSize) {
                                            bufOld[i] = 0
                                        }
                                    }

                                    for (i in 0 until chunkSize) {
                                        bufDiff[i] = (bufDiff[i] + bufOld[i]).toByte()
                                    }
                                    out.write(bufDiff, 0, chunkSize)

                                    oldPos += chunkSize
                                    newPos += chunkSize
                                    remainingDiff -= chunkSize
                                }

                                // Read extra bytes
                                if (ctrlExtraLen > 0) {
                                    if (newPos + ctrlExtraLen > newSize) {
                                        return false
                                    }

                                    var remainingExtra = ctrlExtraLen
                                    while (remainingExtra > 0) {
                                        val chunkSize = minOf(remainingExtra, bufDiff.size.toLong()).toInt()
                                        extraStream.readFully(bufDiff, 0, chunkSize)
                                        out.write(bufDiff, 0, chunkSize)
                                        newPos += chunkSize
                                        remainingExtra -= chunkSize
                                    }
                                }

                                // Seek old position
                                oldPos += ctrlSeekOff
                            }
                            out.flush()
                        }
                    }
                }
            }
            return outputApk.exists() && outputApk.length() > 0
        } catch (e: Exception) {
            outputApk.delete()
            return false
        }
    }

    private fun createDecompressedStream(bytes: ByteArray): InputStream {
        val bais = bytes.inputStream()
        return if (bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
            GZIPInputStream(bais)
        } else {
            bais
        }
    }

    private fun readOff64(isStream: DataInputStream): Long {
        val buf = ByteArray(8)
        isStream.readFully(buf)
        var y = (buf[7].toLong() and 0x7F)
        for (i in 6 downTo 0) {
            y = (y shl 8) or (buf[i].toLong() and 0xFF)
        }
        if ((buf[7].toInt() and 0x80) != 0) {
            y = -y
        }
        return y
    }

    private fun InputStream.readFully(b: ByteArray, off: Int = 0, len: Int = b.size) {
        var count = 0
        while (count < len) {
            val r = read(b, off + count, len - count)
            if (r < 0) throw EOFException("Unexpected EOF in patch stream")
            count += r
        }
    }
}
