package ng.name.nsikak.libaccession.domain

/** A validated scan. [isbn13] is the canonical form; [original] is what was printed on the book. */
data class ScannedIsbn(val isbn13: String, val original: String) {
    val wasIsbn10: Boolean get() = original != isbn13
}

object Isbn {
    private fun Char.isAsciiDigit() = this in '0'..'9'

    /** Removes hyphens and spaces, upper-cases a trailing x. */
    fun clean(raw: String): String = raw.filterNot { it == '-' || it.isWhitespace() }.uppercase()

    /** Check digit for the first 12 digits of an EAN-13: weights 1,3,1,3,... */
    private fun ean13CheckDigit(first12: String): Int {
        val sum = first12.mapIndexed { i, c -> (c - '0') * if (i % 2 == 0) 1 else 3 }.sum()
        return (10 - sum % 10) % 10
    }

    /** 13 digits, starts with 978 or 979, correct check digit. */
    fun isValidIsbn13(s: String): Boolean =
        s.length == 13 &&
            s.all { it.isAsciiDigit() } &&
            (s.startsWith("978") || s.startsWith("979")) &&
            ean13CheckDigit(s.substring(0, 12)) == s[12] - '0'

    /** 9 digits + a digit or X; weighted sum (10..1) divisible by 11. */
    fun isValidIsbn10(s: String): Boolean {
        if (s.length != 10) return false
        if (!s.substring(0, 9).all { it.isAsciiDigit() }) return false
        val last = s[9]
        if (!last.isAsciiDigit() && last != 'X') return false
        val digits = s.substring(0, 9).map { it - '0' } + if (last == 'X') 10 else last - '0'
        return digits.mapIndexed { i, d -> (10 - i) * d }.sum() % 11 == 0
    }

    /** Converts a VALID ISBN-10 to ISBN-13 (prefix 978, new check digit). */
    fun toIsbn13(isbn10: String): String {
        val core = "978" + isbn10.substring(0, 9)
        return core + ean13CheckDigit(core)
    }

    /**
     * Finds an ISBN inside text read from a page, e.g. "ISBN 978-0-14-312854-0" or "0-14-312854-1 (pbk.)".
     * Hyphens and spaces are allowed inside the number. Returns null if no valid ISBN is in the text.
     */
    fun findIn(text: String): ScannedIsbn? {
        parse(text)?.let { return it }
        // 1. A number right after an "ISBN" label (ISBN, ISBN-13, ISBN-10, ISBN:): the label's own digits are not part of it.
        for (match in LABELLED.findAll(text)) {
            parse(match.groupValues[1])?.let { return it }
        }
        // 2. Any run of digits/hyphens/spaces; try every 13- and 10-character window inside it.
        for (match in CANDIDATE.findAll(text)) {
            val cleaned = clean(match.value)
            for (length in intArrayOf(13, 10)) {
                for (start in 0..(cleaned.length - length)) {
                    parse(cleaned.substring(start, start + length))?.let { return it }
                }
            }
        }
        return null
    }

    private val LABELLED = Regex("ISBN(?:-?1[03])?\\s*:?\\s*([0-9][0-9Xx\\- ]{8,18}[0-9Xx])", RegexOption.IGNORE_CASE)
    private val CANDIDATE = Regex("[0-9][0-9\\- ]{8,18}[0-9Xx]")

    /** Accepts barcode text that is a valid ISBN-13, valid ISBN-10, 12-digit UPC-A, 9-digit SBN, or extracts ISBN from concatenated add-on barcodes. */
    fun parse(raw: String): ScannedIsbn? {
        val s = clean(raw)
        when {
            isValidIsbn13(s) -> return ScannedIsbn(isbn13 = s, original = s)
            isValidIsbn10(s) -> return ScannedIsbn(isbn13 = toIsbn13(s), original = s)
            s.length == 9 -> {
                val s10 = "0$s"
                if (isValidIsbn10(s10)) return ScannedIsbn(isbn13 = toIsbn13(s10), original = s10)
            }
            s.length == 12 && isValidIsbn13("0$s") -> {
                val ean13 = "0$s"
                return ScannedIsbn(isbn13 = ean13, original = ean13)
            }
        }
        if (s.length > 13) {
            for (length in intArrayOf(13, 10)) {
                for (start in 0..(s.length - length)) {
                    val sub = s.substring(start, start + length)
                    when {
                        length == 13 && isValidIsbn13(sub) -> return ScannedIsbn(isbn13 = sub, original = sub)
                        length == 10 && isValidIsbn10(sub) -> return ScannedIsbn(isbn13 = toIsbn13(sub), original = sub)
                    }
                }
            }
        }
        return null
    }
}

