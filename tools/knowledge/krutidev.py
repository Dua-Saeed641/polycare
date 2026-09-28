"""Legacy Kruti Dev / Chanakya Hindi → Unicode Devanagari.

Many official Hindi PDFs (e.g. the NHM ASHA modules) are typeset in pre-Unicode fonts such as
Walkman-Chanakya or Kruti Dev: the PDF stores Latin characters that only *look* like Devanagari
through the font. Extracted text reads like "xHkZorh efgyk" instead of "गर्भवती महिला".

Conversion: longest-match glyph table, then two reorderings the legacy fonts need:
  • `f` (ि) is typed before its consonant cluster → moved after the cluster.
  • `Z` (reph, र्) is typed after its syllable → moved before the cluster.
Quality is measured by the caller (share of words the e5 vocabulary knows, leftover symbols).
"""

from __future__ import annotations

import re

# Longest keys first at runtime. Values in Unicode Devanagari.
_TABLE: list[tuple[str, str]] = [
    # vowels (multi-char first)
    ("vkS", "औ"), ("vks", "ओ"), ("vk", "आ"), ("v", "अ"), ("bZ", "ई"), ("b", "इ"), ("m", "उ"), ("Å", "ऊ"),
    (",s", "ऐ"), (",", "ए"), ("_", "ऋ"),
    # conjunct glyphs
    ("{k", "क्ष"), ("{", "क्ष्"), ("=k", "त्र"), ("=", "त्र्"), ("K", "ज्ञ"), ("J", "श्र"), ("Ø", "क्र"),
    ("Ùk", "त्त"), ("Ù", "त्त्"), ("Ô", "द्ध"), ("ê", "द्द"), ("Í", "ट्ट"), ("Î", "ट्ठ"), ("Ì", "द्व"),
    ("Ï", "ड्ढ"), ("á", "ह्य"), ("â", "ह्र"), ("ã", "ह्म"), ("à", "ह्न"), ("ö", "द्य"), ("Ý", "फ्र"),
    ("æ", "द्र"), ("ç", "प्र"), ("Ð", "र्"), ("}", "द्व"), ("è", "ध्"), ("#", "रु"), ("|", "द्य"),
    # consonants with their "k" (full) forms
    ("/k", "ध"), ("[k", "ख"), ("?k", "घ"), ("Fk", "थ"), ("Hk", "भ"), ("'k", "श"), ('"k', "ष"), (".k", "ण"), ("Ck", "ब"),
    ("Pk", "च"), ("Tk", "ज"), ("Uk", "न"), ("Ik", "प"), ("Ek", "म"), ("Yk", "ल"), ("Ok", "व"), ("Lk", "स"),
    ("Xk", "ग"), ("Dk", "क"), ("Rk", "त"), ("¶k", "फ"),
    # consonants
    ("d", "क"), ("x", "ग"), ("³", "ङ"), ("p", "च"), ("N", "छ"), ("t", "ज"), (">", "झ"), ("¥", "ञ"),
    ("V", "ट"), ("B", "ठ"), ("M", "ड"), ("<", "ढ"), ("r", "त"), ("n", "द"), ("/", "ध"), ("u", "न"),
    ("i", "प"), ("Q", "फ"), ("c", "ब"), ("e", "म"), (";", "य"), ("j", "र"), ("y", "ल"), ("o", "व"),
    ("l", "स"), ("g", "ह"), ("\u00ba", "ळ"),
    # half consonants
    ("[", "ख्"), ("?", "घ्"), ("F", "थ्"), ("H", "भ्"), ("'", "श्"), ('"', "ष्"), (".", "ण्"),
    ("D", "क्"), ("X", "ग्"), ("P", "च्"), ("T", "ज्"), ("U", "न्"), ("I", "प्"), ("C", "ब्"),
    ("E", "म्"), ("¸", "य्"), ("Y", "ल्"), ("O", "व्"), ("L", "स्"), ("R", "त्"), ("¶", "फ्"),
    # matras and signs
    ("kS", "ौ"), ("ks", "ो"), ("kW", "ॉ"), ("k", "ा"), ("h", "ी"), ("q", "ु"), ("w", "ू"), ("`", "ृ"), ("s", "े"),
    ("S", "ै"), ("a", "ं"), ("¡", "ँ"), ("W", "ॉ"), ("z", "्र"), ("ª", "्र"), ("~", "्"), ("+", "़"),
    # punctuation
    ("AA", "॥"), ("A", "।"), ("&", "-"), ("]", ","), ("%", ":"), ("¼", "द्ध"), ("½", ")"), (":", "रू"), ("-", "."),
    ("@", "/"), ("\\", "?"), ("^", "‘"), ("*", "’"),
]
_TABLE.sort(key=lambda kv: -len(kv[0]))
_KEYS = [k for k, _ in _TABLE]
_MAP = dict(_TABLE)

