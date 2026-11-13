package ng.name.nsikak.libaccession.domain

/**
 * A digits-only accession format turned into something a numeric keypad can fill in.
 *
 *   ILS-X+          prefix "ILS-"      typing 123      gives ILS-123
 *   ILS-SR-XX-X+    prefix "ILS-SR-"   typing 05123    gives ILS-SR-05-123   (two digits, a dash, then the number)
 *
 * The prefix is fixed text shown beside the box; the person types ONLY digits and the dashes inside the number are laid
 * in for them. Formats with letters to type ("@") or text after the last digit are not supported here ([parse] = null).
 */
class AccessionTemplate private constructor(
    /** The format as written in Settings, e.g. "ILS-SR-XX-X+". */
    val pattern: String,
    /** Fixed text before the first digit, e.g. "ILS-SR-". */
    val prefix: String,
    private val groups: List<Group>,
) {
    private data class Group(val separatorBefore: String, val digits: Int, val many: Boolean)

    /** Fewest digits that make a complete number (a "many" group needs at least one). */
    val minDigits: Int = groups.sumOf { if (it.many) 1 else it.digits }

    /** Most digits allowed, or null when the last group takes any number of digits. */
    val maxDigits: Int? = if (groups.any { it.many }) null else groups.sumOf { it.digits }

    /** A line for people: "ILS-SR- then 2 digits, a dash, then the number". */
    val hint: String
        get() = prefix + " then " + groups.joinToString(", ") { g ->
            val what = if (g.many) "the number" else "${g.digits} digit${if (g.digits == 1) "" else "s"}"
            (if (g.separatorBefore.isNotEmpty()) "'${g.separatorBefore}' + " else "") + what
        }

    /** Keeps only digits, and no more than [maxDigits]. */
    fun clean(raw: String): String {
        val digits = raw.filter { it in '0'..'9' }
        return if (maxDigits != null) digits.take(maxDigits) else digits
    }

    fun isComplete(digits: String): Boolean = digits.length >= minDigits && (maxDigits == null || digits.length <= maxDigits)

    /** The typed [digits] with the separators laid in, WITHOUT the prefix: "05123" -> "05-123". No trailing separator. */
    fun layout(digits: String): String = layoutWithOffsets(digits).first

    /** The whole accession number: prefix + [layout]. */
    fun full(digits: String): String = prefix + layout(digits)

    /**
     * The laid-out text and, for each count i of typed digits (0..n), where the cursor sits in that text after the
     * i-th digit. The keypad box uses it to keep the cursor right while dashes appear.
     */
    fun layoutWithOffsets(digits: String): Pair<String, IntArray> {
        val out = StringBuilder()
        val offsets = IntArray(digits.length + 1)
        var pos = 0
        for (g in groups) {
            val take = if (g.many) digits.length - pos else minOf(g.digits, digits.length - pos)
            if (take <= 0) break
            out.append(g.separatorBefore)
            repeat(take) {
                out.append(digits[pos])
                pos++
                offsets[pos] = out.length
            }
        }
        return out.toString() to offsets
    }

    companion object {
        /** null when [pattern] cannot be typed on a numeric keypad. */
        fun parse(pattern: String): AccessionTemplate? {
            val p = pattern.trim().uppercase()
            val prefix = StringBuilder()
            val groups = ArrayList<Group>()
            val separator = StringBuilder()
            var seenDigit = false
            var i = 0
            while (i < p.length) {
                val c = p[i]
                when {
                    c == '@' -> return null
                    c == 'X' -> {
                        seenDigit = true
                        var n = 0
                        while (i < p.length && p[i] == 'X') { n++; i++ }
                        val many = i < p.length && p[i] == '+'
                        if (many) i++
                        groups += Group(separator.toString(), n, many)
                        separator.setLength(0)
                        continue
                    }
                    c == '+' -> return null
                    !seenDigit -> prefix.append(c)
                    else -> separator.append(c)
                }
                i++
            }
            if (!seenDigit || separator.isNotEmpty()) return null // no digits to type, or text after the last digit
            if (groups.dropLast(1).any { it.many }) return null // "X+" is only allowed last
            // The first group has no separator of its own: the prefix already ends where it starts.
            if (groups.first().separatorBefore.isNotEmpty()) return null
            return AccessionTemplate(p, prefix.toString(), groups)
        }
    }
}
