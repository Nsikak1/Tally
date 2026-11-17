"""Library API: read-only Open Library catalog + read-write library holdings (+ online lookup add-on)."""
import hmac, json, math, os, re, sqlite3, threading, time
from contextlib import asynccontextmanager, contextmanager
from datetime import datetime, timezone
from difflib import SequenceMatcher
from pathlib import Path
from typing import Literal

from fastapi import Depends, FastAPI, Header, HTTPException, Query, Request
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field, field_validator

BASE = Path(__file__).resolve().parent
CATALOG = Path(os.environ.get("CATALOG_DB", BASE / "catalog.db"))
LIBRARY = Path(os.environ.get("LIBRARY_DB", BASE / "data" / "library.db"))
API_KEY = os.environ.get("LIB_API_KEY", "")
MMAP = int(os.environ.get("MMAP_MB", "2048")) << 20

# Online lookup (Google Books, Open Library, Library of Congress ...): optional add-on file next to this one.
# Its saved answers go in the data folder, the only place the service may write to.
os.environ.setdefault("LOOKUP_CACHE_DB", str(LIBRARY.parent / "online_lookup_cache.sqlite3"))
try:
    import online_lookup
except ImportError:
    online_lookup = None

SCHEMA = """
CREATE TABLE IF NOT EXISTS holdings(
  id INTEGER PRIMARY KEY,
  accession TEXT NOT NULL UNIQUE,
  isbn INTEGER, work INTEGER,
  title TEXT, author TEXT, shelf TEXT,
  status TEXT NOT NULL DEFAULT 'in' CHECK (status IN ('in','out','lost','repair')),
  note TEXT,
  added_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP);
CREATE INDEX IF NOT EXISTS h_isbn ON holdings(isbn);
CREATE INDEX IF NOT EXISTS h_work ON holdings(work);
CREATE INDEX IF NOT EXISTS h_status ON holdings(status);
CREATE INDEX IF NOT EXISTS h_shelf ON holdings(shelf);
CREATE INDEX IF NOT EXISTS h_added ON holdings(added_at);
CREATE TABLE IF NOT EXISTS holding_tags(
  holding_id INTEGER NOT NULL REFERENCES holdings(id) ON DELETE CASCADE,
  tag TEXT NOT NULL,
  PRIMARY KEY (holding_id, tag));
CREATE INDEX IF NOT EXISTS ht_tag ON holding_tags(tag);
"""

# Edition and acquisition details, added to the holdings table by migrate() (new AND existing databases).
# name -> SQL type. Never rename or drop these: clients and the fill-out script depend on them.
DETAIL_COLUMNS = {
    "subtitle": "TEXT", "publisher": "TEXT",
    "pub_date": "TEXT",          # date of THIS edition: "2008", "2008-05" or "2008-05-01"
    "edition": "TEXT",           # edition statement: "2nd edition", "Revised"
    "language": "TEXT",          # ISO 639-2 three letters: "eng"
    "country": "TEXT",           # country of publication, ISO 3166-1 alpha-2: "GB"
    "pages": "INTEGER", "format": "TEXT",   # format: "hardcover", "paperback" ...
    "dewey": "TEXT", "description": "TEXT",
    "list_price": "REAL",        # the edition's published price, in `currency`
    "purchase_price": "REAL",    # what the library paid for this copy, in `currency`
    "currency": "TEXT",          # ISO 4217: "NGN", "USD"
    "info_sources": "TEXT",      # JSON {"publisher": "google_books", ...}: which service supplied what
}
BASE_COLUMNS = ["accession", "isbn", "work", "title", "author", "shelf", "status", "note"]

def migrate(c):
    """Adds any missing detail columns. Safe to run on every start: it only ever ADDS, never changes or removes data."""
    have = {r[1] for r in c.execute("PRAGMA table_info(holdings)")}
    for name, sqltype in DETAIL_COLUMNS.items():
        if name not in have:
            c.execute(f"ALTER TABLE holdings ADD COLUMN {name} {sqltype}")   # names come from the constant above
    c.execute("CREATE INDEX IF NOT EXISTS h_language ON holdings(language)")
    c.execute("CREATE INDEX IF NOT EXISTS h_publisher ON holdings(publisher)")
    c.commit()

