"""Tiny SVG helpers shared by the diagram generators."""
from html import escape

FONT = "Segoe UI, Inter, Helvetica, Arial, sans-serif"
MONO = "Cascadia Code, Consolas, Menlo, monospace"

BG = "#0B0F17"
PANEL = "#0F1623"
CARD = "#151D2C"
TEXT = "#E6EAF2"
MUTED = "#98A2B3"

C = {
    "ui": "#60A5FA",
    "ai": "#F5A524",
    "qdrant": "#FF4D6D",
    "store": "#34D399",
    "sync": "#A78BFA",
    "ops": "#94A3B8",
    "cloud": "#FF4D6D",
    "edge": "#22D3EE",
}


class Svg:
    def __init__(self, w, h):
        self.w, self.h = w, h
        self.parts = []

    def add(self, s):
        self.parts.append(s)

    def render(self):
        markers = "".join(
            f'<marker id="ar-{k}" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">'
            f'<path d="M0,0 L10,5 L0,10 z" fill="{v}"/></marker>'
            for k, v in C.items()
        )
        head = (
            f'<svg xmlns="http://www.w3.org/2000/svg" width="{self.w}" height="{self.h}" '
            f'viewBox="0 0 {self.w} {self.h}" font-family="{FONT}">'
            f"<defs>{markers}"
            '<pattern id="grid" width="32" height="32" patternUnits="userSpaceOnUse">'
            '<path d="M32 0 L0 0 0 32" fill="none" stroke="#141B28" stroke-width="1"/></pattern>'
            "</defs>"
            f'<rect width="100%" height="100%" fill="{BG}"/>'
            f'<rect width="100%" height="100%" fill="url(#grid)"/>'
        )
        return head + "".join(self.parts) + "</svg>"

    # primitives -----------------------------------------------------------
    def text(self, x, y, s, size=12, color=TEXT, weight=400, anchor="start", mono=False, italic=False):
        fam = MONO if mono else FONT
        st = ' font-style="italic"' if italic else ""
        self.add(
            f'<text x="{x}" y="{y}" font-size="{size}" fill="{color}" font-weight="{weight}" '
            f'text-anchor="{anchor}" font-family="{fam}"{st}>{escape(s)}</text>'
        )

    def rect(self, x, y, w, h, fill=CARD, stroke="none", sw=1.5, rx=10, dash=None, opacity=1):
        d = f' stroke-dasharray="{dash}"' if dash else ""
        self.add(
            f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{rx}" fill="{fill}" '
            f'stroke="{stroke}" stroke-width="{sw}"{d} opacity="{opacity}"/>'
        )

    def panel(self, x, y, w, h, title, sub, color):
        self.rect(x, y, w, h, fill=PANEL, stroke=color, sw=2, rx=18)
        self.rect(x, y, w, 6, fill=color, rx=3)
        self.text(x + 22, y + 34, title, 20, color, 700)
        self.text(x + w - 22, y + 34, sub, 13, MUTED, 400, "end")

    def group(self, x, y, w, h, title, tech, color):
        self.rect(x, y, w, h, fill="#101826", stroke=color, sw=1.3, rx=14, dash="6 5")
        self.text(x + 16, y + 24, title, 15, color, 700)
        if tech:
            self.text(x + w - 16, y + 24, tech, 11.5, MUTED, 400, "end", mono=True)

    def comp(self, x, y, w, h, title, tech, lines, color, title_size=14):
        self.rect(x, y, w, h, fill=CARD, stroke=color, sw=1.5)
        self.rect(x, y + 10, 4, h - 20, fill=color, rx=2)
        self.text(x + 14, y + 22, title, title_size, TEXT, 700)
        yy = y + 22
        if tech:
            yy += 17
            self.text(x + 14, yy, tech, 11, color, 500, mono=True)
        for ln in lines:
            yy += 16
            self.text(x + 14, yy, ln, 11.5, MUTED)

    def chip(self, x, y, w, label, color, h=24, size=11.5, fill=None):
        self.rect(x, y, w, h, fill=fill or "#0D1420", stroke=color, sw=1.2, rx=h / 2)
        self.text(x + w / 2, y + h / 2 + size / 2 - 1.5, label, size, color, 600, "middle")

    def badge(self, x, y, n, color):
        self.add(f'<circle cx="{x}" cy="{y}" r="12" fill="{color}"/>')
        self.text(x, y + 4.5, str(n), 13, "#0B0F17", 800, "middle")

    def arrow(self, pts, color_key, sw=2, dash=None, both=False):
        color = C[color_key]
        d = "M" + " L".join(f"{x},{y}" for x, y in pts)
        da = f' stroke-dasharray="{dash}"' if dash else ""
        ms = f' marker-start="url(#ar-{color_key})"' if both else ""
        self.add(
            f'<path d="{d}" fill="none" stroke="{color}" stroke-width="{sw}"{da} '
            f'marker-end="url(#ar-{color_key})"{ms} stroke-linejoin="round"/>'
        )

    def line(self, pts, color, sw=1.5, dash=None):
        d = "M" + " L".join(f"{x},{y}" for x, y in pts)
        da = f' stroke-dasharray="{dash}"' if dash else ""
        self.add(f'<path d="{d}" fill="none" stroke="{color}" stroke-width="{sw}"{da}/>')
