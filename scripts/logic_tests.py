#!/usr/bin/env python3
"""Python ports of the pure Kotlin logic (the sandbox can't compile Android).
Keep each port line-for-line with its Kotlin original. Run: python3 scripts/logic_tests.py"""
from datetime import datetime, timezone, timedelta
from zoneinfo import ZoneInfo
import math

# ---- domain/TapZoom.kt ----
def tap_zoom(current, mn, mx, wanted, tx, ty, vw, vh, margin=0.12):
    if vw <= 0 or vh <= 0 or current <= 0:
        return (current, tx, ty)
    target = min(max(wanted, mn), mx)
    zoomed_in = current > mn * 1.05
    if zoomed_in and current >= target * 0.9:
        nz = mn
    else:
        dx, dy = abs(tx - vw / 2), abs(ty - vh / 2)
        lx = float("inf") if dx < 1 else (1 - margin) * (vw / 2) / dx
        ly = float("inf") if dy < 1 else (1 - margin) * (vh / 2) / dy
        capped = min(target, current * min(lx, ly), mx)
        nz = current if capped < current * 1.1 else capped
    f = nz / current
    return (nz, vw / 2 + (tx - vw / 2) * f, vh / 2 + (ty - vh / 2) * f)

# ---- domain/TapBox.kt ----
def tap_box(tx, ty, vw, vh, iw, ih, wf=0.5, hf=0.14):
    s = max(vw / iw, vh / ih)
    ox, oy = (iw * s - vw) / 2, (ih * s - vh) / 2
    cx, cy = (tx + ox) / s, (ty + oy) / s
    bw = min(vw * wf / s, iw)
    bh = min(min(vw, vh) * hf / s, ih)
    left = min(max(cx - bw / 2, 0), iw - bw)
    top = min(max(cy - bh / 2, 0), ih - bh)
    return (left, top, left + bw, top + bh)

# ---- domain/Period.kt ----
DAYS = {"TODAY": 0, "THREE_DAYS": 2, "WEEK": 6, "MONTH": 29, "ALL": None}
def cutoff_utc(period, now_utc, zone):
    back = DAYS[period]
    if back is None:
        return None
    local = now_utc.astimezone(zone)
    start = datetime(local.year, local.month, local.day, tzinfo=zone) - timedelta(days=back)
    # (timedelta on an aware datetime keeps wall-clock, like LocalDate.minusDays + atStartOfDay)
    return start.astimezone(timezone.utc).strftime("%Y-%m-%d %H:%M:%S")

# ---- data/repo/HoldingsRepository.kt: splitSince + paging loop ----
def split_since(page, cutoff):
    if cutoff is None:
        return page, False
    kept = []
    for it in page:
        if it["added_at"] < cutoff:
            return kept, True
        kept.append(it)
    return kept, False

def list_since(all_items, cutoff, max_items, page=200):
    out, offset, calls = [], 0, 0
    while True:
        chunk = all_items[offset:offset + page]; calls += 1
        kept, older = split_since(chunk, cutoff)
        out += kept
        if older or len(chunk) < page:
            return out, False, calls
        if len(out) >= max_items:
            return out[:max_items], True, calls
        offset += len(chunk)

# ---- ui/holding edit: PATCH diff ----
def build_changes(form, orig, parsed_isbn13, work_for_isbn):
    ch = {}
    def diff(key, new, old):
        n = new.strip() or None
        if n != old:
            ch[key] = n
    diff("title", form["title"], orig["title"]); diff("author", form["author"], orig["author"])
    diff("shelf", form["shelf"], orig["shelf"]); diff("note", form["note"], orig["note"])
    if parsed_isbn13 != orig["isbn"]:
        ch["isbn"] = parsed_isbn13
        ch["work"] = work_for_isbn
    if form["status"] != orig["status"]:
        ch["status"] = form["status"]
    return ch

# ---- domain/AccessionInput.kt ----
def norm_acc(raw): return "".join(raw.split()).upper()
def acc_problem(a):
    if not a: return "empty"
    if len(a) > 40: return "long"
    if "/" in a: return "slash"
    return None


