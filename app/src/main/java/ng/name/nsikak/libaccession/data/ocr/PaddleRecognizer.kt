package ng.name.nsikak.libaccession.data.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** One reading of a line of text: the text and the model's average confidence (0..1). */
data class Reading(val text: String, val confidence: Float)

/** ANY = every character the model knows. ACCESSION = A-Z, a-z, 0-9 and the hyphen. ISBN = digits, X and the hyphen. */
enum class Alphabet { ANY, ACCESSION, ISBN }

/**
 * PaddleOCR PP-OCRv6 text RECOGNITION (one line of text in, a string out), run with ONNX Runtime.
 *
 * You draw the box, so no text detection is needed here. The model files are NOT in the source tree:
 * scripts/fetch_models.sh downloads them into app/src/main/assets/ppocr/<tier>/ (rec.onnx, dict.json, MODEL.txt),
 * one folder per model size (small, medium, ...). Settings chooses which one is used ([useModel]).
 * Without any installed model [isInstalled] is false and the app falls back to ML Kit.
 *
 * Model contract (PP-OCRv6 rec, CTC head): input [1, 3, 48, W] float32, BGR, (x/255 - 0.5) / 0.5,
 * W dynamic; output [1, T, C] softmax with class 0 = CTC blank, 1..N = dictionary, last = space.
 */
class PaddleRecognizer(private val context: Context) {
    private class Loaded(
        val env: OrtEnvironment,
        val session: OrtSession,
        val inputName: String,
        val table: List<String>,      // class index -> text ("" for the blank)
        val accessionIds: IntArray,   // class indices allowed in ACCESSION mode
        val isbnIds: IntArray,        // class indices allowed in ISBN mode
        val tier: String,
    )

    private val lock = Mutex()
    private var loaded: Loaded? = null

    @Volatile
    var lastError: String? = null
        private set

    private var gaveUp = false

    @Volatile
    private var requestedTier: String = DEFAULT_TIER

    /** The model sizes whose files are inside the app (e.g. ["small", "medium"]). */
    fun installedTiers(): List<String> {
        val names = context.assets.list(ASSET_DIR) ?: return emptyList()
        return names.filter { name ->
            val files = context.assets.list("$ASSET_DIR/$name") ?: emptyArray()
            MODEL_FILE in files && DICT_FILE in files
        }.sorted()
    }

    /** The size that will really be used: the one chosen in Settings if installed, else any installed one. */
    fun effectiveTier(): String? {
        val installed = installedTiers()
        return when {
            requestedTier in installed -> requestedTier
            else -> installed.firstOrNull()
        }
    }

    /** Called when the user picks a model size in Settings. The model is (re)loaded on the next read. */
    fun useModel(tier: String) {
        if (tier == requestedTier) return
        requestedTier = tier
        gaveUp = false
        lastError = null
    }

    fun isInstalled(): Boolean = effectiveTier() != null

    /** "PP-OCRv6 small" or similar, from MODEL.txt written by the download script. */
    fun modelName(): String {
        val tier = effectiveTier() ?: return "PP-OCRv6"
        return try {
            context.assets.open("$ASSET_DIR/$tier/MODEL.txt").bufferedReader().use { it.readText().trim() }
        } catch (e: Exception) {
            "PP-OCRv6 $tier"
        }
    }

    suspend fun warmUp() {
        withContext(Dispatchers.Default) { load() }
    }

    /**
     * Reads one line of text. The image is run through the network ONCE and decoded once per
     * alphabet in [alphabets]. Returns null if the model is missing or failed (see [lastError]).
     */
    suspend fun read(bitmap: Bitmap, alphabets: List<Alphabet>): List<Reading>? =
        withContext(Dispatchers.Default) {
            val model = load() ?: return@withContext null
            try {
                infer(model, bitmap, alphabets)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                lastError = e.message ?: e::class.java.simpleName
                null
            }
        }

