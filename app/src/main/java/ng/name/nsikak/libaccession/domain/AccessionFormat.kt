package ng.name.nsikak.libaccession.domain

/**
 * The school's accession number formats, set in Settings (one format per line). Format language:
 *   X   = one digit            ILS-XXXX   matches ILS-0123
 *   @   = one letter           @@-XXX     matches AB-123
 *   X+  = one or more digits   LIB-X+     matches LIB-7 and LIB-00123 (must be last)
 *   anything else is written exactly as it is (letters are matched case-insensitively).
 * Several formats can be active at once; a number is fine if it matches ANY of them.
 */
object AccessionFormat {
    /** The school's two formats, most specific first (typed text is matched against them in this order). */
    val DEFAULTS = listOf("ILS-SR-XX-X+", "ILS-X+")

    @Volatile
    var patterns: List<String> = DEFAULTS

    private enum class Kind { DIGIT, LETTER, LITERAL }

    private data class Slot(val kind: Kind, val ch: Char = ' ', val many: Boolean = false)

    private fun compile(pattern: String): List<Slot> {
        val slots = ArrayList<Slot>()
        for (c in pattern.trim().uppercase()) {
            when {
                c == 'X' -> slots += Slot(Kind.DIGIT)
                c == '@' -> slots += Slot(Kind.LETTER)
                c == '+' && slots.isNotEmpty() && slots.last().kind == Kind.DIGIT -> slots[slots.lastIndex] = slots.last().copy(many = true)
                else -> slots += Slot(Kind.LITERAL, c)
            }
        }
        return slots
    }

    private fun slotsList(): List<List<Slot>> = patterns.map(::compile).filter { it.isNotEmpty() }

    /** True if [text] (already upper-case, hyphen where the format has one) matches any format exactly. */
    fun isValid(text: String): Boolean = slotsList().any { matches(it, text) }

    private fun matches(slots: List<Slot>, text: String): Boolean {
        var pos = 0
        for (slot in slots) {
            when (slot.kind) {
                Kind.LITERAL -> {
                    if (pos >= text.length || text[pos].uppercaseChar() != slot.ch) return false
                    pos++
                }
                Kind.LETTER -> {
                    if (pos >= text.length || !text[pos].isLetter()) return false
                    pos++
                }
                Kind.DIGIT -> {
                    if (pos >= text.length || text[pos] !in '0'..'9') return false
                    pos++
                    if (slot.many) while (pos < text.length && text[pos] in '0'..'9') pos++
                }
            }
        }
        return pos == text.length
    }

    /** A sample number for each format, e.g. "ILS-0001". */
    val examples: List<String>
        get() = slotsList().map { slots ->
            val lastDigit = slots.indexOfLast { it.kind == Kind.DIGIT }
            slots.mapIndexed { i, s ->
                when (s.kind) {
                    Kind.LITERAL -> s.ch
                    Kind.LETTER -> 'A'
                    Kind.DIGIT -> if (i == lastDigit) '1' else '0'
                }
            }.joinToString("")
        }

    /** For messages: "ILS-XXXX or LIB-X+ (X = a digit)". */
    fun describe(): String = patterns.joinToString(" or ") + "  (X = a digit)"

    // Characters handwriting/OCR commonly confuses, by the kind of character that is EXPECTED.
    private val AS_DIGIT = mapOf(
        'O' to '0', 'Q' to '0', 'D' to '0',
        'L' to '1', 'I' to '1', '|' to '1', '!' to '1',
        'S' to '5', 'B' to '8', 'Z' to '2', 'G' to '6',
    )
    private val AS_LETTER = mapOf('0' to 'O', '1' to 'I', '5' to 'S', '8' to 'B', '2' to 'Z', '6' to 'G', '4' to 'A')

    /**
     * Cleans raw OCR text into the best guess for the configured formats: spaces and dashes are ignored, and each
     * position is forced to the kind of character the format expects there (O -> 0 in a digit position,
     * 1 -> I in a letter position ...). The first format the text can be fitted to wins. If none fits, the text
     * is returned upper-cased without spaces. The result is NOT guaranteed valid; check with [isValid].
     */
    fun normalize(raw: String): String {
        val upper = raw.uppercase()
        val tidy = upper.trim().replace(Regex("[\\u2010-\\u2015\\u2212_~\\s]+"), "-").trim('-')
        if (isValid(tidy)) return tidy
        val alnum = upper.filter { it.isLetterOrDigit() || it == '|' || it == '!' }
        for (slots in slotsList()) {
            snap(alnum, slots)?.let { return it }
        }
        // Only the number was read ("0123" or "O123"): put the format's fixed start in front ("ILS-0123").
        // snap() checks the length and every character, so text that is not a number still fails.
        if (alnum.isNotEmpty()) {
            // Several formats could take a bare number: the one with the shortest fixed start is the plain one.
            for (slots in slotsList().sortedBy { literalPrefix(it).length }) {
                val prefix = literalPrefix(slots)
                if (prefix.isNotEmpty()) snap(prefix + alnum, slots)?.let { return it }
            }
        }
        return upper.filterNot { it.isWhitespace() }
    }