# ---- domain/Isbn.kt: clean / validate / findIn (ported line for line) ----
import re, json
def clean(raw): return "".join(c for c in raw if c != "-" and not c.isspace()).upper()
def ean13_check(first12): return (10 - sum(int(c) * (1 if i % 2 == 0 else 3) for i, c in enumerate(first12)) % 10) % 10
def valid13(s): return len(s) == 13 and s.isdigit() and s[:3] in ("978", "979") and ean13_check(s[:12]) == int(s[12])
def valid10(s):
    if len(s) != 10 or not s[:9].isdigit() or not (s[9].isdigit() or s[9] == "X"): return False
    d = [int(c) for c in s[:9]] + [10 if s[9] == "X" else int(s[9])]
    return sum((10 - i) * x for i, x in enumerate(d)) % 11 == 0
def to13(s10):
    core = "978" + s10[:9]; return core + str(ean13_check(core))
def parse(raw):
    s = clean(raw)
    if valid13(s): return (s, s)
    if valid10(s): return (to13(s), s)
    return None
RULES = {"accept13": True, "accept10": True}
def parse_typed(raw):
    f = parse(raw)
    if f is None: return None
    was10 = f[0] != f[1]
    return f if (RULES["accept10"] if was10 else RULES["accept13"]) else None
LABELLED = re.compile(r"ISBN(?:-?1[03])?\s*:?\s*([0-9][0-9Xx\- ]{8,18}[0-9Xx])", re.I)
CAND = re.compile(r"[0-9][0-9\- ]{8,18}[0-9Xx]")
def find_in(text):
    f = parse_typed(text)
    if f: return f
    for m in LABELLED.finditer(text):
        f = parse_typed(m.group(1))
        if f: return f
    for m in CAND.finditer(text):
        c = clean(m.group(0))
        for length in (13, 10):
            for st in range(0, len(c) - length + 1):
                f = parse_typed(c[st:st + length])
                if f: return f
    return None

# ---- ui/scanner/GuidedFill.kt ----
ORDER = ["isbn", "title", "author", "accession"]
def from_keys(csv):
    wanted = {k.strip() for k in csv.split(",")}
    return [k for k in ORDER if k in wanted]

# ---- data/lookup parsers (shape of the real JSON as documented; samples written by hand) ----
def parse_google(body, isbn13):
    root = json.loads(body)
    vols = [v for v in root.get("items", []) if isinstance(v, dict)]
    if not vols: return None
    def has(v): return any(i.get("identifier") == isbn13 for i in v.get("volumeInfo", {}).get("industryIdentifiers", []))
    pick = next((v for v in vols if has(v)), vols[0])
    info = pick.get("volumeInfo")
    if not info or not info.get("title"): return None
    title = info["title"] + (": " + info["subtitle"] if info.get("subtitle") else "")
    authors = "; ".join(info.get("authors", [])) or None
    year = None
    pd = (info.get("publishedDate") or "")[:4]
    if pd.isdigit(): year = int(pd)
    cover = (info.get("imageLinks", {}).get("thumbnail") or info.get("imageLinks", {}).get("smallThumbnail"))
    if cover: cover = cover.replace("http://", "https://", 1)
    return {"title": title, "author": authors, "year": year, "lang": info.get("language"), "cover": cover}
def parse_ol(body, isbn13):
    d = json.loads(body).get("ISBN:" + isbn13)
    if not isinstance(d, dict) or not d.get("title"): return None
    title = d["title"] + (": " + d["subtitle"] if d.get("subtitle") else "")
    authors = "; ".join(a["name"] for a in d.get("authors", []) if a.get("name")) or None
    m = re.search(r"\d{4}", d.get("publish_date", "") or "")
    c = d.get("cover") or {}
    return {"title": title, "author": authors, "year": int(m.group(0)) if m else None, "cover": c.get("medium") or c.get("large") or c.get("small")}

