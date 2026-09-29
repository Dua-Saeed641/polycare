"""Team features that sit on top of the sync gateway (`app/main.py`).

What the sync foundation does not cover, and this adds without changing its protocol:

* **Gaps and supervisor answers.** Phones push a strict `GAP` op (`{"query": ...}`, validated in
  `main._validate_cloud_op`). Supervisors read the grouped, unanswered questions and answer them; the
  asking phone fetches the answer on its next sync (`GET /v1/answers`).
* **Outbreak Radar alerts.** Cluster the de-identified signal embeddings across all villages and
  hand the alerts back (`GET /v1/radar/alerts`).
* **A supervisor dashboard** (`/dashboard`) over both.
* **Team memory.** Shared tips (`TIP` ops) and votes (`VOTE` ops); a semantic Merkle index over the
  tips so a phone can find what it is missing by comparing a few hashes (`/v1/merkle`,
  `/v1/merkle/leaf`, `POST /v1/ops/fetch`); vote counts (`/v1/votes`).
* **Guidance cards.** A supervisor writes a card for the villages an outbreak alert names; phones in
  those villages fetch it on their next sync (`/v1/guidance`).

Everything is stored in Qdrant like the rest of the gateway: answers in a payload-only collection,
gaps read from the already-stored signed `sync_ops`, signals from the `signals` collection.
Supervisor endpoints need `POLYCARE_SUPERVISOR_TOKEN` (at least 16 characters).
"""

from __future__ import annotations

import hmac
import os
import struct
import time
import uuid
from collections import Counter
from datetime import UTC, datetime
from pathlib import Path
from typing import Annotated, Any, Callable

from fastapi import APIRouter, Depends, Header, HTTPException, Query
from fastapi.responses import FileResponse
from pydantic import BaseModel, ConfigDict, Field
from qdrant_client import QdrantClient, models

from . import radar
from .merkle import Entry, SemanticMerkle

STATIC = Path(__file__).resolve().parent / "static"


class AnswerRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    question: str = Field(min_length=1, max_length=400)
    answer: str = Field(min_length=1, max_length=2000)
    author: str = Field(default="Supervisor", min_length=1, max_length=80)


class GuidanceRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    title: str = Field(min_length=1, max_length=120)
    body: str = Field(min_length=1, max_length=1500)
    #: Village codes the card is for; empty means every village.
    villages: list[Annotated[str, Field(pattern=r"^[A-Za-z0-9_-]{1,64}$")]] = Field(default_factory=list, max_length=100)
    author: str = Field(default="Supervisor", min_length=1, max_length=80)


class FetchRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    op_ids: list[str] = Field(min_length=1, max_length=100)


