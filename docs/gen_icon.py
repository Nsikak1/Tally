"""Generates the Tally adaptive-icon vector paths (108x108 viewport) with shapely.

Four upright books + one diagonal book = a tally "five-bar gate".
The diagonal book has a halo gap cut out of the upright books so it reads cleanly,
including in the single-colour themed (monochrome) icon.
"""
import math
from shapely.geometry import box, Polygon
from shapely.affinity import rotate, translate
from shapely.ops import unary_union

BG = "#12303A"
CREAM = "#F5EBD6"
TERRA = "#C9743A"
AMBER = "#F2B134"

W = 8.0          # book thickness
H = 44.0         # upright book height
GAP = 4.8
R = 1.4          # corner radius
CX, CY = 54.0, 54.0
HALO = 1.7
ANGLE = -37.0    # slash angle (negative = rises to the right on screen)


def rbox(x0, y0, x1, y1, r=R):
    return box(x0 + r, y0 + r, x1 - r, y1 - r).buffer(r, resolution=8)


# upright books
total_w = 4 * W + 3 * GAP
x_start = CX - total_w / 2
y0, y1 = CY - H / 2 + 1.5, CY + H / 2 + 1.5
books, bands = [], []
for i in range(4):
    x0 = x_start + i * (W + GAP)
    x1 = x0 + W
    books.append(rbox(x0, y0, x1, y1))
    bands.append(box(x0, y0 + 5.0, x1, y0 + 6.8))
    bands.append(box(x0, y1 - 8.0, x1, y1 - 6.2))
    # small label bar between bands
    bands.append(box(x0 + 1.6, y0 + 12.0, x1 - 1.6, y0 + 16.0))

# slash book (horizontal first, then rotate about the centre)
L = 62.0
sl = rbox(CX - L / 2, CY - W / 2, CX + L / 2, CY + W / 2)
sl_bands = [
    box(CX - L / 2 + 5.0, CY - W / 2, CX - L / 2 + 6.8, CY + W / 2),
    box(CX + L / 2 - 6.8, CY - W / 2, CX + L / 2 - 5.0, CY + W / 2),
    box(CX + L / 2 - 17.0, CY - W / 2 + 1.8, CX + L / 2 - 12.0, CY + W / 2 - 1.8),
]
slash = rotate(sl, ANGLE, origin=(CX, CY))
sl_bands = [rotate(b, ANGLE, origin=(CX, CY)) for b in sl_bands]
halo = slash.buffer(HALO, resolution=8)

books_u = unary_union(books).difference(halo)
bands_u = unary_union(bands).intersection(books_u)
sl_bands_u = unary_union(sl_bands).intersection(slash)


def to_path(geom, nd=2):
    """Shapely (Multi)Polygon -> SVG/Android pathData (even-odd friendly)."""
    polys = [geom] if geom.geom_type == "Polygon" else list(geom.geoms)
    out = []
    for p in polys:
        if p.is_empty:
            continue
        for ring in [p.exterior, *p.interiors]:
            pts = list(ring.coords)[:-1]
            seg = "M" + " L".join(f"{x:.{nd}f},{y:.{nd}f}" for x, y in pts) + "Z"
            out.append(seg)
    return "".join(out)


layers = {
    "books": to_path(books_u),
    "bands": to_path(bands_u),
    "slash": to_path(slash),
    "slash_bands": to_path(sl_bands_u),
    "mono_books": to_path(books_u.difference(bands_u)),
    "mono_slash": to_path(slash.difference(sl_bands_u)),
}


def android_path(name, fill, data, extra=""):
    return (f'    <path\n        android:fillColor="{fill}"\n'
            f'        android:fillType="evenOdd"\n'
            f'        android:pathData="{data}"{extra} />\n')


HEAD = ('<?xml version="1.0" encoding="utf-8"?>\n'
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:width="108dp"\n    android:height="108dp"\n'
        '    android:viewportWidth="108"\n    android:viewportHeight="108">\n')

fg = HEAD
fg += android_path("books", CREAM, layers["books"])
fg += android_path("bands", TERRA, layers["bands"])
fg += android_path("slash", AMBER, layers["slash"])
fg += android_path("slash_bands", BG, layers["slash_bands"])
fg += "</vector>\n"

mono = HEAD
mono += android_path("mb", "#000000", layers["mono_books"])
mono += android_path("ms", "#000000", layers["mono_slash"])
mono += "</vector>\n"

open("ic_launcher_foreground.xml", "w").write(fg)
open("ic_launcher_monochrome.xml", "w").write(mono)

# preview SVG (background + foreground, with round mask like a launcher)
svg = f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="512" height="512">
<defs><clipPath id="m"><circle cx="54" cy="54" r="54"/></clipPath></defs>
<g clip-path="url(#m)"><rect width="108" height="108" fill="{BG}"/>
<path fill="{CREAM}" fill-rule="evenodd" d="{layers['books']}"/>
<path fill="{TERRA}" fill-rule="evenodd" d="{layers['bands']}"/>
<path fill="{AMBER}" fill-rule="evenodd" d="{layers['slash']}"/>
<path fill="{BG}" fill-rule="evenodd" d="{layers['slash_bands']}"/></g></svg>'''
open("preview.svg", "w").write(svg)
import cairosvg
cairosvg.svg2png(bytestring=svg.encode(), write_to="icon_preview.png")
# small size check (launcher ~48dp)
cairosvg.svg2png(bytestring=svg.encode(), write_to="icon_preview_48.png", output_width=96, output_height=96)
print("bytes fg:", len(fg), "mono:", len(mono))
