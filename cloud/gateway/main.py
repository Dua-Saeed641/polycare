"""PolyCare gateway.

Phones talk to this service, never to Qdrant Cloud directly (they hold no write keys). It:

* accepts **only** de-identified symptom signals and unanswered questions (gaps) and rejects
  everything else again on its side, so a phone-side bug can never leak a household (invariant 7);
* is idempotent on the op id (invariant 2) so a phone that was killed between the ack and its
  cursor write can safely re-send a chunk;
* runs the Outbreak Radar over all villages and hands the alerts back on the next pull;
* lets a supervisor answer a gap on the dashboard; the answer reaches the asking phone on its
  next pull.

Run:  uvicorn main:app --host 0.0.0.0 --port 8080
Env:  POLYCARE_TOKEN      bearer token phones must send (optional; open if unset)
      SUPERVISOR_TOKEN    bearer token needed to answer gaps (optional; falls back to POLYCARE_TOKEN)
      POLYCARE_DB         SQLite file (default data/polycare.db)
      QDRANT_URL/QDRANT_API_KEY   optional: also mirror signal vectors into a Qdrant collection
"""

from __future__ import annotations

import json
import os
import sqlite3
import threading
import time
from pathlib import Path
from typing import Any

from fastapi import Depends, FastAPI, Header, HTTPException, Query
from fastapi.responses import FileResponse
from pydantic import BaseModel, Field

import radar

VERSION = "0.2.0"
DB_PATH = Path(os.getenv("POLYCARE_DB", "data/polycare.db"))
STATIC = Path(__file__).parent / "static"

#: Only these may leave a phone. Everything else is personal health data.
ACCEPTED_ENTITIES = {"signal", "gap"}
#: Payload keys that identify a person or household; an op carrying one is refused outright.
FORBIDDEN_KEYS = {"name", "headofhousehold", "membername", "phone", "address", "notes", "householdid", "memberid"}

app = FastAPI(title="PolyCare gateway", version=VERSION)
_lock = threading.Lock()


def _db() -> sqlite3.Connection:
    DB_PATH.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(DB_PATH, check_same_thread=False)
    conn.row_factory = sqlite3.Row
    return conn


_conn = _db()
_conn.executescript(
    """
    CREATE TABLE IF NOT EXISTS ops (
        op_id TEXT PRIMARY KEY, device TEXT NOT NULL, entity TEXT NOT NULL, entity_id TEXT NOT NULL,
        action TEXT NOT NULL, payload TEXT NOT NULL, received_ms INTEGER NOT NULL
    );
    CREATE TABLE IF NOT EXISTS signals (
        op_id TEXT PRIMARY KEY, model_id TEXT NOT NULL, vector TEXT NOT NULL, village TEXT NOT NULL,
        category TEXT NOT NULL, label TEXT NOT NULL, wall_ms INTEGER NOT NULL, count INTEGER NOT NULL DEFAULT 1,
        dedup_key TEXT
    );
    CREATE INDEX IF NOT EXISTS signals_dedup ON signals(dedup_key);
    CREATE TABLE IF NOT EXISTS gaps (
        op_id TEXT PRIMARY KEY, question TEXT NOT NULL, confidence REAL, device TEXT NOT NULL,
        asked_ms INTEGER NOT NULL, answered INTEGER NOT NULL DEFAULT 0
    );
    CREATE TABLE IF NOT EXISTS answers (
        seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT UNIQUE NOT NULL, question TEXT NOT NULL,
        answer TEXT NOT NULL, author TEXT NOT NULL, answered_ms INTEGER NOT NULL
    );
    """
)


def _now_ms() -> int:
    return int(time.time() * 1000)


# ---------------------------------------------------------------------------------------- auth
def _bearer(authorization: str | None) -> str | None:
    if authorization and authorization.lower().startswith("bearer "):
        return authorization[7:].strip()
    return None


def require_device(authorization: str | None = Header(default=None)) -> None:
    expected = os.getenv("POLYCARE_TOKEN")
    if expected and _bearer(authorization) != expected:
        raise HTTPException(status_code=401, detail="bad token")


