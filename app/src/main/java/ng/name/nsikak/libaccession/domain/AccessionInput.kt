package ng.name.nsikak.libaccession.domain

/** The server strips all whitespace and upper-cases accession numbers; do the same so the form shows what will be stored. */
fun normalizeAccessionInput(raw: String): String = raw.filterNot { it.isWhitespace() }.uppercase()

/** What the accession box shows: upper-case, fixed parts of the format filled in (see AccessionFormat.autocorrect). */
fun correctAccessionInput(raw: String): String = AccessionFormat.autocorrect(normalizeAccessionInput(raw))

/** Hard server rules for an accession number: required, at most 40 characters, no "/". Null = fine. */
fun accessionProblem(accession: String): String? = when {
    accession.isEmpty() -> "Enter an accession number."
    accession.length > 40 -> "An accession number can have at most 40 characters."
    '/' in accession -> "An accession number can't contain “/”."
    else -> null
}
