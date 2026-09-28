"""Builds phone assets and test fixtures for the multilingual-e5-small embedder.

    pip install -r tools/requirements.txt   # onnxruntime pinned to the app's version
    python tools/models/build_e5_assets.py

Inputs  (downloaded by fetch_models.sh): tools/models/multilingual-e5-small/{tokenizer.json, model_quantized.onnx}
Outputs:
  tools/models/multilingual-e5-small/e5_tokenizer.bin
      Compact tokenizer for the phone (the 17 MB tokenizer.json is too slow to parse there).
      Layout, little-endian:
        magic "PCTK", u32 version=1, u32 unk_id, u32 bos_id, u32 eos_id, u32 pad_id,
        u32 charsmap_len, charsmap bytes (SentencePiece precompiled nmt_nfkc),
        u32 vocab_size, then per piece: f32 score, u16 utf8_len, utf8 bytes
  android/core-embed/src/test/resources/e5_reference.json
      Reference token ids (HF `tokenizers`) and embeddings (onnxruntime, same int8 file) used by
      the Kotlin parity tests.
"""

from __future__ import annotations

import base64
import json
import struct
from pathlib import Path

import numpy as np
import onnxruntime as ort
from tokenizers import Tokenizer

ROOT = Path(__file__).resolve().parents[2]
MODEL_DIR = ROOT / "tools" / "models" / "multilingual-e5-small"
FIXTURE = ROOT / "android" / "core-embed" / "src" / "test" / "resources" / "e5_reference.json"

# Covers English, Hindi and other Indian scripts, numbers, units, punctuation, unusual
# whitespace, full-width forms, accents, emoji and unknown characters.
SENTENCES = [
    "query: how many ANC visits does a pregnant woman need?",
    "passage: Every pregnant woman should have at least four antenatal check-ups.",
    "query: गर्भवती महिला को आयरन की गोली कब देनी चाहिए?",
    "passage: गर्भावस्था के दौरान 180 दिनों तक आयरन-फोलिक एसिड की एक गोली रोज़ दें।",
    "query: नवजात शिशु में खतरे के लक्षण क्या हैं?",
    "passage: If the baby is not feeding well, has fever or fast breathing, refer to the PHC immediately.",
    "query: ORS घोल कैसे बनाएं",
    "passage: Mix one packet of ORS in one litre of clean drinking water.",
    "BP 140/90 mmHg, Hb 8.5 g/dL, weight 52.4 kg",
    "E-21 fault, MUAC < 11.5 cm (SAM)",
    "  Double  spaces\tand\ttabs\nand newlines  ",
    "ＦＵＬＬ ｗｉｄｔｈ １２３ and café naïve résumé",
    "Emoji 🤰🏽 and symbols ™ © ½ ① …",
    "বাংলা: শিশুর জ্বর হলে কী করবেন?",
    "मराठी: बाळाला ताप आला तर काय करावे?",
    "தமிழ்: குழந்தைக்கு காய்ச்சல்",
    "తెలుగు: గర్భిణీ స్త్రీకి పోషణ",
    "ਪੰਜਾਬੀ: ਟੀਕਾਕਰਨ ਸਮਾਂ-ਸਾਰਣੀ",
    "ગુજરાતી: રસીકરણ",
    "Mixed हिंदी and English: BCG, OPV-0 और Hepatitis B जन्म के समय",
    "",
    "a",
    "ज़्यादा क़लम ड़ ढ़ ग़",
    "Zero​width‌joiner‍test",
    "𝒮𝓅𝑒𝒸𝒾𝒶𝓁 math letters and  nbsp",
]


def build_tokenizer_bin(tok_json: dict, out: Path) -> None:
    model = tok_json["model"]
    assert model["type"] == "Unigram"
    charsmap = base64.b64decode(tok_json["normalizer"]["normalizers"][0]["precompiled_charsmap"])
    special = {t["content"]: t["id"] for t in tok_json["added_tokens"]}
    with out.open("wb") as f:
        f.write(b"PCTK")
        f.write(struct.pack("<IIIII", 1, model["unk_id"], special["<s>"], special["</s>"], special["<pad>"]))
        f.write(struct.pack("<I", len(charsmap)))
        f.write(charsmap)
        f.write(struct.pack("<I", len(model["vocab"])))
        for piece, score in model["vocab"]:
            b = piece.encode("utf-8")
            f.write(struct.pack("<fH", score, len(b)))
            f.write(b)


def embed(session: ort.InferenceSession, ids: list[int]) -> np.ndarray:
    input_ids = np.array([ids], dtype=np.int64)
    mask = np.ones_like(input_ids)
    out = session.run(None, {"input_ids": input_ids, "attention_mask": mask, "token_type_ids": np.zeros_like(input_ids)})[0]
    pooled = (out * mask[..., None]).sum(axis=1) / mask.sum(axis=1, keepdims=True)
    return (pooled / np.linalg.norm(pooled, axis=1, keepdims=True))[0]


def main() -> None:
    tok_json = json.loads((MODEL_DIR / "tokenizer.json").read_text(encoding="utf-8"))
    build_tokenizer_bin(tok_json, MODEL_DIR / "e5_tokenizer.bin")

    tokenizer = Tokenizer.from_file(str(MODEL_DIR / "tokenizer.json"))
    session = ort.InferenceSession(str(MODEL_DIR / "model_quantized.onnx"))
    cases = []
    for s in SENTENCES:
        ids = tokenizer.encode(s).ids
        cases.append({"text": s, "ids": ids, "embedding": [round(float(x), 6) for x in embed(session, ids)]})
    FIXTURE.parent.mkdir(parents=True, exist_ok=True)
    FIXTURE.write_text(json.dumps({"model": "Xenova/multilingual-e5-small@761b726 int8", "cases": cases}, ensure_ascii=False), encoding="utf-8")
    print(f"wrote {MODEL_DIR / 'e5_tokenizer.bin'} and {FIXTURE} ({len(cases)} cases)")


if __name__ == "__main__":
    main()