def require_supervisor(authorization: str | None = Header(default=None)) -> None:
    expected = os.getenv("SUPERVISOR_TOKEN") or os.getenv("POLYCARE_TOKEN")
    if expected and _bearer(authorization) != expected:
        raise HTTPException(status_code=401, detail="bad token")


# --------------------------------------------------------------------------------------- models
class PushBody(BaseModel):
    device: str
    ops: list[dict[str, Any]] = Field(default_factory=list)
    plusOnes: list[dict[str, Any]] = Field(default_factory=list)


class AnswerBody(BaseModel):
    question: str = Field(min_length=1, max_length=400)
    answer: str = Field(min_length=1, max_length=2000)
    author: str = Field(default="Supervisor", max_length=80)


# -------------------------------------------------------------------------------------- routes
@app.get("/v1/health")
def health() -> dict[str, str]:
    return {"service": "polycare-gateway", "version": VERSION}


@app.post("/v1/ops", dependencies=[Depends(require_device)])
def push(body: PushBody) -> dict[str, int]:
    accepted = duplicate = rejected = 0
    with _lock:
        for op in body.ops:
            try:
                op_id = str(op["id"])
                entity = str(op["e"])
                payload: dict[str, str] = {str(k): str(v) for k, v in dict(op.get("p", {})).items()}
                entity_id = str(op["eid"])
                action = str(op["a"])
            except (KeyError, TypeError, ValueError):
                rejected += 1
                continue
            # Invariant 7, enforced again here: personal data is refused, never stored.
            if entity not in ACCEPTED_ENTITIES or any(k.lower() in FORBIDDEN_KEYS for k in payload):
                rejected += 1
                continue
            cur = _conn.execute(
                "INSERT OR IGNORE INTO ops(op_id, device, entity, entity_id, action, payload, received_ms) VALUES (?,?,?,?,?,?,?)",
                (op_id, body.device, entity, entity_id, action, json.dumps(payload), _now_ms()),
            )
            if cur.rowcount == 0:
                duplicate += 1
                continue
            accepted += 1
            if entity == "signal":
                _store_signal(op_id, payload)
            elif entity == "gap" and action == "upsert":
                _conn.execute(
                    "INSERT OR IGNORE INTO gaps(op_id, question, confidence, device, asked_ms) VALUES (?,?,?,?,?)",
                    (op_id, payload.get("query", "")[:400], float(payload.get("confidence", "0") or 0), body.device, _now_ms()),
                )

        for p in body.plusOnes:  # a repeat report of a signal already sent: count it, don't store a copy
            key = str(p.get("dedupKey", ""))
            if key:
                _conn.execute("UPDATE signals SET count = count + 1 WHERE dedup_key = ?", (key,))
        _conn.commit()
    return {"accepted": accepted, "duplicate": duplicate, "rejected": rejected}


def _store_signal(op_id: str, p: dict[str, str]) -> None:
    vector = [float(x) for x in p.get("vector", "").split(",") if x.strip()]
    wall_ms = int(p.get("wallMs", "0") or 0) or _now_ms()
    _conn.execute(
        "INSERT OR IGNORE INTO signals(op_id, model_id, vector, village, category, label, wall_ms, dedup_key) VALUES (?,?,?,?,?,?,?,?)",
        (op_id, p.get("modelId", ""), json.dumps(vector), p.get("village", ""), p.get("category", ""),
         p.get("label", ""), wall_ms, p.get("dedupKey")),
    )
    _mirror_to_qdrant(op_id, vector, p)


