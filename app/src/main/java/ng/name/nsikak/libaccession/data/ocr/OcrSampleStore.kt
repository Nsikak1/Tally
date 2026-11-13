package ng.name.nsikak.libaccession.data.ocr

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Keeps every confirmed crop with its corrected label, as training data for a custom digit model.
 *
 * Folder:  <app external files>/ocr_samples/
 *   crop_<millis>.png     the cropped photo, unprocessed
 *   labels.csv            file,label,recognized,mode,width,height,saved_at
 * `label` is what you confirmed (the truth); `recognized` is what the OCR first proposed.
 * Pull it with:  adb pull /sdcard/Android/data/ng.name.nsikak.libaccession/files/ocr_samples
 * or use the in-app "Share samples" button (zip).
 */
class OcrSampleStore(private val context: Context) {
    private val dir: File
        get() = (context.getExternalFilesDir("ocr_samples") ?: File(context.filesDir, "ocr_samples"))
            .also { it.mkdirs() }

    private val csv: File get() = File(dir, "labels.csv")

    /** Saves one sample and returns the new total. */
    suspend fun save(crop: Bitmap, label: String, recognized: String, mode: String): Int =
        withContext(Dispatchers.IO) {
            var stamp = System.currentTimeMillis()
            while (File(dir, "crop_$stamp.png").exists()) stamp++
            val name = "crop_$stamp.png"
            File(dir, name).outputStream().use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }

            val isNew = !csv.exists()
            csv.appendText(
                buildString {
                    if (isNew) appendLine("file,label,recognized,mode,width,height,saved_at")
                    appendLine(
                        listOf(name, label, recognized, mode, crop.width.toString(), crop.height.toString(), stamp.toString())
                            .joinToString(",") { csvCell(it) },
                    )
                },
            )
            countBlocking()
        }

    suspend fun count(): Int = withContext(Dispatchers.IO) { countBlocking() }

    private fun countBlocking(): Int =
        csv.takeIf { it.exists() }?.useLines { lines -> lines.count() - 1 }?.coerceAtLeast(0) ?: 0

    /** Zips the whole samples folder into the cache and returns the zip. */
    suspend fun exportZip(): File = withContext(Dispatchers.IO) {
        val outDir = File(context.cacheDir, "share").also { it.mkdirs() }
        val zip = File(outDir, "tally-ocr-samples.zip")
        ZipOutputStream(zip.outputStream().buffered()).use { out ->
            dir.listFiles()?.sortedBy { it.name }?.forEach { file ->
                out.putNextEntry(ZipEntry("ocr_samples/${file.name}"))
                file.inputStream().use { it.copyTo(out) }
                out.closeEntry()
            }
        }
        zip
    }

    private fun csvCell(value: String): String {
        val flat = value.replace('\n', ' ').replace('\r', ' ')
        return "\"" + flat.replace("\"", "\"\"") + "\""
    }
}