# Placeholders for the reordered signs, resolved after mapping.
_I_MATRA = "\u0001"
_REPH = "\u0002"

_CONSONANT = "[\u0915-\u0939\u0958-\u095f]\u093c?"
_MATRAS = "\u093e-\u094c\u0901-\u0903\u0962\u0963"


def convert(text: str) -> str:
    out = []
    i = 0
    while i < len(text):
        ch = text[i]
        if ch == "f":
            out.append(_I_MATRA)
            i += 1
            continue
        # "." is ण् in these fonts, but a real full stop after a digit or before a space.
        if ch == "." and (i + 1 == len(text) or text[i + 1].isspace() or (i > 0 and text[i - 1].isdigit())):
            out.append(".")
            i += 1
            continue
        if ch == "Z":
            out.append(_REPH)
            i += 1
            continue
        for key in _KEYS:
            if text.startswith(key, i):
                out.append(_MAP[key])
                i += len(key)
                break
        else:
            out.append(ch)
            i += 1
    s = "".join(out)
    # ि typed before its cluster: move it after the whole cluster (C (् C)*).
    s = re.sub(_I_MATRA + f"((?:{_CONSONANT}\u094d)*{_CONSONANT})", "\\1\u093f", s)
    s = s.replace(_I_MATRA, "\u093f")
    # र् typed after its syllable: move it before the cluster, skipping trailing matras.
    s = re.sub(f"((?:{_CONSONANT}\u094d)*{_CONSONANT})([{_MATRAS}]*){_REPH}", "र्\\1\\2", s)
    s = s.replace(_REPH, "र्")
    # Sequences some documents type differently: ि can land before a following ा ("चिाहए" →
    # "चाहिए"), and Chanakya draws फ as प + Q ("पफ" → "फ").
    s = re.sub(f"िा({_CONSONANT})", "ा\\1ि", s)
    s = s.replace("पफ", "फ")
    # Half letter + ा means the full letter ("ज़्ाोर" → "ज़ोर"); े/ै typed around ा are ो/ौ.
    s = s.replace("्ा", "")
    for a, b in [("ेा", "ो"), ("ैा", "ौ"), ("ैे", "ै")]:
        s = s.replace(a, b)
    # Nukta forms to their precomposed letters where Unicode has them.
    for a, b in [("ड़", "ड़"), ("ढ़", "ढ़"), ("क़", "क़"), ("ख़", "ख़"), ("ग़", "ग़"), ("ज़", "ज़"), ("फ़", "फ़")]:
        s = s.replace(a, b)
    return s


def is_legacy_hindi(sample: str) -> bool:
    """Heuristic: lots of Kruti-style tokens and no Devanagari."""
    if re.search("[\u0900-\u097f]", sample):
        return False
    hits = len(re.findall(r"\b(?:gS|ds|dh|dk|vkSj|esa|fd|ls|gksrh|djsa)\b", sample))
    return hits >= 3
