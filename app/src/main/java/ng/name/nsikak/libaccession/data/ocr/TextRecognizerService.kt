package ng.name.nsikak.libaccession.data.ocr

import android.graphics.Bitmap
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class RecognizedLine(val text: String, val box: android.graphics.Rect?)

/**
 * ML Kit text recognition, Latin script, BUNDLED model: it ships inside the APK, works offline,
 * and nothing is downloaded at run time.
 */
class TextRecognizerService {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    /** Every text line found in [bitmap], with its box in bitmap pixels. */
    suspend fun recognizeLines(bitmap: Bitmap): List<RecognizedLine> {
        val result = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
        return result.textBlocks.flatMap { block ->
            block.lines.map { RecognizedLine(it.text, it.boundingBox) }
        }
    }

    /** All the text in [bitmap] as one string (lines separated by newlines). */
    suspend fun recognizeText(bitmap: Bitmap): String =
        recognizer.process(InputImage.fromBitmap(bitmap, 0)).await().text
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
