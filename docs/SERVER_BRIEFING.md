# Library server briefing (reference only; the server is not changed by the app project)

Copied from the user's briefing of 2026-10-30. Tally is the Android client.

## Basics
- Base URL: http://192.168.1.117:4321 (also http://school.local:4321 via mDNS). Plain HTTP on the school LAN. The base URL must be user-configurable (https tunnel domains are also used).
- JSON everywhere. Errors are `{"detail": "..."}`; 422 validation errors carry a list in `detail`. Docs at `/docs`, spec at `/openapi.json`.
- ISBNs are always JSON strings. Work ids are integers.
- Auth: reads are open. POST/PATCH/DELETE on `/holdings` need header `X-API-Key: <key>`, otherwise 401. The key is entered in app Settings.
- The server is small (3.4 GB RAM) and slow when cold: use a 15 s read timeout.

## Two data sets
1. CATALOG (read-only): Open Library dump of 2026-09-30, all languages, ~41.7M works, ~34.7M editions. A "work" groups all editions/ISBNs of one book.
2. HOLDINGS (read-write): the library's own physical copies, keyed by accession number.

## Catalog endpoints
- `GET /health` -> `{ok, catalog: bool, catalog_built: ISO time|null, holdings: int, writes_need_key: bool}`
- `GET /isbn/{isbn}` (ISBN-10 or -13, hyphens OK; 10 converted to 13; the check digit is NOT verified server-side: validate on device). 400 invalid, 404 not in catalog.
  -> `{isbn, title, author, work: int|null, editions: int, year: int|null, lang: "eng"|null, dewey: str|null, cover_url: str|null}`. `author` may be "A; B" (up to 3). `cover_url` is on covers.openlibrary.org (needs internet): placeholder on failure.
- `GET /search?q=&title=&author=&limit=20` (1-50). Work-level search. All words must match, the last word is a prefix. Filler words (the, a, an, of, and, in, to, for, on) ignored.
  400 unless at least one word has 3+ letters. 503 "query too broad" if over 4 s (add letters; don't auto-retry).
  -> `{match: "all"|"any", results: [{work, title, authors: str|null, editions: int, sample_isbns: [up to 3]}]}`. No typo tolerance.
  Results can include works with NO ISBN edition (editions 0, sample_isbns []): the user can pick one and save a holding with `work` only.
- `GET /work/{id}` -> `{work, title, authors, editions: [{isbn, title, year, lang, cover_url}]}` (max 50, newest first). 404 if unknown.
- `GET /same/{a}/{b}` (two ISBNs, both in catalog: 400/404) -> `{same: bool, verdict: "yes"|"maybe"|"no", confidence: 0-1, reason: "same work"|"title similarity"}`. On "maybe", ask the user.
- `GET /scan/{isbn}` (call right after a scan) -> `{book: <same shape as /isbn>|null, copies: [holding + same_edition: bool]}`. copies = every holding with that ISBN or the same work.

## Holdings endpoints
Holding: `{id, accession, isbn: str|null, work: int|null, title, author, shelf, status: "in"|"out"|"lost"|"repair", note, added_at, updated_at}`.
Timestamps are UTC "YYYY-MM-DD HH:MM:SS". Any field except id/accession/status/timestamps can be null.
Accession numbers: the server strips all whitespace and upper-cases them (show the returned value). They must not contain "/".
- `POST /holdings` (key) body `{accession (required, max 40), isbn?, work?, title?, author?, shelf?, status? default "in", note?, + the detail fields below}` -> 201 holding. 409 accession exists. 400 bad ISBN. If isbn is given, missing title/author/work AND pub_date/language/dewey are filled from the catalog (or a saved online lookup); an ISBN not in the catalog is still saved.
- DETAIL FIELDS (v0.8, all optional, all returned on every holding, null when unknown): `subtitle`, `publisher`, `pub_date` ("2008" | "2008-05" | "2008-05-01": date of THIS edition), `edition` ("2nd edition"), `language` (3 lowercase letters, ISO 639-2: "eng"), `country` (2 capital letters, ISO 3166: "GB"), `pages` (1-20000), `format` ("paperback"), `dewey`, `description` (max 5000), `list_price` and `purchase_price` (numbers >= 0, in `currency`), `currency` (3 capital letters, "NGN"), `tags` (list of lowercase strings, max 50, each max 40; on PATCH the list REPLACES the old one, `[]` or null clears it, leaving it out keeps it), `info_sources` (object {field: service name}: who supplied what; PATCH replaces the whole object). Bad values -> 422. `language`/`country`/`currency` are case-corrected for you.
- `GET /holdings/{accession}` -> holding | 404
- `PATCH /holdings/{accession}` (key) send only changed fields among isbn, work, title, author, shelf, status, note and the detail fields. null clears a field (except status). No catalog auto-fill on PATCH. -> holding. 400 if empty or invalid. 404.
- `DELETE /holdings/{accession}` (key) -> 204 | 404
- `GET /holdings?q=&status=&shelf=&tag=&language=&publisher=&limit=50 (1-200)&offset=0` q: every word must appear as a substring of title/subtitle/author/publisher/accession. shelf exact; tag exact (case-corrected); language exact (3 letters); publisher substring. Newest first.
- `GET /tags?limit=200` -> `[{tag, copies}]`, most used first. -> `{offset, results: [holding]}`. (No date filter.)

## Not on the server
Loans/borrowers/due dates, users/login, shelf list or stats endpoints, search of individual editions, typo-tolerant search, catalog writes, HTTPS, handwriting/accession OCR (client side).
Catalog quirks: junk ISBNs exist (e.g. 9780000000000); title/author/year/lang/dewey/cover can be null; non-Latin titles occur.