def test_isbn_and_providers():
    assert find_in("ISBN 978-0-14-312854-0") == ("9780143128540", "9780143128540") or find_in("ISBN 978-0-14-312854-0") is None
    # build known-valid numbers from the algorithm itself (no hand-typed check digits)
    core = "978014312854"; good13 = core + str(ean13_check(core))
    pretty13 = f"{good13[:3]}-{good13[3]}-{good13[4:6]}-{good13[6:12]}-{good13[12]}"
    assert find_in("ISBN " + pretty13)[0] == good13
    assert find_in("First published 2004 ISBN: " + pretty13 + " (pbk.)")[0] == good13
    s10 = "014312854"; chk = (11 - sum((10 - i) * int(c) for i, c in enumerate(s10)) % 11) % 11
    good10 = s10 + ("X" if chk == 10 else str(chk)); assert valid10(good10)
    f = find_in("ISBN-10 " + good10[0] + "-" + good10[1:3] + "-" + good10[3:9] + "-" + good10[9])
    assert f == (to13(good10), good10) and f[0] != f[1]
    assert find_in("page 123 of 456, year 2024") is None
    bad = good13[:-1] + str((int(good13[-1]) + 1) % 10)
    assert find_in("ISBN " + bad) is None
    # settings rules apply to typed / OCR text
    RULES["accept10"] = False
    assert find_in("ISBN " + good10) is None and find_in(good13)[0] == good13
    RULES["accept10"] = True; RULES["accept13"] = False
    assert find_in(good13) is None and find_in(good10)[0] == to13(good10)
    RULES["accept13"] = True
    assert from_keys("accession,title,isbn") == ["isbn", "title", "accession"] and from_keys("zzz") == []

    g = json.dumps({"totalItems": 1, "items": [{"volumeInfo": {"title": "Things Fall Apart", "subtitle": "A Novel", "authors": ["Chinua Achebe"], "publishedDate": "1994-09-01", "language": "en", "imageLinks": {"thumbnail": "http://books.google.com/x?id=1"}, "industryIdentifiers": [{"type": "ISBN_13", "identifier": "9780385474542"}]}}]})
    r = parse_google(g, "9780385474542")
    assert r == {"title": "Things Fall Apart: A Novel", "author": "Chinua Achebe", "year": 1994, "lang": "en", "cover": "https://books.google.com/x?id=1"}
    assert parse_google(json.dumps({"totalItems": 0}), "9780385474542") is None
    o = json.dumps({"ISBN:9780385474542": {"title": "Things fall apart", "authors": [{"name": "Chinua Achebe"}, {"name": "X Y"}], "publish_date": "March 1, 1994", "cover": {"medium": "https://covers.openlibrary.org/b/id/1-M.jpg"}}})
    assert parse_ol(o, "9780385474542") == {"title": "Things fall apart", "author": "Chinua Achebe; X Y", "year": 1994, "cover": "https://covers.openlibrary.org/b/id/1-M.jpg"}
    assert parse_ol("{}", "9780385474542") is None


# ---- domain/AccessionFormat.kt (formats from Settings: X digit, @ letter, X+ digits, rest literal) ----
AS_DIGIT_MAP = {"O": "0", "Q": "0", "D": "0", "L": "1", "I": "1", "|": "1", "!": "1", "S": "5", "B": "8", "Z": "2", "G": "6"}
AS_LETTER_MAP = {"0": "O", "1": "I", "5": "S", "8": "B", "2": "Z", "6": "G", "4": "A"}
def acc_compile(pattern):
    slots = []
    for c in pattern.strip().upper():
        if c == "X": slots.append(["D", " ", False])
        elif c == "@": slots.append(["L", " ", False])
        elif c == "+" and slots and slots[-1][0] == "D": slots[-1][2] = True
        else: slots.append(["C", c, False])
    return slots
def acc_matches(slots, text):
    pos = 0
    for kind, ch, many in slots:
        if kind == "C":
            if pos >= len(text) or text[pos].upper() != ch: return False
            pos += 1
        elif kind == "L":
            if pos >= len(text) or not text[pos].isalpha(): return False
            pos += 1
        else:
            if pos >= len(text) or not ("0" <= text[pos] <= "9"): return False
            pos += 1
            if many:
                while pos < len(text) and "0" <= text[pos] <= "9": pos += 1
    return pos == len(text)