    private suspend fun load(): Loaded? =
        lock.withLock {
            val tier = effectiveTier()
            val current = loaded
            if (current != null) {
                if (current.tier == tier) return current
                // The user switched model size: let go of the old one.
                try {
                    current.session.close()
                } catch (e: Exception) {
                    // already closed
                }
                loaded = null
            }
            if (gaveUp || tier == null) return null
            try {
                val dir = File(context.filesDir, "ppocr/$tier").also { it.mkdirs() }
                val modelFile = File(dir, MODEL_FILE)
                val stampFile = File(dir, "stamp")
                @Suppress("DEPRECATION")
                val stamp = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
                if (!modelFile.exists() || !stampFile.exists() || stampFile.readText() != stamp) {
                    context.assets.open("$ASSET_DIR/$tier/$MODEL_FILE").use { input ->
                        modelFile.outputStream().use { input.copyTo(it) }
                    }
                    stampFile.writeText(stamp)
                }

                val dictJson = context.assets.open("$ASSET_DIR/$tier/$DICT_FILE").bufferedReader().use { it.readText() }
                val chars = Json.decodeFromString(ListSerializer(String.serializer()), dictJson)
                // class 0 = blank, then the dictionary, then a space as the last class
                val table = buildList {
                    add("")
                    addAll(chars)
                    add(" ")
                }

                val env = OrtEnvironment.getEnvironment()
                val options = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                }
                val session = env.createSession(modelFile.absolutePath, options)
                val inputName = session.inputNames.first()

                val allowed = HashSet<String>().apply {
                    ('A'..'Z').forEach { add(it.toString()) }
                    ('a'..'z').forEach { add(it.toString()) }
                    ('0'..'9').forEach { add(it.toString()) }
                    add("-")
                }
                val accessionIds = table.indices.filter { it != 0 && table[it] in allowed }.toIntArray()
                val isbnChars = setOf("0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "X", "x", "-")
                val isbnIds = table.indices.filter { it != 0 && table[it] in isbnChars }.toIntArray()

                Loaded(env, session, inputName, table, accessionIds, isbnIds, tier).also {
                    loaded = it
                    lastError = null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                gaveUp = true
                lastError = e.message ?: e::class.java.simpleName
                null
            }
        }

    private fun infer(m: Loaded, bitmap: Bitmap, alphabets: List<Alphabet>): List<Reading> {
        val (data, width) = toInput(bitmap)
        val buffer = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        buffer.put(data)
        buffer.rewind()

        OnnxTensor.createTensor(m.env, buffer, longArrayOf(1, 3, REC_H.toLong(), width.toLong())).use { input ->
            m.session.run(mapOf(m.inputName to input)).use { result ->
                val out = result.get(0) as OnnxTensor
                val shape = out.info.shape            // [1, T, C]
                val steps = shape[1].toInt()
                val classes = shape[2].toInt()
                check(classes == m.table.size) {
                    "dict.json has ${m.table.size - 2} characters but the model has $classes classes: " +
                        "re-run scripts/fetch_models.sh so both files come from the same model."
                }
                val probs = out.floatBuffer
                return alphabets.map { alphabet ->
                    val allowed = when (alphabet) {
                        Alphabet.ACCESSION -> m.accessionIds
                        Alphabet.ISBN -> m.isbnIds
                        Alphabet.ANY -> null
                    }
                    decode(probs, steps, classes, m.table, allowed)
                }
            }
        }
    }

    /** Greedy CTC decoding: best class per time step, merge repeats, drop blanks. */
    private fun decode(probs: FloatBuffer, steps: Int, classes: Int, table: List<String>, allowed: IntArray?): Reading {
        val text = StringBuilder()
        var previous = 0
        var sum = 0f
        var kept = 0
        for (t in 0 until steps) {
            val base = t * classes
            var best = 0
            var bestP = probs.get(base)               // start from the blank
            if (allowed == null) {
                for (c in 1 until classes) {
                    val p = probs.get(base + c)
                    if (p > bestP) { bestP = p; best = c }
                }
            } else {
                for (c in allowed) {
                    val p = probs.get(base + c)
                    if (p > bestP) { bestP = p; best = c }
                }
            }
            if (best != 0 && best != previous) {
                text.append(table[best])
                sum += bestP
                kept++
            }
            previous = best
        }
        return Reading(text.toString(), if (kept > 0) sum / kept else 0f)
    }

    /** Bitmap -> CHW float array (B, G, R planes), height 48, zero-padded on the right. */
    private fun toInput(src: Bitmap): Pair<FloatArray, Int> {
        val ratio = src.width.toFloat() / src.height.toFloat()
        val wanted = ceil(REC_H * ratio).toInt().coerceAtLeast(8)
        val width = max(MIN_W, wanted).coerceAtMost(MAX_W)
        val resizedW = min(width, wanted)
        val scaled = resize(src, resizedW, REC_H)

        val px = IntArray(resizedW * REC_H)
        scaled.getPixels(px, 0, resizedW, 0, 0, resizedW, REC_H)

        val plane = REC_H * width
        val data = FloatArray(3 * plane)              // zeros = the padding, as in PaddleOCR
        for (y in 0 until REC_H) {
            for (x in 0 until resizedW) {
                val p = px[y * resizedW + x]
                val i = y * width + x
                data[i] = ((p and 0xFF) / 255f - 0.5f) / 0.5f
                data[plane + i] = (((p shr 8) and 0xFF) / 255f - 0.5f) / 0.5f
                data[2 * plane + i] = (((p shr 16) and 0xFF) / 255f - 0.5f) / 0.5f
            }
        }
        return data to width
    }

    /** Halves the image while it is much bigger than the target (cheap anti-aliasing), then scales. */
    private fun resize(src: Bitmap, w: Int, h: Int): Bitmap {
        var current = src
        while (current.height >= h * 2 && current.width >= w * 2) {
            current = Bitmap.createScaledBitmap(current, current.width / 2, current.height / 2, true)
        }
        return Bitmap.createScaledBitmap(current, w, h, true)
    }

    private companion object {
        const val ASSET_DIR = "ppocr"
        const val DEFAULT_TIER = "small"
        const val MODEL_FILE = "rec.onnx"
        const val DICT_FILE = "dict.json"
        const val REC_H = 48
        const val MIN_W = 320
        const val MAX_W = 3200
    }
}