def make_device_or_supervisor(authenticate: Callable[..., str]) -> Callable[..., None]:
    """A dependency that accepts a device bearer token *or* the supervisor token."""

    device_or_supervisor = make_device_or_supervisor(authenticate)

    # ------------------------------------------------------------------ gaps and answers
    def _answers(client: QdrantClient) -> list[dict[str, Any]]:
        return [p.payload for p in _scroll_all(client, "answers", with_payload=True, with_vectors=False)]

    @router.get("/v1/supervisor/gaps", dependencies=[Depends(supervisor)])
    def open_gaps(client: Annotated[QdrantClient, Depends(get_qdrant)]) -> list[dict[str, Any]]:
        """Unanswered questions grouped by text, most-asked first."""
        only_gaps = models.Filter(must=[models.FieldCondition(key="kind", match=models.MatchValue(value="GAP"))])
        answered = {a["question_key"] for a in _answers(client)}
        groups: dict[str, dict[str, Any]] = {}
        for point in _scroll_all(client, "sync_ops", scroll_filter=only_gaps, with_payload=True, with_vectors=False):
            query = str(point.payload["payload"].get("query", "")).strip()
            key = query.lower()
            if not key or key in answered:
                continue
            g = groups.setdefault(key, {"question": query, "asked": 0, "devices": set(), "last": 0})
            g["asked"] += 1
            g["devices"].add(point.payload["device_id"])
            g["last"] = max(g["last"], point.payload["hlc_wall_ms"])
        rows = [{"question": g["question"], "asked": g["asked"], "devices": len(g["devices"]), "last": g["last"]} for g in groups.values()]
        return sorted(rows, key=lambda r: (r["asked"], r["last"]), reverse=True)[:100]

    @router.post("/v1/supervisor/answers", dependencies=[Depends(supervisor)], status_code=201)
    def answer_gap(request: AnswerRequest, client: Annotated[QdrantClient, Depends(get_qdrant)]) -> dict[str, str]:
        now = _now_ms()
        ans_id = f"ans-{now}-{uuid.uuid4().hex[:6]}"
        client.upsert("answers", [models.PointStruct(id=point_id("answer", ans_id), vector={}, payload={
            "id": ans_id, "question": request.question.strip(), "question_key": request.question.strip().lower(),
            "answer": request.answer.strip(), "author": request.author.strip(), "answered_ms": now})], wait=True)
        return {"id": ans_id}

    @router.get("/v1/answers", dependencies=[Depends(device_or_supervisor)])
    def pull_answers(client: Annotated[QdrantClient, Depends(get_qdrant)], after_ms: Annotated[int, Query(ge=0)] = 0) -> dict[str, Any]:
        rows = sorted((a for a in _answers(client) if a["answered_ms"] > after_ms), key=lambda a: a["answered_ms"])[:100]
        return {
            "cursor": rows[-1]["answered_ms"] if rows else after_ms,
            "answers": [{"id": a["id"], "question": a["question"], "answer": a["answer"], "author": a["author"], "t": a["answered_ms"]} for a in rows],
        }

    # ------------------------------------------------------------------ outbreak radar
    def compute_alerts(client: QdrantClient) -> list[radar.Alert]:
        now = datetime.now(UTC)
        signals: list[radar.Signal] = []
        for point in _scroll_all(client, "signals", with_payload=True, with_vectors=True):
            p, vec = point.payload, point.vector
            values = vec.get("stub") if isinstance(vec, dict) else vec
            if not values:
                continue
            signals.append(radar.Signal(
                id=str(point.id), model_id=str(p.get("model_id", "")), vector=[float(x) for x in values],
                village=str(p.get("village_code", "")), category=str(p.get("age_band", "")),
                label=f"Similar symptoms, age {p.get('age_band', '?')}",
                wall_ms=week_start_ms(int(p.get("week", 0) or 0), now),
            ))
        return radar.detect(signals, int(now.timestamp() * 1000))

    def alert_json(a: radar.Alert) -> dict[str, Any]:
        return {"key": a.key, "label": a.label, "category": a.category, "level": a.level, "count": a.count,
                "villages": a.villages, "first": a.first, "last": a.last}

    @router.get("/v1/radar/alerts", dependencies=[Depends(device_or_supervisor)])
    def radar_alerts(client: Annotated[QdrantClient, Depends(get_qdrant)]) -> dict[str, Any]:
        return {"alerts": [alert_json(a) for a in compute_alerts(client)]}

    @router.get("/v1/supervisor/stats", dependencies=[Depends(supervisor)])
    def stats(client: Annotated[QdrantClient, Depends(get_qdrant)]) -> dict[str, int]:
        kinds = Counter(p.payload["kind"] for p in _scroll_all(client, "sync_ops", with_payload=True, with_vectors=False))
        return {
            "signals": kinds.get("SIGNAL", 0), "gaps": kinds.get("GAP", 0),
            "answers": len(_answers(client)),
            "devices": len(_scroll_all(client, "devices", with_payload=False, with_vectors=False)),
        }

    # ------------------------------------------------------------------ team memory
    def _tip_points(client: QdrantClient) -> list[Any]:
        only_tips = models.Filter(must=[models.FieldCondition(key="kind", match=models.MatchValue(value="TIP"))])
        return _scroll_all(client, "sync_ops", scroll_filter=only_tips, with_payload=True, with_vectors=False)

    def _tree(client: QdrantClient) -> SemanticMerkle:
        return SemanticMerkle([
            Entry(p.payload["op_id"], int(p.payload["payload"]["simhash"]), int(p.payload["hlc_wall_ms"]), int(p.payload["hlc_logical"]))
            for p in _tip_points(client)
        ])

    @router.get("/v1/merkle", dependencies=[Depends(device_or_supervisor)])
    def merkle_children(
        client: Annotated[QdrantClient, Depends(get_qdrant)],
        prefix: Annotated[str, Query(pattern=r"^[0-9a-f]{0,3}$")] = "",
    ) -> dict[str, Any]:
        """The 16 child hashes of `prefix` (0-3 hex digits); see app/merkle.py for the rules."""
        return {"prefix": prefix, "children": _tree(client).children(prefix)}

    @router.get("/v1/merkle/leaf", dependencies=[Depends(device_or_supervisor)])
    def merkle_leaf(
        client: Annotated[QdrantClient, Depends(get_qdrant)],
        region: Annotated[str, Query(pattern=r"^[0-9a-f]{4}$")],
    ) -> dict[str, Any]:
        ops = _tree(client).leaf_ops(region)
        return {"region": region, "ops": [{"op_id": e.op_id, "wall_ms": e.wall_ms, "logical": e.logical} for e in ops]}

    @router.post("/v1/ops/fetch", dependencies=[Depends(device_or_supervisor)])
    def fetch_ops(request: FetchRequest, client: Annotated[QdrantClient, Depends(get_qdrant)]) -> dict[str, Any]:
        """Specific tip ops by id, in the same shape as `/v1/ops/pull`, so the phone can verify signatures."""
        rows = client.retrieve("sync_ops", ids=request.op_ids, with_payload=True, with_vectors=False)
        items = []
        for row in rows:
            r = row.payload
            if r.get("kind") != "TIP":
                continue
            items.append({
                "op_id": r["op_id"], "device_id": r["device_id"],
                "hlc": {"wall_ms": r["hlc_wall_ms"], "logical": r["hlc_logical"], "node": r["hlc_node"]},
                "kind": r["kind"], "payload": r["payload"], "prev_hash": r["prev_hash"],
                "signature": r["signature"], "public_key": r["public_key"],
            })
        return {"ops": items}

    @router.get("/v1/votes", dependencies=[Depends(device_or_supervisor)])
    def votes(client: Annotated[QdrantClient, Depends(get_qdrant)]) -> dict[str, Any]:
        """Distinct devices that voted for each tip (a device voting twice counts once)."""
        only_votes = models.Filter(must=[models.FieldCondition(key="kind", match=models.MatchValue(value="VOTE"))])
        by_tip: dict[str, set[str]] = {}
        for point in _scroll_all(client, "sync_ops", scroll_filter=only_votes, with_payload=True, with_vectors=False):
            by_tip.setdefault(str(point.payload["payload"].get("cloud_point_id", "")), set()).add(point.payload["device_id"])
        return {"votes": {tip: len(devices) for tip, devices in by_tip.items() if tip}}

    # ------------------------------------------------------------------ guidance cards
    @router.post("/v1/supervisor/guidance", dependencies=[Depends(supervisor)], status_code=201)
    def post_guidance(request: GuidanceRequest, client: Annotated[QdrantClient, Depends(get_qdrant)]) -> dict[str, str]:
        now = _now_ms()
        card_id = f"gd-{now}-{uuid.uuid4().hex[:6]}"
        client.upsert("guidance", [models.PointStruct(id=point_id("guidance", card_id), vector={}, payload={
            "id": card_id, "title": request.title.strip(), "body": request.body.strip(), "villages": request.villages,
            "author": request.author.strip(), "created_ms": now})], wait=True)
        return {"id": card_id}

    @router.get("/v1/guidance", dependencies=[Depends(device_or_supervisor)])
    def pull_guidance(
        client: Annotated[QdrantClient, Depends(get_qdrant)],
        after_ms: Annotated[int, Query(ge=0)] = 0,
        village_code: Annotated[str, Query(max_length=64)] = "",
    ) -> dict[str, Any]:
        cards = [p.payload for p in _scroll_all(client, "guidance", with_payload=True, with_vectors=False)]
        rows = sorted(
            (c for c in cards if c["created_ms"] > after_ms and (not c["villages"] or village_code in c["villages"])),
            key=lambda c: c["created_ms"],
        )[:50]
        return {
            "cursor": rows[-1]["created_ms"] if rows else after_ms,
            "cards": [{"id": c["id"], "title": c["title"], "body": c["body"], "villages": c["villages"],
                       "author": c["author"], "t": c["created_ms"]} for c in rows],
        }

    @router.get("/v1/supervisor/qa", dependencies=[Depends(supervisor)])
    def answered_questions(client: Annotated[QdrantClient, Depends(get_qdrant)]) -> list[dict[str, Any]]:
        """Question/answer pairs supervisors wrote. The Skill Factory trains on these (with the knowledge base)."""
        return [{"question": a["question"], "answer": a["answer"], "author": a["author"], "t": a["answered_ms"]} for a in _answers(client)]

    @router.get("/dashboard", include_in_schema=False)
    def dashboard() -> FileResponse:
        return FileResponse(STATIC / "dashboard.html")

    return router


def unpack_f16(raw: bytes) -> tuple[float, ...]:
    """The 384 little-endian float16 values of a signal's `dense_f16`."""
    return struct.unpack("<384e", raw)