def acc_valid(patterns, text): return any(acc_matches(acc_compile(p), text) for p in patterns if acc_compile(p))
def acc_matches_as(ch, expected):
    if ch == expected: return True
    return (AS_DIGIT_MAP.get(ch) == expected) if expected.isdigit() else (AS_LETTER_MAP.get(ch) == expected)
def acc_snap(alnum, slots):
    consuming = sum(1 for k, ch, m in slots if k != "C" or ch.isalnum())
    has_many = any(m for _, _, m in slots)
    if has_many:
        if len(alnum) < consuming: return None
    elif len(alnum) != consuming: return None
    out, pos = [], 0
    for kind, ch, many in slots:
        if kind == "C" and not ch.isalnum(): out.append(ch)
        elif kind == "C":
            if pos >= len(alnum) or not acc_matches_as(alnum[pos], ch): return None
            out.append(ch); pos += 1
        elif kind == "L":
            if pos >= len(alnum): return None
            c = alnum[pos]; l = c if c.isalpha() else AS_LETTER_MAP.get(c)
            if l is None: return None
            out.append(l); pos += 1
        else:
            count = len(alnum) - pos if many else 1
            if count < 1 or pos + count > len(alnum): return None
            for _ in range(count):
                c = alnum[pos]; d = c if "0" <= c <= "9" else AS_DIGIT_MAP.get(c)
                if d is None: return None
                out.append(d); pos += 1
    return "".join(out) if pos == len(alnum) else None
def acc_normalize(patterns, raw):
    upper = raw.upper()
    tidy = re.sub(r"[\u2010-\u2015\u2212_~\s]+", "-", upper.strip()).strip("-")
    if acc_valid(patterns, tidy): return tidy
    alnum = "".join(c for c in upper if c.isalnum() or c in "|!")
    for p in patterns:
        sl = acc_compile(p)
        if sl:
            r = acc_snap(alnum, sl)
            if r: return r
    if alnum:
        for p in sorted(patterns, key=lambda q: len(acc_literal_prefix(acc_compile(q)))):
            sl = acc_compile(p)
            pre = acc_literal_prefix(sl)
            if sl and pre:
                r = acc_snap(pre + alnum, sl)
                if r: return r
    return "".join(upper.split())
def acc_literal_prefix(slots):
    out = []
    for k, ch, m in slots:
        if k != "C": break
        if ch.isalnum(): out.append(ch)
    return "".join(out)
def acc_fit_typed(alnum, slots):
    out, pos = [], 0
    for kind, ch, many in slots:
        if pos >= len(alnum): break
        if kind == "C" and not ch.isalnum(): out.append(ch)
        elif kind == "C":
            if alnum[pos] != ch: return None
            out.append(ch); pos += 1
        elif kind == "L":
            c = alnum[pos]; l = c if c.isalpha() else AS_LETTER_MAP.get(c)
            if l is None: return None
            out.append(l); pos += 1
        else:
            count = len(alnum) - pos if many else 1
            for _ in range(count):
                c = alnum[pos]; d = c if "0" <= c <= "9" else AS_DIGIT_MAP.get(c)
                if d is None: return None
                out.append(d); pos += 1
    return "".join(out) if pos == len(alnum) else None
def acc_autocorrect(patterns, raw):
    upper = "".join(raw.upper().split())
    alnum = "".join(c for c in upper if c.isalnum())
    if not alnum: return upper
    allp = [acc_compile(p) for p in patterns if acc_compile(p)]
    for sl in allp:
        r = acc_fit_typed(alnum, sl)
        if r is not None: return r
    if alnum.isdigit():
        for sl in sorted(allp, key=lambda q: len(acc_literal_prefix(q))):
            pre = acc_literal_prefix(sl)
            if pre:
                r = acc_fit_typed(pre + alnum, sl)
                if r is not None: return r
    return upper