@asynccontextmanager
async def lifespan(app):
    LIBRARY.parent.mkdir(parents=True, exist_ok=True)
    c = sqlite3.connect(LIBRARY, timeout=10)
    c.execute("PRAGMA journal_mode=WAL")
    c.executescript(SCHEMA)
    migrate(c)
    c.close()
    if not API_KEY:
        print("WARNING: LIB_API_KEY not set, write endpoints are unauthenticated", flush=True)
    if online_lookup is None:
        print("NOTE: online_lookup.py not found, /lookup is not available", flush=True)
    else:
        print("online lookup: " + ", ".join(online_lookup.provider_order())
              + ("; Google Books key set" if os.environ.get("GOOGLE_BOOKS_API_KEY") else "; no Google Books key"),
              flush=True)
    yield

app = FastAPI(title="Library API", lifespan=lifespan)
if online_lookup is not None and online_lookup.router is not None:
    app.include_router(online_lookup.router)      # adds GET /lookup/{isbn}

UPDATES_ENV = os.environ.get("UPDATES_DIR")

def get_updates_dir() -> Path:
    if UPDATES_ENV:
        p = Path(UPDATES_ENV)
        p.mkdir(parents=True, exist_ok=True)
        return p
    for candidate in [BASE, Path("/var/www/tally-updates"), BASE / "updates"]:
        if (candidate / "update-manifest.json").exists() or (candidate / "apks").exists():
            return candidate
    default_dir = BASE / "updates"
    default_dir.mkdir(parents=True, exist_ok=True)
    return default_dir

# Serve static downloads (/updates/apks/..., /updates/patches/..., /updates/update-manifest.json)
updates_dir = get_updates_dir()
app.mount("/updates", StaticFiles(directory=str(updates_dir), html=False), name="updates")

@app.get("/updates/{file_path:path}")
def serve_update_file(file_path: str):
    u_dir = get_updates_dir()
    for base in [u_dir, Path("/var/www/tally-updates"), BASE / "updates", BASE]:
        target = (base / file_path).resolve()
        if target.is_file():
            return FileResponse(target)
    raise HTTPException(404, "file not found")

@app.get("/api/v1/app/check-update")
def check_update(request: Request):
    u_dir = get_updates_dir()
    manifest_path = u_dir / "update-manifest.json"
    if not manifest_path.exists():
        for candidate in [BASE / "update-manifest.json", Path("/var/www/tally-updates/update-manifest.json"), BASE / "updates" / "update-manifest.json"]:
            if candidate.exists():
                manifest_path = candidate
                break
    if not manifest_path.exists():
        return {
            "latestVersionCode": 0,
            "latestVersionName": "0.0.0",
            "minSupportedVersionCode": 1,
            "releaseNotes": "No update published yet.",
            "fullApkUrl": "",
            "fullApkSha256": "",
            "fullApkSizeBytes": 0,
            "patches": []
        }
    try:
        with open(manifest_path, "r", encoding="utf-8") as f:
            data = json.load(f)

        req_base = str(request.base_url).rstrip("/")
        if "fullApkUrl" in data and data["fullApkUrl"]:
            u_path = data["fullApkUrl"].split("/updates/", 1)[-1] if "/updates/" in data["fullApkUrl"] else data["fullApkUrl"].lstrip("/")
            data["fullApkUrl"] = f"{req_base}/updates/{u_path}"

        if "patches" in data and isinstance(data["patches"], list):
            for patch in data["patches"]:
                if "patchUrl" in patch and patch["patchUrl"]:
                    p_path = patch["patchUrl"].split("/updates/", 1)[-1] if "/updates/" in patch["patchUrl"] else patch["patchUrl"].lstrip("/")
                    patch["patchUrl"] = f"{req_base}/updates/{p_path}"

        return data
    except Exception as e:
        raise HTTPException(500, f"error reading update manifest: {e}")

_tl = threading.local()
STOP = {"the", "a", "an", "of", "and", "in", "to", "for", "on"}

@contextmanager
def deadline(seconds):
    _tl.deadline = time.monotonic() + seconds
    try:
        yield
    finally:
        _tl.deadline = float("inf")

def tokens(text):
    return re.findall(r"[^\W_]+", (text or "").lower())[:8]

def cat():
    c = getattr(_tl, "cat", None)
    if c is None:
        if not CATALOG.exists():
            raise HTTPException(503, "catalog not built yet")
        c = sqlite3.connect(f"{CATALOG.resolve().as_uri()}?mode=ro&immutable=1", uri=True)
        c.row_factory = sqlite3.Row
        c.execute(f"PRAGMA mmap_size={MMAP}")
        c.set_progress_handler(
            lambda: int(time.monotonic() > getattr(_tl, "deadline", float("inf"))), 100000)
        _tl.cat = c
    return c

