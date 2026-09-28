"""Builds PolyCare's on-device knowledge shard from official source documents.

    tools/.venv/Scripts/python tools/knowledge/build_knowledge.py

Pipeline: sources.json → PDF text per page → clean → ~150-word passages with page citations
(tables become one passage per row) → e5 passage embeddings (same model + ONNX Runtime version
as the phone) + BM25 sparse vectors (same formula as core-embed/SparseEncoder) → Qdrant Edge
shard (same layout as QdrantEdgeVectorStore) → zip + manifest.

Outputs in tools/knowledge/out/:
  knowledge-<version>.zip   the shard directory, zipped
  knowledge-<version>.json  manifest: sha256, size, points, model_id, sources (with their sha256)
  report-<version>.json     every passage + sample query results, for review

Until Qdrant Cloud partial snapshots exist (M6), this zip is how the cloud-owned `knowledge`
shard reaches the phone. It is only ever replaced whole, never edited on the phone (invariant 9).
"""

from __future__ import annotations

import hashlib
import json
import re
import shutil
import sys
import uuid
import zipfile
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

import numpy as np
import onnxruntime as ort
import pymupdf
import qdrant_edge as q
from tokenizers import Tokenizer

sys.path.insert(0, str(Path(__file__).resolve().parent))
import krutidev  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
HERE = ROOT / "tools" / "knowledge"
SOURCES = HERE / "sources"
OUT = HERE / "out"
MODEL_DIR = ROOT / "tools" / "models" / "multilingual-e5-small"

VERSION = "v2"  # v1: first build · v2: Hindi modules + legacy-font and ligature fixes
MODEL_ID = "multilingual-e5-small/int8@761b726"  # must equal E5Embedder.MODEL_ID
DIM = 384

# Must equal PolyCareConfig.Retrieval (core-common) so phone queries and these documents agree.
BM25_K1 = 1.2
BM25_B = 0.75
BM25_AVG_DOC_TOKENS = 120.0

TARGET_WORDS = 120
MAX_WORDS = 180
MIN_WORDS = 25
MAX_TOKENS = 512
ID_NAMESPACE = uuid.UUID("5c0e3b1a-7a44-4c1e-9d0b-0f6a2c9e8a11")

SAMPLE_QUERIES = [
    "how many antenatal check-ups does a pregnant woman need",
    "danger signs in a newborn baby",
    "how to prepare ORS",
    "when is BCG vaccine given",
    "गर्भावस्था में खतरे के लक्षण",
    "बच्चे को दस्त हो तो क्या करें",
    "fever with rash in a child",
    "iron folic acid tablets during pregnancy",
]


# ── Text extraction ──────────────────────────────────────────────────────────

# PDF font artefacts seen in the NHM modules: ligatures mapped to odd code points and
# Wingdings bullets in the private-use area.
LIGATURES = {
    "ﬁ ": "fi", "ﬂ ": "fl", "ﬁ": "fi", "ﬂ": "fl", "ﬀ": "ff", "ﬃ": "ffi", "ﬄ": "ffl",
    "Ĵ": "tt", "Ğ": "ft",
    # Module 6 encodes "ft" as control char 0x04 plus a space ("a\x04 er" → "after").
    "\x04 ": "ft", "\x04": "ft",
    "": "•", "": "•", "": "•", "": "•", "": "", "": "",
    "­": "", "’": "'", "‘": "'",
}
BULLET = re.compile(r"^\s*(?:[•▪●◦\-–]|\(?[0-9ivx]{1,3}[.)])\s+")
SENTENCE_END = re.compile(r"(?<=[.!?])\s+(?=[A-Z(\"'])|(?<=[।॥])\s*")


def clean(text: str) -> str:
    for a, b in LIGATURES.items():
        text = text.replace(a, b)
    return text