    /** The letters/digits a format starts with before its first slot, e.g. "ILS" for ILS-XXXX. */
    private fun literalPrefix(slots: List<Slot>): String =
        slots.takeWhile { it.kind == Kind.LITERAL }.filter { it.ch.isLetterOrDigit() }.joinToString("") { it.ch.toString() }

    /**
     * Live correction for a box the user types in (or edits) while typing: always upper-case, spaces dropped,
     * the fixed parts of the format are written for you ("ils0123" and "ils-12" become "ILS-0123" and "ILS-12",
     * typing just "123" gives "ILS-123"), O/I/S... typed where a digit belongs become digits.
     * A partly typed number is fine (no trailing dash is added, so Backspace always works). Text that fits no
     * format is only upper-cased, so nothing the person types is thrown away.
     */
    fun autocorrect(raw: String): String {
        val upper = raw.uppercase().filterNot { it.isWhitespace() }
        val alnum = upper.filter { it.isLetterOrDigit() }
        if (alnum.isEmpty()) return upper
        val all = slotsList()
        for (slots in all) fitTyped(alnum, slots)?.let { return it }
        if (alnum.all { it in '0'..'9' }) {
            for (slots in all.sortedBy { literalPrefix(it).length }) {
                val prefix = literalPrefix(slots)
                if (prefix.isNotEmpty()) fitTyped(prefix + alnum, slots)?.let { return it }
            }
        }
        return upper
    }

    /** Fits what was typed to the START of a format (typed fixed letters must be exact; digit/letter slots are forgiving). */
    private fun fitTyped(alnum: String, slots: List<Slot>): String? {
        val out = StringBuilder()
        var pos = 0
        for (slot in slots) {
            if (pos >= alnum.length) break
            when {
                slot.kind == Kind.LITERAL && !slot.ch.isLetterOrDigit() -> out.append(slot.ch)
                slot.kind == Kind.LITERAL -> {
                    if (alnum[pos] != slot.ch) return null
                    out.append(slot.ch)
                    pos++
                }
                slot.kind == Kind.LETTER -> {
                    val c = alnum[pos]
                    val letter = if (c.isLetter()) c else AS_LETTER[c] ?: return null
                    out.append(letter)
                    pos++
                }
                else -> { // DIGIT
                    val count = if (slot.many) alnum.length - pos else 1
                    repeat(count) {
                        val c = alnum[pos]
                        val digit = if (c in '0'..'9') c else AS_DIGIT[c] ?: return null
                        out.append(digit)
                        pos++
                    }
                }
            }
        }
        return if (pos == alnum.length) out.toString() else null
    }

    private fun matchesAs(ch: Char, expected: Char): Boolean = when {
        ch == expected -> true
        expected.isDigit() -> AS_DIGIT[ch] == expected
        else -> AS_LETTER[ch] == expected
    }

    private fun snap(alnum: String, slots: List<Slot>): String? {
        val consuming = slots.count { it.kind != Kind.LITERAL || it.ch.isLetterOrDigit() }
        val hasMany = slots.any { it.many }
        if (hasMany) {
            if (alnum.length < consuming) return null
        } else if (alnum.length != consuming) {
            return null
        }
        val out = StringBuilder()
        var pos = 0
        for (slot in slots) {
            when {
                slot.kind == Kind.LITERAL && !slot.ch.isLetterOrDigit() -> out.append(slot.ch)
                slot.kind == Kind.LITERAL -> {
                    if (pos >= alnum.length || !matchesAs(alnum[pos], slot.ch)) return null
                    out.append(slot.ch)
                    pos++
                }
                slot.kind == Kind.LETTER -> {
                    if (pos >= alnum.length) return null
                    val c = alnum[pos]
                    val letter = if (c.isLetter()) c else AS_LETTER[c] ?: return null
                    out.append(letter)
                    pos++
                }
                else -> { // DIGIT
                    val count = if (slot.many) alnum.length - pos else 1
                    if (count < 1 || pos + count > alnum.length) return null
                    repeat(count) {
                        val c = alnum[pos]
                        val digit = if (c in '0'..'9') c else AS_DIGIT[c] ?: return null
                        out.append(digit)
                        pos++
                    }
                }
            }
        }
        return if (pos == alnum.length) out.toString() else null
    }
}