def lib():
    c = getattr(_tl, "lib", None)
    if c is None:
        c = sqlite3.connect(LIBRARY, timeout=10)
        c.row_factory = sqlite3.Row
        c.execute("PRAGMA synchronous=NORMAL")      # safe with WAL, fewer disk syncs per write
        c.execute("PRAGMA foreign_keys=ON")         # deleting a copy also deletes its tags
        _tl.lib = c
    return c

def require_key(x_api_key: str | None = Header(None)):
    if API_KEY and not hmac.compare_digest((x_api_key or "").encode(), API_KEY.encode()):
        raise HTTPException(401, "bad or missing X-API-Key header")

# ---------- helpers ----------
def to13(s):
    s = re.sub(r"[-\s]", "", s).upper()
    if len(s) == 13 and s.isdigit() and s[:3] in ("978", "979"):
        return int(s)
    if len(s) == 10 and s[:9].isdigit():
        core = "978" + s[:9]
        chk = (10 - sum(int(c) * (1 if i % 2 == 0 else 3) for i, c in enumerate(core)) % 10) % 10
        return int(core + str(chk))
    return None

ED_SQL = """SELECT e.isbn, COALESCE(e.title, w.title) AS title,
       COALESCE(e.author, w.authors) AS author, e.year, e.lang, e.dewey, e.cover,
       e.work, COALESCE(w.n, 1) AS editions
FROM editions e LEFT JOIN works w ON w.id = e.work WHERE e.isbn = ?"""

def get_ed(isbn):
    n = to13(isbn)
    if n is None:
        raise HTTPException(400, "invalid ISBN")
    return db_ed(n)

def db_ed(n):
    return cat().execute(ED_SQL, (n,)).fetchone()

def cover_url(c):
    return f"https://covers.openlibrary.org/b/id/{c}-M.jpg" if c else None

def book_json(r):
    return {"isbn": str(r["isbn"]), "title": r["title"], "author": r["author"],
            "work": r["work"], "editions": r["editions"], "year": r["year"],
            "lang": r["lang"], "dewey": r["dewey"], "cover_url": cover_url(r["cover"])}

def norm(t):
    t = re.sub(r"[^\w\s]", " ", (t or "").split(":")[0].lower())
    stop = {"the", "a", "an", "edition", "revised", "paperback", "hardcover"}
    return " ".join(w for w in t.split() if w not in stop)

def surname(s):
    p = (s or "").split(";")[0].split()
    return p[-1].lower() if p else ""

def match_expr(q, title, author, loose):
    parts = []
    def add(text, col=None):
        t = tokens(text)
        t = [w for w in t if w not in STOP] or t     # drop "the", "of"... unless that is all there is
        for i, w in enumerate(t):
            term = f'"{w}"' + ("*" if i == len(t) - 1 else "")
            parts.append(f"{col} : {term}" if col else term)
    add(q); add(title, "title"); add(author, "authors")
    return (" OR " if loose else " AND ").join(parts)

# ---------- health ----------
@app.get("/health")
def health():
    ok = CATALOG.exists()
    n = lib().execute("SELECT count(*) FROM holdings").fetchone()[0]
    built = datetime.fromtimestamp(CATALOG.stat().st_mtime, timezone.utc).isoformat() if ok else None
    u_dir = get_updates_dir()
    manifest_exists = (u_dir / "update-manifest.json").exists()
    return {"ok": True, "catalog": ok, "catalog_built": built,
            "holdings": n, "writes_need_key": bool(API_KEY),
            "online_lookup": online_lookup is not None,
            "online_providers": online_lookup.provider_order() if online_lookup else [],
            "google_books_key": bool(os.environ.get("GOOGLE_BOOKS_API_KEY")),
            "updates_dir": str(u_dir),
            "update_manifest_found": manifest_exists}

# ---------- catalog (read-only) ----------
@app.get("/isbn/{isbn}")
def by_isbn(isbn: str):
    r = get_ed(isbn)
    if not r:
        raise HTTPException(404, "not found")
    return book_json(r)