def _mirror_to_qdrant(op_id: str, vector: list[float], p: dict[str, str]) -> None:
    """Optional: keep signal vectors in Qdrant Cloud too. Never lets a Qdrant problem fail a push."""
    url = os.getenv("QDRANT_URL")
    if not url or not vector:
        return
    try:
        import uuid

        from qdrant_client import QdrantClient, models

        client = QdrantClient(url=url, api_key=os.getenv("QDRANT_API_KEY"))
        if not client.collection_exists("signals"):
            client.create_collection("signals", vectors_config=models.VectorParams(size=len(vector), distance=models.Distance.COSINE))
        client.upsert(
            "signals",
            [models.PointStruct(
                id=str(uuid.uuid5(uuid.NAMESPACE_URL, op_id)), vector=vector,
                payload={"model_id": p.get("modelId"), "village": p.get("village"), "category": p.get("category"),
                         "label": p.get("label"), "wall_ms": int(p.get("wallMs", "0") or 0)},
            )],
        )
    except Exception:  # noqa: BLE001 - mirroring is best-effort by design
        pass


def _alerts() -> list[radar.Alert]:
    rows = _conn.execute("SELECT * FROM signals").fetchall()
    signals = [
        radar.Signal(r["op_id"], r["model_id"], json.loads(r["vector"]), r["village"], r["category"], r["label"], r["wall_ms"], r["count"])
        for r in rows
    ]
    return radar.detect(signals, _now_ms())


def _alert_json(a: radar.Alert) -> dict[str, Any]:
    return {"key": a.key, "label": a.label, "category": a.category, "level": a.level, "count": a.count,
            "villages": a.villages, "first": a.first, "last": a.last}


@app.get("/v1/pull", dependencies=[Depends(require_device)])
def pull(device: str = Query(""), since: int = Query(0)) -> dict[str, Any]:
    with _lock:
        rows = _conn.execute("SELECT * FROM answers WHERE seq > ? ORDER BY seq", (since,)).fetchall()
        cursor = max([since] + [r["seq"] for r in rows])
        return {
            "cursor": cursor,
            "alerts": [_alert_json(a) for a in _alerts()],
            "answers": [
                {"id": r["id"], "question": r["question"], "answer": r["answer"], "author": r["author"], "t": r["answered_ms"]}
                for r in rows
            ],
        }


@app.get("/v1/alerts")
def alerts() -> list[dict[str, Any]]:
    with _lock:
        return [_alert_json(a) for a in _alerts()]


@app.get("/v1/gaps")
def open_gaps() -> list[dict[str, Any]]:
    """Unanswered questions grouped by text, most-asked first."""
    with _lock:
        rows = _conn.execute(
            "SELECT lower(question) AS q, MIN(question) AS question, COUNT(*) AS asked, MAX(asked_ms) AS last_ms "
            "FROM gaps WHERE answered = 0 GROUP BY lower(question) ORDER BY asked DESC, last_ms DESC LIMIT 100"
        ).fetchall()
        return [{"question": r["question"], "asked": r["asked"], "last": r["last_ms"]} for r in rows]


@app.post("/v1/gaps/answer", dependencies=[Depends(require_supervisor)])
def answer_gap(body: AnswerBody) -> dict[str, Any]:
    with _lock:
        ans_id = f"ans-{_now_ms()}-{abs(hash(body.question.lower())) % 10_000}"
        _conn.execute(
            "INSERT INTO answers(id, question, answer, author, answered_ms) VALUES (?,?,?,?,?)",
            (ans_id, body.question, body.answer, body.author, _now_ms()),
        )
        _conn.execute("UPDATE gaps SET answered = 1 WHERE lower(question) = lower(?)", (body.question,))
        _conn.commit()
    return {"id": ans_id}


@app.get("/v1/stats")
def stats() -> dict[str, int]:
    with _lock:
        return {
            "signals": _conn.execute("SELECT COALESCE(SUM(count),0) FROM signals").fetchone()[0],
            "open_gaps": _conn.execute("SELECT COUNT(*) FROM gaps WHERE answered = 0").fetchone()[0],
            "answers": _conn.execute("SELECT COUNT(*) FROM answers").fetchone()[0],
            "devices": _conn.execute("SELECT COUNT(DISTINCT device) FROM ops").fetchone()[0],
        }


@app.get("/", include_in_schema=False)
def dashboard() -> FileResponse:
    return FileResponse(STATIC / "dashboard.html")
