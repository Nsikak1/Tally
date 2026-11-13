package ng.name.nsikak.libaccession.ui.scanner

import ng.name.nsikak.libaccession.ui.ocr.OcrMode

/**
 * The fields the camera can fill in, in the order they are asked: ISBN (barcode or printed digits),
 * then the title, then the author, then the accession number.
 */
enum class FillStep(val key: String, val label: String, val ocrMode: OcrMode) {
    ISBN("isbn", "ISBN", OcrMode.ISBN),
    TITLE("title", "Title", OcrMode.ANY_TEXT),
    AUTHOR("author", "Author", OcrMode.ANY_TEXT),
    ACCESSION("accession", "Accession number", OcrMode.ACCESSION),
    ;

    companion object {
        /** "isbn,title" -> [ISBN, TITLE]; unknown names are ignored, order is always the canonical one. */
        fun fromKeys(csv: String): List<FillStep> {
            val wanted = csv.split(',').map { it.trim() }.toSet()
            return entries.filter { it.key in wanted }
        }

        fun toKeys(steps: List<FillStep>): String = steps.joinToString(",") { it.key }
    }
}

/** A guided run: the camera asks for one field at a time. [values] holds what has been read so far. */
data class GuidedState(
    val steps: List<FillStep>,
    val index: Int = 0,
    val values: Map<FillStep, String> = emptyMap(),
) {
    val current: FillStep? get() = steps.getOrNull(index)
    val finished: Boolean get() = index >= steps.size
}