@app.get("/search")
def search(q: str | None = None, title: str | None = None,
           author: str | None = None, limit: int = Query(20, ge=1, le=50)):
    if not any(len(w) >= 3 and w not in STOP
               for s_ in (q, title, author) for w in tokens(s_)):
        raise HTTPException(400, "type a word of at least 3 letters")
    qn = norm(q or title or "")
    rows, loose = [], False
    try:
        with deadline(4.0):
            for loose in (False, True):
                expr = match_expr(q, title, author, loose)
                rows = cat().execute(
                    """SELECT w.id, w.title, w.authors, w.n, bm25(works_fts, 10.0, 4.0) AS s
                       FROM works_fts JOIN works w ON w.id = works_fts.rowid
                       WHERE works_fts MATCH ? ORDER BY s LIMIT 100""", (expr,)).fetchall()
                if rows:
                    break
    except sqlite3.OperationalError as e:
        if "interrupt" in str(e).lower():
            raise HTTPException(503, "query too broad, add more letters")
        raise HTTPException(400, f"bad search: {e}")

    def score(r):
        s = r["s"] - math.log1p(r["n"])
        return s - 5 if qn and norm(r["title"]) == qn else s   # exact title wins

    out = []
    for r in sorted(rows, key=score)[:limit]:
        isbns = [str(x[0]) for x in cat().execute(
            "SELECT isbn FROM editions WHERE work=? LIMIT 3", (r["id"],))]
        out.append({"work": r["id"], "title": r["title"], "authors": r["authors"],
                    "editions": r["n"], "sample_isbns": isbns})
    return {"match": "any" if loose else "all", "results": out}

@app.get("/work/{wid}")
def work(wid: int):
    w = cat().execute("SELECT * FROM works WHERE id=?", (wid,)).fetchone()
    if not w:
        raise HTTPException(404, "not found")
    eds = cat().execute(
        "SELECT isbn, COALESCE(title, ?) AS title, year, lang, cover FROM editions "
        "WHERE work=? ORDER BY year DESC LIMIT 50", (w["title"], wid)).fetchall()
    return {"work": wid, "title": w["title"], "authors": w["authors"],
            "editions": [{"isbn": str(e["isbn"]), "title": e["title"], "year": e["year"],
                          "lang": e["lang"], "cover_url": cover_url(e["cover"])} for e in eds]}

@app.get("/same/{a}/{b}")
def same(a: str, b: str):
    ra, rb = get_ed(a), get_ed(b)
    if not ra or not rb:
        raise HTTPException(404, "ISBN not found")
    if ra["work"] and ra["work"] == rb["work"]:
        return {"same": True, "verdict": "yes", "confidence": 1.0, "reason": "same work"}
    score = SequenceMatcher(None, norm(ra["title"]), norm(rb["title"])).ratio()
    ok = surname(ra["author"]) != "" and surname(ra["author"]) == surname(rb["author"])
    verdict = "yes" if score >= 0.9 and ok else "maybe" if score >= 0.6 else "no"
    return {"same": verdict == "yes", "verdict": verdict,
            "confidence": round(score, 3), "reason": "title similarity"}

# ---------- holdings (read-write; accession numbers must not contain "/") ----------
Status = Literal["in", "out", "lost", "repair"]

TAG_MAX, TAGS_MAX = 40, 50

def clean_tag(t):
    return re.sub(r"\s+", " ", t.strip().lower())[:TAG_MAX].strip()

class Details(BaseModel):
    """Edition and acquisition details. Shared by create and patch; every field optional."""
    subtitle: str | None = Field(None, max_length=300)
    publisher: str | None = Field(None, max_length=200)
    pub_date: str | None = Field(None, pattern=r"^\d{4}(-\d{2}(-\d{2})?)?$")
    edition: str | None = Field(None, max_length=100)
    language: str | None = Field(None, pattern=r"^[a-z]{3}$")
    country: str | None = Field(None, pattern=r"^[A-Z]{2}$")
    pages: int | None = Field(None, ge=1, le=20000)
    format: str | None = Field(None, max_length=60)
    dewey: str | None = Field(None, max_length=40)
    description: str | None = Field(None, max_length=5000)
    list_price: float | None = Field(None, ge=0, le=10_000_000)
    purchase_price: float | None = Field(None, ge=0, le=10_000_000)
    currency: str | None = Field(None, pattern=r"^[A-Z]{3}$")
    tags: list[str] | None = None            # on PATCH the list REPLACES the old one; [] or null clears it
    info_sources: dict[str, str] | None = None

    @field_validator("language", mode="before")
    @classmethod
    def _lower(cls, v):
        return v.strip().lower() if isinstance(v, str) else v

    @field_validator("country", "currency", mode="before")
    @classmethod
    def _upper(cls, v):
        return v.strip().upper() if isinstance(v, str) else v

    @field_validator("tags", mode="after")
    @classmethod
    def _tidy_tags(cls, v):
        if v is None:
            return None
        seen = []
        for t in map(clean_tag, v):
            if t and t not in seen:
                seen.append(t)
        if len(seen) > TAGS_MAX:
            raise ValueError(f"at most {TAGS_MAX} tags")
        return seen