def acc_examples(patterns):
    out = []
    for p in patterns:
        sl = acc_compile(p)
        last = max((i for i, s in enumerate(sl) if s[0] == "D"), default=-1)
        out.append("".join(s[1] if s[0] == "C" else "A" if s[0] == "L" else ("1" if i == last else "0") for i, s in enumerate(sl)))
    return out
def parse_format_lines(text):
    items = [t.strip().upper() for t in re.split(r"[\n,;]", text)]
    seen = []
    for t in items:
        if t and t not in seen: seen.append(t)
    return seen or ["ILS-XXXX"]

def test_accession():
    P = ["ILS-XXXX"]
    assert acc_valid(P, "ILS-0123") and acc_valid(P, "ils-0123") and not acc_valid(P, "ILS-123") and not acc_valid(P, "ILS-01234")
    assert not acc_valid(P, "LIB-0123") and not acc_valid(P, "ILS0123") and not acc_valid(P, "")
    assert acc_normalize(P, "ILS-0123") == "ILS-0123"
    assert acc_normalize(P, "ils 0123") == "ILS-0123"
    assert acc_normalize(P, "ILS0123") == "ILS-0123"
    assert acc_normalize(P, "IL5-O123") == "ILS-0123"
    assert acc_normalize(P, "1LS-01Z3") == "ILS-0123"
    assert acc_normalize(P, "ILS \u2014 0l23") == "ILS-0123"
    assert acc_normalize(P, "ILS-123") == "ILS-123"          # too short: returned as read, not valid
    assert not acc_valid(P, acc_normalize(P, "ILS-123"))
    assert acc_normalize(P, "hello world") == "HELLOWORLD"
    # two formats at once; variable length
    P2 = ["ILS-XXXX", "LIB-X+"]
    assert acc_valid(P2, "LIB-7") and acc_valid(P2, "LIB-00123") and acc_valid(P2, "ILS-0001") and not acc_valid(P2, "LIB-")
    assert acc_normalize(P2, "L1B 00l23") == "LIB-00123"
    assert acc_normalize(P2, "ILS-0O01") == "ILS-0001"
    assert acc_examples(P2) == ["ILS-0001", "LIB-1"]
    # letters wildcard
    P3 = ["@@-XXX"]
    assert acc_valid(P3, "AB-123") and not acc_valid(P3, "A1-123") and acc_normalize(P3, "4B-I23") == "AB-123"
    assert parse_format_lines(" ils-xxxx \n lib-x+ ,ils-xxxx") == ["ILS-XXXX", "LIB-X+"] and parse_format_lines("  ") == ["ILS-XXXX"]

def test_accession_autocorrect():
    P = ["ILS-XXXX"]
    ac = lambda s, p=P: acc_autocorrect(p, s)
    assert ac("ils-0123") == "ILS-0123" and ac("Ils0123") == "ILS-0123" and ac("ILS 0123") == "ILS-0123"
    assert ac("i") == "I" and ac("il") == "IL" and ac("ils") == "ILS"          # no trailing dash: Backspace works
    assert ac("ILS-") == "ILS" and ac("ils-1") == "ILS-1" and ac("ils-12") == "ILS-12"
    assert ac("ILS-O1l3") == "ILS-0113"                                         # O/l typed in digit slots
    assert ac("123") == "ILS-123" and ac("1") == "ILS-1" and ac("0123") == "ILS-0123"
    assert ac("") == "" and ac("  ") == "" and ac("-") == "-"
    assert ac("ILS-01234") == "ILS-01234"                                       # too long: kept, shown as typed (upper)
    assert ac("hello") == "HELLO"
    # backspace/typing simulation: every prefix of ILS-0123 typed one character at a time
    text = ""
    for ch in "ils0123":
        text = ac(text + ch)
    assert text == "ILS-0123"
    for _ in range(5):
        text = ac(text[:-1])
    assert text == "ILS" or text == "IL", text
    P2 = ["ILS-XXXX", "LIB-X+"]
    assert acc_autocorrect(P2, "lib12") == "LIB-12" and acc_autocorrect(P2, "ils99") == "ILS-99"
    assert acc_autocorrect(P2, "7") == "ILS-7"                                  # digits only: first format
    # OCR: only the number was read
    assert acc_normalize(P, "0123") == "ILS-0123" and acc_normalize(P, "O123") == "ILS-0123"
    assert acc_normalize(P, "123") == "123"                                     # too short: unchanged