def prose_pages(pdf: Path, legacy_hindi: bool = False) -> list[tuple[int, list[str]]]:
    """Lines per page, with running headers/footers and page numbers removed."""
    doc = pymupdf.open(pdf)
    texts = [p.get_text() for p in doc]
    if legacy_hindi:
        texts = [krutidev.convert(t) for t in texts]
    pages = [[l.strip() for l in clean(t).split("\n")] for t in texts]
    freq = Counter(l for lines in pages for l in set(lines) if l)
    running = {l for l, n in freq.items() if n >= max(3, len(pages) * 0.2) and len(l) < 80}
    out = []
    for i, lines in enumerate(pages, start=1):
        kept = [l for l in lines if l and l not in running and not re.fullmatch(r"\d{1,3}", l) and "...." not in l]
        out.append((i, kept))
    return out


def sentences(lines: list[str]) -> list[str]:
    """Joins wrapped lines, keeps bullets as their own items, splits sentences."""
    items: list[str] = []
    buf = ""
    for line in lines:
        if BULLET.match(line) and buf:
            items.append(buf)
            buf = "• " + BULLET.sub("", line)
        else:
            buf = f"{buf} {line}".strip() if buf else ("• " + BULLET.sub("", line) if BULLET.match(line) else line)
    if buf:
        items.append(buf)
    out = []
    for item in items:
        item = re.sub(r"\s+", " ", item).strip()
        for s in SENTENCE_END.split(item):
            words = s.split()
            # Text without sentence breaks (tables, lists) is split by length instead.
            for i in range(0, len(words), MAX_WORDS):
                if words[i:i + MAX_WORDS]:
                    out.append(" ".join(words[i:i + MAX_WORDS]))
    return out


def chunk_prose(source: dict) -> list[dict]:
    passages = []
    for page, lines in prose_pages(SOURCES / source["file"], source.get("encoding") == "krutidev"):
        sents = sentences(lines)
        chunk: list[str] = []
        words = 0
        for s in sents:
            n = len(s.split())
            if chunk and words + n > MAX_WORDS:
                passages.append((page, chunk))
                chunk, words = chunk[-1:], len(chunk[-1].split())  # one sentence of overlap
            chunk.append(s)
            words += n
            if words >= TARGET_WORDS:
                passages.append((page, chunk))
                chunk, words = chunk[-1:], len(chunk[-1].split())
        if chunk and words >= MIN_WORDS:
            passages.append((page, chunk))
    out = []
    seen = set()
    for page, chunk in passages:
        text = " ".join(chunk)
        if len(text.split()) < MIN_WORDS or text in seen:
            continue
        seen.add(text)
        out.append({"text": text, "page": page, "quality": "prose"})
    return out


def norm_cell(c) -> str:
    return re.sub(r"\s+", " ", c or "").strip()


def chunk_table(source: dict) -> list[dict]:
    """National Immunization Schedule: one passage per age row and per vaccine row."""
    doc = pymupdf.open(SOURCES / source["file"])
    out = []
    for page_index, page in enumerate(doc):
        for table in page.find_tables().tables:
            rows = table.extract()
            header = [norm_cell(c) for c in rows[0]]
            if header[:2] == ["Age", "Vaccines given"]:
                for r in rows[1:]:
                    age, vaccines = norm_cell(r[0]), norm_cell(r[1])
                    if age and vaccines:
                        out.append({
                            "text": f"Immunization schedule — vaccines given at {age}: {vaccines}.",
                            "page": page_index + 1,
                            "quality": "table",
                        })
                continue
            names, current = [], None
            for c in header:
                current = c or current
                names.append(current)
            groups, section = [], None
            for r in rows[1:]:
                cells = [norm_cell(c) for c in r]
                filled = [c for c in cells if c]
                if len(filled) == 1 and not cells[0] and filled[0].lower().startswith("for "):
                    section = filled[0]
                    continue
                if cells[0]:
                    groups.append({"section": section, "name": cells[0], "cols": {}})
                if not groups:
                    continue
                cols = groups[-1]["cols"]
                for i, c in enumerate(cells):
                    if c and i > 0 and names[i]:
                        parts = cols.setdefault(names[i], [])
                        if c not in parts:
                            parts.append(c)
            for g in groups:
                cols = g["cols"]
                extra_vaccines = [v for v in cols.get("Vaccine", []) if v not in g["name"] and g["name"] not in v]
                who = f" ({g['section'].lower()})" if g["section"] else ""
                fields = "; ".join(
                    f"{label}: {' '.join(cols[key])}"
                    for key, label in [("When to give", "when to give"), ("Dose", "dose"), ("Route", "route"), ("Site", "site")]
                    if cols.get(key)
                )
                if extra_vaccines:
                    # Several vaccines share one printed row; the pairing of doses to vaccines is
                    # ambiguous, so keep it as printed and say so rather than state a clean fact.
                    text = (f"Immunization schedule table row (as printed; several vaccines share this row, check the "
                            f"printed schedule): {g['name']}, {', '.join(extra_vaccines)} — {fields}.")
                    quality = "table-ambiguous"
                else:
                    text = f"Immunization schedule{who} — {g['name']}: {fields}."
                    quality = "table"
                out.append({"text": text, "page": page_index + 1, "quality": quality})
    return out