class HoldingIn(Details):
    accession: str = Field(min_length=1, max_length=40)
    isbn: str | None = None
    work: int | None = None
    title: str | None = None
    author: str | None = None
    shelf: str | None = None
    status: Status = "in"
    note: str | None = None

class HoldingPatch(Details):
    isbn: str | None = None
    work: int | None = None
    title: str | None = None
    author: str | None = None
    shelf: str | None = None
    status: Status | None = None
    note: str | None = None

def norm_acc(s):
    return re.sub(r"\s+", "", s).upper()

def holds(rows):
    """Holdings as JSON-ready dicts, with their tags (one query for all rows) and parsed info_sources."""
    out = []
    for r in rows:
        d = dict(r)
        d["isbn"] = str(d["isbn"]) if d["isbn"] else None
        try:
            d["info_sources"] = json.loads(d["info_sources"]) if d.get("info_sources") else None
        except ValueError:
            d["info_sources"] = None
        d["tags"] = []
        out.append(d)
    if out:
        by_id = {d["id"]: d for d in out}
        marks = ",".join("?" * len(by_id))
        for hid, tag in lib().execute(
                f"SELECT holding_id, tag FROM holding_tags WHERE holding_id IN ({marks}) ORDER BY tag", list(by_id)):
            by_id[hid]["tags"].append(tag)
    return out

def hold(r):
    return holds([r])[0]

def save_tags(c, holding_id, tags):
    c.execute("DELETE FROM holding_tags WHERE holding_id=?", (holding_id,))
    c.executemany("INSERT OR IGNORE INTO holding_tags(holding_id, tag) VALUES(?,?)", [(holding_id, t) for t in tags])

def holding_row(acc):
    return lib().execute("SELECT * FROM holdings WHERE accession=?", (norm_acc(acc),)).fetchone()

def autofill(d):
    """If an ISBN is given, fill missing title/author/work from the catalog, or else from a saved online lookup."""
    if not d.get("isbn"):
        return
    n = to13(d["isbn"])
    if n is None:
        raise HTTPException(400, "invalid ISBN")
    d["isbn"] = n
    try:
        r = db_ed(n)
    except HTTPException:
        r = None
    if r:
        d["title"] = d.get("title") or r["title"]
        d["author"] = d.get("author") or r["author"]
        if d.get("work") is None:
            d["work"] = r["work"]
        fill_details(d, r["year"], r["lang"], r["dewey"])
    elif online_lookup is not None:
        b = online_lookup.cached_book(str(n))      # only what was already looked up: no network on a save
        if b:
            d["title"] = d.get("title") or b.get("title")
            d["author"] = d.get("author") or b.get("author")
            fill_details(d, b.get("year"), b.get("lang"), b.get("dewey"))

def fill_details(d, year, lang, dewey):
    """Edition date, language and Dewey class from a catalog row, only where the client left them empty and the value is well-formed."""
    if not d.get("pub_date") and year and re.fullmatch(r"\d{4}", str(year)):
        d["pub_date"] = str(year)
    if not d.get("language") and lang and re.fullmatch(r"[a-z]{3}", str(lang)):
        d["language"] = str(lang)
    if not d.get("dewey") and dewey:
        d["dewey"] = str(dewey)[:40]

@app.get("/scan/{isbn}")
def scan(isbn: str):
    n = to13(isbn)
    if n is None:
        raise HTTPException(400, "invalid ISBN")
    try:
        r = db_ed(n)
    except HTTPException:
        r = None
    work_id = r["work"] if r else None
    rows = lib().execute("SELECT * FROM holdings WHERE isbn=? OR work=? ORDER BY accession",
                         (n, work_id)).fetchall()
    return {"book": book_json(r) if r else None,
            "copies": [{**h, "same_edition": h["isbn"] == str(n)} for h in holds(rows)]}