# ---- AccessionTemplate (domain/AccessionTemplate.kt) ----
def tpl_parse(pattern):
    p = pattern.strip().upper(); prefix = ""; groups = []; sep = ""; seen = False; i = 0
    while i < len(p):
        c = p[i]
        if c == "@": return None
        if c == "X":
            seen = True; n = 0
            while i < len(p) and p[i] == "X": n += 1; i += 1
            many = i < len(p) and p[i] == "+"
            if many: i += 1
            groups.append((sep, n, many)); sep = ""; continue
        if c == "+": return None
        if not seen: prefix += c
        else: sep += c
        i += 1
    if not seen or sep: return None
    if any(g[2] for g in groups[:-1]): return None
    return {"pattern": p, "prefix": prefix, "groups": groups}
def tpl_min(t): return sum(1 if m else n for _, n, m in t["groups"])
def tpl_max(t): return None if any(m for _, _, m in t["groups"]) else sum(n for _, n, _ in t["groups"])
def tpl_clean(t, raw):
    d = "".join(c for c in raw if "0" <= c <= "9"); mx = tpl_max(t)
    return d if mx is None else d[:mx]
def tpl_complete(t, d): mx = tpl_max(t); return len(d) >= tpl_min(t) and (mx is None or len(d) <= mx)
def tpl_layout(t, d):
    out = ""; offs = [0] * (len(d) + 1); pos = 0
    for sep, n, many in t["groups"]:
        take = len(d) - pos if many else min(n, len(d) - pos)
        if take <= 0: break
        out += sep
        for _ in range(take):
            out += d[pos]; pos += 1; offs[pos] = len(out)
    return out, offs
def tpl_full(t, d): return t["prefix"] + tpl_layout(t, d)[0]

def test_accession_template():
    a, b = tpl_parse("ILS-X+"), tpl_parse("ILS-SR-XX-X+")
    assert a["prefix"] == "ILS-" and b["prefix"] == "ILS-SR-"
    assert (tpl_min(a), tpl_max(a), tpl_min(b), tpl_max(b)) == (1, None, 3, None)
    assert tpl_full(a, "7") == "ILS-7" and tpl_full(a, "00123") == "ILS-00123"
    assert tpl_full(b, "05123") == "ILS-SR-05-123" and tpl_full(b, "05") == "ILS-SR-05" and tpl_full(b, "0") == "ILS-SR-0"
    assert tpl_layout(b, "05123")[0] == "05-123" and tpl_layout(b, "0512")[1] == [0, 1, 2, 4, 5]   # cursor skips the dash
    assert tpl_complete(a, "1") and not tpl_complete(a, "") and tpl_complete(b, "051") and not tpl_complete(b, "05") and not tpl_complete(b, "")
    assert tpl_clean(a, "1a2-3 ") == "123" and tpl_clean(b, "x0-5 12") == "0512"          # pasted junk is stripped
    fixed = tpl_parse("LIB-XXXX")
    assert tpl_max(fixed) == 4 and tpl_clean(fixed, "123456") == "1234" and tpl_complete(fixed, "1234") and not tpl_complete(fixed, "123")
    assert tpl_parse("@@-XX") is None and tpl_parse("ILS-") is None and tpl_parse("X+-ABC") is None and tpl_parse("X+X") is None
    # the typed number must also satisfy the format checker (the form validates with the same patterns)
    for t, d in ((a, "42"), (b, "07991")):
        assert acc_valid(["ILS-X+", "ILS-SR-XX-X+"], tpl_full(t, d)), tpl_full(t, d)
    assert not acc_valid(["ILS-X+", "ILS-SR-XX-X+"], "ILS-SR-5-1") and not acc_valid(["ILS-X+", "ILS-SR-XX-X+"], "ILS-")
    # typed into the plain text box of the copy form (autocorrect), most specific format first
    P = ["ILS-SR-XX-X+", "ILS-X+"]
    assert acc_autocorrect(P, "ils-sr-0512") == "ILS-SR-05-12" and acc_autocorrect(P, "ils0042") == "ILS-0042" and acc_autocorrect(P, "ils-5") == "ILS-5"
    assert acc_autocorrect(P, "123") == "ILS-123" and acc_autocorrect(P, "7") == "ILS-7"      # a bare number: the plain format
    assert acc_normalize(P, "0123") == "ILS-0123"
    assert acc_autocorrect(P, "ILS-SR") == "ILS-SR" and acc_autocorrect(P, "ILS-SR-0") == "ILS-SR-0"


