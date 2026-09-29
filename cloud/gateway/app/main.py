from __future__ import annotations

import base64
import hashlib
import hmac
import json
import os
import re
import secrets
import threading
import uuid
from contextlib import asynccontextmanager
from datetime import UTC, datetime, timedelta
from typing import Annotated, Any

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey
from fastapi import Depends, FastAPI, Header, HTTPException, Query, status
from pydantic import BaseModel, ConfigDict, Field, field_validator
from qdrant_client import QdrantClient, models

ZERO_HASH = bytes(32)
TOKEN_LIFETIME = timedelta(minutes=15)
UUID7_RE = re.compile(r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-7[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")
PRIVATE_FIELDS = {"household", "household_id", "member", "member_id", "patient", "name", "phone", "address", "aadhaar"}
SIGNAL_FIELDS = {"dense_f16", "model_id", "simhash", "village_code", "week", "age_band", "sex"}
PHONE_RE = re.compile(r"(?<!\d)(?:\+?91[ -]?)?[6-9]\d{9}(?!\d)")
AADHAAR_RE = re.compile(r"(?<!\d)\d{12}(?!\d)")
EMAIL_RE = re.compile(r"\b[\w.+-]+@[\w.-]+\.[A-Za-z]{2,}\b")


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class DeviceRegistration(StrictModel):
    device_id: str = Field(min_length=1, max_length=128, pattern=r"^[A-Za-z0-9._:-]+$")
    public_key: str


class ChallengeRequest(StrictModel):
    device_id: str = Field(min_length=1, max_length=128)


class ChallengeResponse(StrictModel):
    challenge_id: uuid.UUID
    device_id: str = Field(min_length=1, max_length=128)
    signature: str


class Hlc(StrictModel):
    wall_ms: int = Field(ge=0)
    logical: int = Field(ge=0, le=2**31 - 1)
    node: str = Field(min_length=1, max_length=128)


class Op(StrictModel):
    op_id: str
    device_id: str = Field(min_length=1, max_length=128)
    hlc: Hlc
    kind: str = Field(min_length=1, max_length=64)
    payload: dict[str, Any]
    prev_hash: str
    signature: str

    @field_validator("op_id")
    @classmethod
    def require_uuid7(cls, value: str) -> str:
        if not UUID7_RE.fullmatch(value):
            raise ValueError("op_id must be a UUIDv7")
        return value.lower()


class PushRequest(StrictModel):
    ops: list[Op] = Field(min_length=1, max_length=256)


def _decode_b64(value: str, size: int | None = None) -> bytes:
    try:
        decoded = base64.b64decode(value, validate=True)
    except (ValueError, TypeError) as exc:
        raise HTTPException(422, "Invalid base64 value") from exc
    if size is not None and len(decoded) != size:
        raise HTTPException(422, "Invalid encoded value length")
    return decoded


def _canonical(value: dict[str, Any]) -> bytes:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()


def _signed_fields(op: Op) -> dict[str, Any]:
    return {"op_id": op.op_id, "device_id": op.device_id, "hlc": op.hlc.model_dump(mode="json"),
            "kind": op.kind, "payload": op.payload, "prev_hash": _decode_b64(op.prev_hash, 32).hex()}


def _scan_private(value: Any) -> bool:
    if isinstance(value, dict):
        return any(key.lower() in PRIVATE_FIELDS or _scan_private(child) for key, child in value.items())
    if isinstance(value, list):
        return any(_scan_private(child) for child in value)
    return isinstance(value, str) and bool(PHONE_RE.search(value) or AADHAAR_RE.search(value) or EMAIL_RE.search(value))


def _validate_cloud_op(op: Op) -> None:
    kind = op.kind.upper()
    if kind in {"HOUSEHOLD", "HOUSEHOLDS", "VISIT", "MEMBER", "PATIENT"}:
        raise HTTPException(403, "Personal health records are local-only")
    if _scan_private(op.payload):
        raise HTTPException(403, "Operation contains a possible personal identifier")
    if kind == "VOTE":
        if set(op.payload) != {"cloud_point_id"} or not isinstance(op.payload["cloud_point_id"], str):
            raise HTTPException(422, "Invalid vote payload")
        return
    if kind != "SIGNAL" or set(op.payload) != SIGNAL_FIELDS:
        raise HTTPException(422, "Operation kind or payload is not enabled for cloud sync")
    p = op.payload
    raw_vector = _decode_b64(p["dense_f16"], 768)
    import math
    import struct
    if not all(math.isfinite(value) for value in struct.unpack("<384e", raw_vector)):
        raise HTTPException(422, "Signal vector must be 384 float16 values")
    if not isinstance(p["model_id"], str) or not re.fullmatch(r"[A-Za-z0-9._:-]{1,128}", p["model_id"]):
        raise HTTPException(422, "Invalid signal model id")
    if not isinstance(p["village_code"], str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", p["village_code"]):
        raise HTTPException(422, "Invalid village code")
    if p["age_band"] not in {"0-1", "1-4", "5-9", "10-14", "15-19", "20-29", "30-39", "40-49", "50+"} or p["sex"] not in {"F", "M", "U"}:
        raise HTTPException(422, "Invalid signal demographics")
    if type(p["week"]) is not int or not 1 <= p["week"] <= 53 or type(p["simhash"]) is not int or not 0 <= p["simhash"] <= 65535:
        raise HTTPException(422, "Invalid signal week or simhash")


def _b64url(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode()


def _unb64url(value: str) -> bytes:
    return base64.urlsafe_b64decode(value + "=" * (-len(value) % 4))


def _secret(name: str) -> bytes:
    value = os.environ.get(name, "")
    if len(value) < 32:
        raise HTTPException(503, f"{name} is not configured")
    return value.encode()


def _token(device_id: str, secret: bytes) -> str:
    payload = _b64url(_canonical({"sub": device_id, "exp": int((datetime.now(UTC) + TOKEN_LIFETIME).timestamp())}))
    sig = _b64url(hmac.new(secret, payload.encode("ascii"), hashlib.sha256).digest())
    return f"{payload}.{sig}"


def _authenticate(authorization: Annotated[str | None, Header()] = None) -> str:
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(401, "Bearer token required")
    try:
        payload_b64, sig_b64 = authorization.removeprefix("Bearer ").split(".", 1)
        payload, signature = json.loads(_unb64url(payload_b64)), _unb64url(sig_b64)
        expected = hmac.new(_secret("POLYCARE_TOKEN_SECRET"), payload_b64.encode("ascii"), hashlib.sha256).digest()
    except (ValueError, json.JSONDecodeError) as exc:
        raise HTTPException(401, "Invalid bearer token") from exc
    if not hmac.compare_digest(signature, expected) or payload.get("exp", 0) < int(datetime.now(UTC).timestamp()):
        raise HTTPException(401, "Expired or invalid bearer token")
    return str(payload["sub"])


def _pid(namespace: str, key: str) -> str:
    return str(uuid.uuid5(uuid.uuid5(uuid.NAMESPACE_URL, "polycare:" + namespace), key))


def _collection(client: QdrantClient, name: str, vector_size: int | None) -> None:
    if not client.collection_exists(name):
        config = {} if vector_size is None else {"stub": models.VectorParams(size=vector_size, distance=models.Distance.COSINE)}
        client.create_collection(name, vectors_config=config)


def _point(client: QdrantClient, collection: str, key: str, namespace: str) -> dict[str, Any] | None:
    point_id = key if namespace == "op" else _pid(namespace, key)
    rows = client.retrieve(collection, ids=[point_id], with_payload=True, with_vectors=False)
    return rows[0].payload if rows else None


@asynccontextmanager
async def lifespan(application: FastAPI):
    for name in ("POLYCARE_ENROLLMENT_TOKEN", "POLYCARE_TOKEN_SECRET"):
        if len(os.environ.get(name, "")) < 32:
            raise RuntimeError(f"{name} must contain at least 32 characters")
    url, api_key = os.environ.get("QDRANT_URL", ""), os.environ.get("QDRANT_API_KEY", "")
    if not url.startswith("https://") or len(api_key) < 16:
        raise RuntimeError("QDRANT_URL (https) and QDRANT_API_KEY must be configured")
    client = QdrantClient(url=url, api_key=api_key, timeout=15)
    _collection(client, "devices", None)
    _collection(client, "auth_challenges", None)
    _collection(client, "sync_ops", None)
    _collection(client, "signals", 384)
    _collection(client, "knowledge", 384)
    application.state.qdrant = client
    application.state.write_lock = threading.RLock()
    application.state.embedder_lock = threading.Lock()
    application.state.embedder = None
    yield
    client.close()


app = FastAPI(title="PolyCare Sync Gateway", version="0.2.0", lifespan=lifespan)


def _qdrant() -> QdrantClient:
    return app.state.qdrant


@app.get("/healthz")
def health(client: Annotated[QdrantClient, Depends(_qdrant)]) -> dict[str, str]:
    client.get_collections()
    return {"status": "ok", "database": "qdrant-cloud"}


@app.post("/v1/devices/register", status_code=status.HTTP_201_CREATED)
def register_device(request: DeviceRegistration, client: Annotated[QdrantClient, Depends(_qdrant)],
                    enrollment_token: Annotated[str | None, Header(alias="X-Enrollment-Token")] = None) -> dict[str, str]:
    expected = os.environ.get("POLYCARE_ENROLLMENT_TOKEN", "")
    if not enrollment_token or not hmac.compare_digest(enrollment_token, expected):
        raise HTTPException(401, "Valid enrollment token required")
    key = _decode_b64(request.public_key, 32)
    with app.state.write_lock:
        existing = _point(client, "devices", request.device_id, "device")
        if existing and not hmac.compare_digest(base64.b64decode(existing["public_key"]), key):
            raise HTTPException(409, "Device is already registered with another key")
        if existing:
            return {"device_id": request.device_id, "status": "registered"}
        client.upsert("devices", [models.PointStruct(id=_pid("device", request.device_id), vector={},
                     payload={"device_id": request.device_id, "public_key": base64.b64encode(key).decode(),
                              "chain_head": base64.b64encode(ZERO_HASH).decode(), "last_hlc_wall_ms": 0,
                              "last_hlc_logical": 0, "last_seen_at": datetime.now(UTC).isoformat()})], wait=True)
    return {"device_id": request.device_id, "status": "registered"}


@app.post("/v1/auth/challenge")
def create_challenge(request: ChallengeRequest, client: Annotated[QdrantClient, Depends(_qdrant)]) -> dict[str, Any]:
    if not _point(client, "devices", request.device_id, "device"):
        raise HTTPException(404, "Device is not registered")
    challenge_id, nonce = uuid.uuid4(), secrets.token_bytes(32)
    expires = datetime.now(UTC) + timedelta(minutes=2)
    stale_ids: list[str | int] = []
    offset = None
    while True:
        points, offset = client.scroll("auth_challenges", limit=256, offset=offset, with_payload=True, with_vectors=False)
        stale_ids.extend(str(point.id) for point in points if point.payload.get("consumed") or datetime.fromisoformat(point.payload["expires_at"]) <= datetime.now(UTC))
        if offset is None:
            break
    if stale_ids:
        client.delete("auth_challenges", points_selector=models.PointIdsList(points=stale_ids), wait=True)
    client.upsert("auth_challenges", [models.PointStruct(id=str(challenge_id), vector={}, payload={
        "device_id": request.device_id, "nonce": base64.b64encode(nonce).decode(), "expires_at": expires.isoformat(), "consumed": False})], wait=True)
    return {"challenge_id": str(challenge_id), "nonce": base64.b64encode(nonce).decode(), "expires_at": expires.isoformat()}


@app.post("/v1/auth/verify")
def verify_challenge(request: ChallengeResponse, client: Annotated[QdrantClient, Depends(_qdrant)]) -> dict[str, str]:
    with app.state.write_lock:
        rows = client.retrieve("auth_challenges", ids=[str(request.challenge_id)], with_payload=True, with_vectors=False)
        challenge = rows[0].payload if rows else None
        device = _point(client, "devices", request.device_id, "device")
        if not challenge or not device or challenge["device_id"] != request.device_id or challenge["consumed"] or datetime.fromisoformat(challenge["expires_at"]) <= datetime.now(UTC):
            raise HTTPException(401, "Challenge is missing, expired, or already used")
        try:
            Ed25519PublicKey.from_public_bytes(base64.b64decode(device["public_key"])).verify(
                _decode_b64(request.signature, 64), base64.b64decode(challenge["nonce"]))
        except InvalidSignature as exc:
            raise HTTPException(401, "Challenge signature is invalid") from exc
        client.set_payload("auth_challenges", {"consumed": True}, points=[str(request.challenge_id)], wait=True)
        client.set_payload("devices", {"last_seen_at": datetime.now(UTC).isoformat()}, points=[_pid("device", request.device_id)], wait=True)
    return {"access_token": _token(request.device_id, _secret("POLYCARE_TOKEN_SECRET")), "token_type": "bearer"}


def _all_ops(client: QdrantClient) -> list[dict[str, Any]]:
    records, offset = [], None
    while True:
        points, offset = client.scroll("sync_ops", limit=256, offset=offset, with_payload=True, with_vectors=False)
        records.extend(point.payload for point in points)
        if offset is None:
            return records


@app.post("/v1/ops/push")
def push_ops(request: PushRequest, device_id: Annotated[str, Depends(_authenticate)],
             client: Annotated[QdrantClient, Depends(_qdrant)]) -> dict[str, Any]:
    accepted, duplicates = [], []
    with app.state.write_lock:
        device = _point(client, "devices", device_id, "device")
        if not device:
            raise HTTPException(401, "Device is not registered")
        head, last_hlc = base64.b64decode(device["chain_head"]), (device["last_hlc_wall_ms"], device["last_hlc_logical"])
        public_key = Ed25519PublicKey.from_public_bytes(base64.b64decode(device["public_key"]))
        for op in request.ops:
            if op.device_id != device_id or op.hlc.node != device_id:
                raise HTTPException(403, "Operation device or HLC node mismatch")
            if op.hlc.wall_ms > int(datetime.now(UTC).timestamp() * 1000) + 300_000:
                raise HTTPException(422, "Operation HLC is too far in the future")
            _validate_cloud_op(op)
            signature = _decode_b64(op.signature, 64)
            signed = _canonical(_signed_fields(op))
            try:
                public_key.verify(signature, signed)
            except InvalidSignature as exc:
                raise HTTPException(401, "Operation signature is invalid") from exc
            op_hash = hashlib.sha256(signed + signature).digest()
            old = _point(client, "sync_ops", op.op_id, "op")
            if old:
                if old["device_id"] != device_id or old["op_hash"] != base64.b64encode(op_hash).decode():
                    raise HTTPException(409, "Operation id was reused with different content")
                if hmac.compare_digest(_decode_b64(op.prev_hash, 32), head) and (op.hlc.wall_ms, op.hlc.logical) > last_hlc:
                    head, last_hlc = op_hash, (op.hlc.wall_ms, op.hlc.logical)
                    client.set_payload("devices", {"chain_head": base64.b64encode(head).decode(),
                        "last_hlc_wall_ms": last_hlc[0], "last_hlc_logical": last_hlc[1]},
                        points=[_pid("device", device_id)], wait=True)
                if op.kind.upper() == "SIGNAL":
                    import struct
                    vec = struct.unpack("<384e", _decode_b64(op.payload["dense_f16"], 768))
                    client.upsert("signals", [models.PointStruct(id=op.op_id, vector={"stub": list(map(float, vec))}, payload={
                        k: op.payload[k] for k in ("model_id", "simhash", "village_code", "week", "age_band", "sex")})], wait=True)
                duplicates.append(op.op_id)
                continue
            if not hmac.compare_digest(_decode_b64(op.prev_hash, 32), head) or (op.hlc.wall_ms, op.hlc.logical) <= last_hlc:
                raise HTTPException(409, "Operation chain or HLC does not continue from server head")
            point_payload = {"op_id": op.op_id, "device_id": device_id, "hlc_wall_ms": op.hlc.wall_ms,
                "hlc_logical": op.hlc.logical, "hlc_node": op.hlc.node, "kind": op.kind.upper(),
                "payload": op.payload, "prev_hash": op.prev_hash, "signature": op.signature,
                "public_key": device["public_key"], "op_hash": base64.b64encode(op_hash).decode()}
            client.upsert("sync_ops", [models.PointStruct(id=op.op_id, vector={}, payload=point_payload)], wait=True)
            if op.kind.upper() == "SIGNAL":
                import struct
                vec = struct.unpack("<384e", _decode_b64(op.payload["dense_f16"], 768))
                client.upsert("signals", [models.PointStruct(id=op.op_id, vector={"stub": list(map(float, vec))}, payload={
                    k: op.payload[k] for k in ("model_id", "simhash", "village_code", "week", "age_band", "sex")})], wait=True)
            head, last_hlc = op_hash, (op.hlc.wall_ms, op.hlc.logical)
            client.set_payload("devices", {"chain_head": base64.b64encode(head).decode(), "last_hlc_wall_ms": last_hlc[0],
                              "last_hlc_logical": last_hlc[1], "last_seen_at": datetime.now(UTC).isoformat()},
                              points=[_pid("device", device_id)], wait=True)
            accepted.append(op.op_id)
    return {"accepted_op_ids": accepted, "duplicate_op_ids": duplicates, "chain_head": base64.b64encode(head).decode()}


@app.get("/v1/ops/pull")
def pull_ops(device_id: Annotated[str, Depends(_authenticate)], client: Annotated[QdrantClient, Depends(_qdrant)],
             after_wall_ms: Annotated[int, Query(ge=0)] = 0, after_logical: Annotated[int, Query(ge=0)] = 0,
             after_device: Annotated[str, Query(max_length=128)] = "", after_op_id: Annotated[str, Query(max_length=36)] = "",
             limit: Annotated[int, Query(ge=1, le=256)] = 100) -> dict[str, Any]:
    del device_id
    records = _all_ops(client)
    records.sort(key=lambda x: (x["hlc_wall_ms"], x["hlc_logical"], x["device_id"], x["op_id"]))
    cursor_key = (after_wall_ms, after_logical, after_device, after_op_id)
    rows = [r for r in records if (r["hlc_wall_ms"], r["hlc_logical"], r["device_id"], r["op_id"]) > cursor_key][:limit]
    items = [{"op_id": r["op_id"], "device_id": r["device_id"], "hlc": {"wall_ms": r["hlc_wall_ms"], "logical": r["hlc_logical"], "node": r["hlc_node"]},
              "kind": r["kind"], "payload": r["payload"], "prev_hash": r["prev_hash"], "signature": r["signature"], "public_key": r["public_key"]} for r in rows]
    last = rows[-1] if rows else None
    cursor = None if last is None else {"wall_ms": last["hlc_wall_ms"], "logical": last["hlc_logical"], "device_id": last["device_id"], "op_id": last["op_id"]}
    return {"ops": items, "next_cursor": cursor, "has_more": len(rows) == limit}


class KnowledgeDocument(StrictModel):
    document_id: str = Field(min_length=1, max_length=256, pattern=r"^[A-Za-z0-9._:-]+$")
    language: str = Field(min_length=2, max_length=16)
    text: str = Field(min_length=1, max_length=12000)
    source_version: str = Field(min_length=1, max_length=128)


def _embedder() -> Any:
    if app.state.embedder is None:
        with app.state.embedder_lock:
            if app.state.embedder is None:
                from fastembed import TextEmbedding

                app.state.embedder = TextEmbedding(model_name="sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2")
    return app.state.embedder


@app.post("/v1/knowledge/{document_id}")
def upsert_knowledge(document_id: str, request: KnowledgeDocument,
                     client: Annotated[QdrantClient, Depends(_qdrant)],
                     authorization: Annotated[str | None, Header()] = None) -> dict[str, str]:
    expected = os.environ.get("POLYCARE_KNOWLEDGE_TOKEN", "")
    if len(expected) < 32 or not authorization or not hmac.compare_digest(authorization.removeprefix("Bearer "), expected):
        raise HTTPException(401, "Knowledge publisher token required")
    if request.document_id != document_id or _scan_private({"text": request.text}):
        raise HTTPException(422, "Document id mismatch or text contains a personal identifier")
    model_name = "sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2"
    embedder = _embedder()
    vector = next(embedder.embed([request.text]))
    point_id = _pid("knowledge", document_id)
    client.upsert("knowledge", [models.PointStruct(id=point_id, vector={"stub": list(map(float, vector))}, payload={
        "document_id": document_id, "language": request.language, "text": request.text,
        "source_version": request.source_version, "embedding_model": model_name,
        "updated_at": datetime.now(UTC).isoformat()})], wait=True)
    return {"document_id": document_id, "status": "indexed", "embedding_model": model_name}


@app.get("/v1/knowledge/search")
def search_knowledge(query: str = Query(min_length=2, max_length=2000),
                     client: Annotated[QdrantClient, Depends(_qdrant)] = None,
                     device_id: Annotated[str, Depends(_authenticate)] = "") -> dict[str, Any]:
    del device_id
    if _scan_private({"text": query}):
        raise HTTPException(403, "Query contains a possible personal identifier")
    vector = next(_embedder().query_embed([query]))
    hits = client.query_points("knowledge", query=list(map(float, vector)), using="stub", limit=8, with_payload=True).points
    return {"results": [{"document_id": hit.payload["document_id"], "language": hit.payload["language"],
        "text": hit.payload["text"], "source_version": hit.payload["source_version"], "score": hit.score}
        for hit in hits], "embedding_model": "sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2"}