# ── Vectors ──────────────────────────────────────────────────────────────────

class Encoders:
    def __init__(self) -> None:
        self.tokenizer = Tokenizer.from_file(str(MODEL_DIR / "tokenizer.json"))
        self.session = ort.InferenceSession(str(MODEL_DIR / "model_quantized.onnx"))
        self.special = {0, 1, 2, 3}  # <s> <pad> </s> <unk>

    def ids(self, text: str) -> list[int]:
        # Same truncation as E5Tokenizer.encode: first 511 tokens, then </s>.
        ids = self.tokenizer.encode(text).ids
        return ids if len(ids) <= MAX_TOKENS else ids[: MAX_TOKENS - 1] + [2]

    def dense(self, text: str) -> list[float]:
        # One text per run: the int8 model's dynamic quantisation couples batch members.
        ids = np.array([self.ids(text)], dtype=np.int64)
        mask = np.ones_like(ids)
        h = self.session.run(None, {"input_ids": ids, "attention_mask": mask, "token_type_ids": np.zeros_like(ids)})[0]
        v = h.mean(axis=1)[0]
        return (v / np.linalg.norm(v)).astype(np.float32).tolist()

    def sparse_document(self, text: str) -> q.SparseVector:
        terms = [t for t in self.ids(text) if t not in self.special]
        tf = Counter(terms)
        length_norm = 1 - BM25_B + BM25_B * len(terms) / BM25_AVG_DOC_TOKENS
        items = sorted((t, n * (BM25_K1 + 1) / (n + BM25_K1 * length_norm)) for t, n in tf.items())
        return q.SparseVector(indices=[t for t, _ in items], values=[float(w) for _, w in items])

    def sparse_query(self, text: str) -> q.SparseVector:
        terms = sorted({t for t in self.ids(text) if t not in self.special})
        return q.SparseVector(indices=terms, values=[1.0] * len(terms))


# ── Shard ────────────────────────────────────────────────────────────────────