def approx(a, b, eps=1e-3): return all(abs(x - y) < eps for x, y in zip(a, b))

def main():
    # TapZoom: centre tap zooms to the wanted level
    assert approx(tap_zoom(1, 1, 8, 2.5, 540, 600, 1080, 1200), (2.5, 540, 600))
    # off-centre tap: zoom is capped so the spot stays in view (0.88 * 540 / 400 = 1.188 -> 1.188x... )
    z, x, y = tap_zoom(1, 1, 8, 2.5, 940, 600, 1080, 1200)
    assert abs(z - 0.88 * 540 / 400) < 1e-6 and abs(x - (540 + 400 * z)) < 1e-3 and x <= 1080
    # capped below 1.1x current -> no zoom at all, tap stays put
    assert approx(tap_zoom(1, 1, 8, 2.5, 1070, 600, 1080, 1200), (1, 1070, 600))
    # already zoomed in: second double-tap resets to min
    z, x, y = tap_zoom(2.5, 1, 8, 2.5, 700, 800, 1080, 1200)
    assert z == 1 and abs(x - (540 + 160 / 2.5)) < 1e-3
    # max zoom of the camera limits the target
    assert tap_zoom(1, 1, 2, 5, 540, 600, 1080, 1200)[0] == 2
    # pinched to 1.5 then double-tap: zooms in to 2.5
    assert tap_zoom(1.5, 1, 8, 2.5, 540, 600, 1080, 1200)[0] == 2.5
    # property: the mapped point is always inside the view
    for cur in (1, 1.7, 3):
        for tx in range(0, 1081, 90):
            for ty in range(0, 1201, 100):
                z, x, y = tap_zoom(cur, 1, 8, 2.5, tx, ty, 1080, 1200)
                assert z >= 1 and 0 <= x <= 1080 and 0 <= y <= 1200, (cur, tx, ty, z, x, y)

    # TapBox: view 1080x1200 (aspect .9), photo 3000x2250 (4:3, already view-cropped would be 2700x3000)
    b = tap_box(540, 600, 1080, 1200, 2700, 3000)          # same aspect: scale 0.4, no crop
    assert abs((b[0] + b[2]) / 2 - 1350) < 1e-3 and abs((b[1] + b[3]) / 2 - 1500) < 1e-3
    assert abs((b[2] - b[0]) - 1080 * .5 / 0.4) < 1e-3 and abs((b[3] - b[1]) - 1080 * .14 / 0.4) < 1e-3
    # photo wider than the view: the sides are cropped away by FILL_CENTER
    b = tap_box(0, 600, 1080, 1200, 4000, 3000)            # s = max(.27, .4) = .4 ; ox = (1600-1080)/2 = 260
    assert abs(b[0] - 0) < 1e-3 or b[0] >= 0                # clamped inside photo
    assert 0 <= b[0] < b[2] <= 4000 and 0 <= b[1] < b[3] <= 3000
    cx = (0 + 260) / .4
    assert abs(((b[0] + b[2]) / 2) - max(cx, (b[2] - b[0]) / 2)) < 1e-3
    # box never leaves the photo, whatever the tap
    for tx in (0, 540, 1080):
        for ty in (0, 600, 1200):
            for (iw, ih) in ((2700, 3000), (4000, 3000), (3000, 4000)):
                l, t, r, bt = tap_box(tx, ty, 1080, 1200, iw, ih)
                assert 0 <= l < r <= iw + 1e-6 and 0 <= t < bt <= ih + 1e-6

    # Period cutoffs, Africa/Lagos = UTC+1, no DST
    lagos = ZoneInfo("Africa/Lagos")
    now = datetime(2026, 10, 30, 8, 30, tzinfo=timezone.utc)      # 09:30 local on the 30th
    assert cutoff_utc("TODAY", now, lagos) == "2026-10-29 23:00:00"
    assert cutoff_utc("THREE_DAYS", now, lagos) == "2026-10-27 23:00:00"
    assert cutoff_utc("WEEK", now, lagos) == "2026-10-23 23:00:00"
    assert cutoff_utc("ALL", now, lagos) is None
    # just after local midnight: 23:30 UTC on the 29th is 00:30 local on the 30th
    now = datetime(2026, 10, 29, 23, 30, tzinfo=timezone.utc)
    assert cutoff_utc("TODAY", now, lagos) == "2026-10-29 23:00:00"
    # a zone behind UTC (New York, UTC-4 in October): local midnight is 04:00 UTC
    assert cutoff_utc("TODAY", datetime(2026, 10, 30, 3, 0, tzinfo=timezone.utc), ZoneInfo("America/New_York")) == "2026-10-29 04:00:00"
    # month boundary
    assert cutoff_utc("MONTH", datetime(2026, 3, 5, 12, 0, tzinfo=timezone.utc), lagos) == "2026-02-03 23:00:00"   # 29 days before 5 Mar is 4 Feb, local midnight = 23:00 UTC on the 3rd

    # splitSince / paging: 450 items newest-first, one per 10 minutes
    base = datetime(2026, 10, 30, 8, 0)
    items = [{"accession": f"LIB-{i}", "added_at": (base - timedelta(minutes=10 * i)).strftime("%Y-%m-%d %H:%M:%S")} for i in range(450)]
    cut = "2026-10-29 23:00:00"        # 9h before base -> items 0..54 (i*10 <= 540)
    got, trunc, calls = list_since(items, cut, 500)
    assert len(got) == 55 and not trunc and calls == 1 and got[-1]["added_at"] >= cut
    got, trunc, calls = list_since(items, None, 500)         # ALL: 450 items, 3 pages (200,200,50)
    assert len(got) == 450 and not trunc and calls == 3
    got, trunc, calls = list_since(items, None, 300)         # capped
    assert len(got) == 300 and trunc and calls == 2
    exact = items[:200]                                      # exactly one full page, nothing older seen
    got, trunc, calls = list_since(exact, None, 500)
    assert len(got) == 200 and not trunc and calls == 2      # second call returns an empty page
    assert split_since([], cut) == ([], False)

    # PATCH diff
    orig = {"title": "Old", "author": None, "shelf": "A1", "note": None, "isbn": "9780000000002", "status": "in"}
    form = {"title": " Old ", "author": "", "shelf": "A2", "note": "  ", "status": "out"}
    assert build_changes(form, orig, "9780000000002", 7) == {"shelf": "A2", "status": "out"}
    form2 = dict(form, shelf="", title="New")
    assert build_changes(form2, orig, None, None) == {"title": "New", "shelf": None, "status": "out", "isbn": None, "work": None}
    assert build_changes({"title": "Old", "author": "", "shelf": "A1", "note": "", "status": "in"}, orig, "9780000000002", 1) == {}

    # accession input
    assert norm_acc(" lib - 001 23 ") == "LIB-00123"
    assert acc_problem("") == "empty" and acc_problem("A/B") == "slash" and acc_problem("X" * 41) == "long" and acc_problem("X" * 40) is None
    test_isbn_and_providers()
    test_accession()
    test_accession_autocorrect()
    test_accession_template()
    print("all logic tests passed")

if __name__ == "__main__":
    main()