@app.post("/holdings", status_code=201, dependencies=[Depends(require_key)])
def add_holding(h: HoldingIn):
    d = h.model_dump()
    d["accession"] = norm_acc(d["accession"])
    autofill(d)
    tags = d.pop("tags") or []
    if d["info_sources"] is not None:
        d["info_sources"] = json.dumps(d["info_sources"], ensure_ascii=False)
    cols = BASE_COLUMNS + list(DETAIL_COLUMNS)         # fixed lists, never client text
    try:
        with lib() as c:
            cur = c.execute(f"INSERT INTO holdings({','.join(cols)}) VALUES({','.join(':' + k for k in cols)})", d)
            save_tags(c, cur.lastrowid, tags)
    except sqlite3.IntegrityError:
        raise HTTPException(409, "accession number already exists")
    return hold(holding_row(d["accession"]))

@app.get("/holdings/{accession}")
def get_holding(accession: str):
    r = holding_row(accession)
    if not r:
        raise HTTPException(404, "accession not found")
    return hold(r)

@app.patch("/holdings/{accession}", dependencies=[Depends(require_key)])
def patch_holding(accession: str, p: HoldingPatch):
    acc = norm_acc(accession)
    d = p.model_dump(exclude_unset=True)       # only fields the client sent
    if not d:
        raise HTTPException(400, "nothing to update")
    if "status" in d and d["status"] is None:
        raise HTTPException(400, "status cannot be null")
    if d.get("isbn"):
        n = to13(d["isbn"])
        if n is None:
            raise HTTPException(400, "invalid ISBN")
        d["isbn"] = n
    tags = d.pop("tags", ...)                  # ... = not sent: leave the tags alone
    if "info_sources" in d and d["info_sources"] is not None:
        d["info_sources"] = json.dumps(d["info_sources"], ensure_ascii=False)
    sets = ", ".join(f"{k}=:{k}" for k in d)   # keys come from the model, not the client
    d["acc"] = acc
    with lib() as c:
        if sets:
            cur = c.execute(f"UPDATE holdings SET {sets}, updated_at=CURRENT_TIMESTAMP "
                            "WHERE accession=:acc", d)
            found = cur.rowcount != 0
        else:                                  # only tags were sent
            found = c.execute("UPDATE holdings SET updated_at=CURRENT_TIMESTAMP WHERE accession=?", (acc,)).rowcount != 0
        if found and tags is not ...:
            hid = c.execute("SELECT id FROM holdings WHERE accession=?", (acc,)).fetchone()[0]
            save_tags(c, hid, tags or [])
    if not found:
        raise HTTPException(404, "accession not found")
    return hold(holding_row(acc))

@app.delete("/holdings/{accession}", status_code=204, dependencies=[Depends(require_key)])
def delete_holding(accession: str):
    with lib() as c:
        cur = c.execute("DELETE FROM holdings WHERE accession=?", (norm_acc(accession),))
    if cur.rowcount == 0:
        raise HTTPException(404, "accession not found")

@app.get("/holdings")
def list_holdings(q: str | None = None, status: Status | None = None, shelf: str | None = None,
                  tag: str | None = None, language: str | None = None, publisher: str | None = None,
                  limit: int = Query(50, ge=1, le=200), offset: int = Query(0, ge=0)):
    where, args = [], []
    for t in tokens(q):
        where.append("lower(coalesce(title,'')||' '||coalesce(subtitle,'')||' '||coalesce(author,'')||' '"
                     "||coalesce(publisher,'')||' '||accession) LIKE ?")
        args.append(f"%{t}%")
    if status:
        where.append("status=?"); args.append(status)
    if shelf:
        where.append("shelf=?"); args.append(shelf)
    if tag:
        where.append("EXISTS (SELECT 1 FROM holding_tags t WHERE t.holding_id=holdings.id AND t.tag=?)")
        args.append(clean_tag(tag))
    if language:
        where.append("language=?"); args.append(language.strip().lower())
    if publisher:
        where.append("lower(publisher) LIKE ?"); args.append(f"%{publisher.strip().lower()}%")
    sql = "SELECT * FROM holdings" + (" WHERE " + " AND ".join(where) if where else "")
    rows = lib().execute(sql + " ORDER BY id DESC LIMIT ? OFFSET ?", (*args, limit, offset)).fetchall()
    return {"offset": offset, "results": holds(rows)}

@app.get("/tags")
def list_tags(limit: int = Query(200, ge=1, le=1000)):
    """Every tag in use with how many copies carry it, most used first."""
    rows = lib().execute("SELECT tag, count(*) AS n FROM holding_tags GROUP BY tag ORDER BY n DESC, tag LIMIT ?", (limit,))
    return [{"tag": r["tag"], "copies": r["n"]} for r in rows]