def edge_config() -> q.EdgeConfig:
    # Same layout as QdrantEdgeVectorStore (android/qdrant-edge): dense cosine int8 + HNSW m=8,
    # sparse with IDF.
    return q.EdgeConfig(
        vectors={
            "dense": q.EdgeVectorParams(
                size=DIM,
                distance=q.Distance.Cosine,
                quantization_config=q.ScalarQuantizationConfig(type=q.ScalarType.Int8, quantile=0.99, always_ram=True),
                hnsw_config=q.HnswIndexConfig(m=8, ef_construct=100, full_scan_threshold=10_000),
            )
        },
        sparse_vectors={"sparse": q.EdgeSparseVectorParams(modifier=q.Modifier.Idf)},
    )


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def main() -> None:
    sources = json.loads((HERE / "sources.json").read_text(encoding="utf-8"))["sources"]
    enc = Encoders()

    passages = []
    for src in sources:
        items = chunk_table(src) if src["kind"] == "table" else chunk_prose(src)
        for n, p in enumerate(items):
            pid = str(uuid.uuid5(ID_NAMESPACE, f"{src['id']}:{p['page']}:{n}:{p['text'][:40]}"))
            passages.append({
                "id": pid, "text": p["text"], "page": p["page"], "quality": p["quality"],
                "source": src["id"], "title": src["title"], "lang": src["lang"], "programme": src["programme"],
            })
        print(f"{src['id']}: {len(items)} passages", file=sys.stderr)

    OUT.mkdir(parents=True, exist_ok=True)
    shard_dir = OUT / f"knowledge-{VERSION}"
    if shard_dir.exists():
        shutil.rmtree(shard_dir)
    shard_dir.mkdir(parents=True)
    shard = q.EdgeShard.create(str(shard_dir), edge_config())
    for field in ("source", "lang", "programme", "quality"):
        shard.update(q.UpdateOperation.create_field_index(field, q.PayloadSchemaType.Keyword))

    batch = []
    for i, p in enumerate(passages):
        payload = {k: p[k] for k in ("text", "page", "quality", "source", "title", "lang", "programme")}
        payload["_pid"] = p["id"]
        vector = {"dense": enc.dense("passage: " + p["text"]), "sparse": enc.sparse_document(p["text"])}
        batch.append(q.Point(id=p["id"], vector=vector, payload=payload))
        if len(batch) == 64 or i == len(passages) - 1:
            shard.update(q.UpdateOperation.upsert_points(batch))
            batch = []
        if (i + 1) % 100 == 0:
            print(f"embedded {i + 1}/{len(passages)}", file=sys.stderr)
    shard.flush()
    shard.optimize()

    samples = []
    for query in SAMPLE_QUERIES:
        hits = shard.query(q.QueryRequest(
            limit=3,
            prefetches=[
                q.Prefetch(limit=20, query=q.Query.Nearest(enc.dense("query: " + query), using="dense")),
                q.Prefetch(limit=20, query=q.Query.Nearest(enc.sparse_query(query), using="sparse")),
            ],
            query=q.Fusion.Rrf(k=60),
            with_payload=True,
        ))
        samples.append({"query": query, "hits": [
            {"score": round(h.score, 4), "source": h.payload["source"], "page": h.payload["page"], "text": h.payload["text"][:160]}
            for h in hits
        ]})
    count = shard.count(q.CountRequest(exact=True))
    shard.close()

    zip_path = OUT / f"knowledge-{VERSION}.zip"
    zip_path.unlink(missing_ok=True)
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as z:
        for f in sorted(shard_dir.rglob("*")):
            if f.is_file():
                z.write(f, f.relative_to(shard_dir).as_posix())

    manifest = {
        "version": VERSION,
        "built_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "file": zip_path.name,
        "sha256": sha256(zip_path),
        "size_bytes": zip_path.stat().st_size,
        "points": count,
        "model_id": MODEL_ID,
        "dim": DIM,
        "engine": f"qdrant-edge-py {getattr(q, '__version__', '0.8.0')}",
        "sources": [
            {k: s[k] for k in ("id", "title", "publisher", "url", "lang", "programme")} | {"sha256": sha256(SOURCES / s["file"])}
            for s in sources
        ],
    }
    (OUT / f"knowledge-{VERSION}.json").write_text(json.dumps(manifest, indent=2, ensure_ascii=False), encoding="utf-8")
    (OUT / f"report-{VERSION}.json").write_text(
        json.dumps({"passages": passages, "samples": samples}, indent=1, ensure_ascii=False), encoding="utf-8"
    )
    print(json.dumps({k: manifest[k] for k in ("file", "points", "size_bytes", "sha256")}, indent=1))
    for s in samples:
        print(f"\nQ: {s['query']}")
        for h in s["hits"]:
            print(f"   {h['score']:.4f} [{h['source']} p{h['page']}] {h['text'][:110]}")


if __name__ == "__main__":
    main()
