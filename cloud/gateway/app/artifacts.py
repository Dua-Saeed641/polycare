"""Artifact delivery (ARCHITECTURE.md §6.4): signed skills and knowledge packages for phones.

The gateway only *serves* artifacts; it never signs them and holds no private key. A publisher
signs each entry offline with `tools/publish_artifact.py` (Ed25519 over the canonical JSON of
`{kind, name, version, sha256, size}`), and phones pin the publisher's public key, so a compromised
gateway cannot push a model onto a phone.

Layout of `POLYCARE_ARTIFACT_DIR` (default `data/artifacts`):
    index.json      [{kind, name, version, file, size, sha256, signature, ...}]
    <file>          the artifact itself

Downloads support HTTP `Range` so a phone on a bad connection resumes where it stopped.
"""

from __future__ import annotations

import json
import os
import re
from pathlib import Path
from typing import Annotated, Any, Callable, Iterator

from fastapi import APIRouter, Depends, Header, HTTPException
from fastapi.responses import StreamingResponse

from .team import make_device_or_supervisor

CHUNK = 64 * 1024
RANGE_RE = re.compile(r"^bytes=(\d+)-(\d*)$")


def _dir() -> Path:
    return Path(os.environ.get("POLYCARE_ARTIFACT_DIR", "data/artifacts"))


def _index() -> list[dict[str, Any]]:
    path = _dir() / "index.json"
    if not path.exists():
        return []
    try:
        return list(json.loads(path.read_text(encoding="utf-8")))
    except (OSError, ValueError):
        return []


def _stream(path: Path, start: int, end: int) -> Iterator[bytes]:
    with path.open("rb") as f:
        f.seek(start)
        remaining = end - start + 1
        while remaining > 0:
            data = f.read(min(CHUNK, remaining))
            if not data:
                return
            remaining -= len(data)
            yield data


def build_artifact_router(authenticate: Callable[..., str]) -> APIRouter:
    router = APIRouter()
    device_or_supervisor = make_device_or_supervisor(authenticate)

    @router.get("/v1/artifacts", dependencies=[Depends(device_or_supervisor)])
    def list_artifacts() -> dict[str, Any]:
        return {"artifacts": _index()}

    @router.get("/v1/artifacts/{name}/{version}/file", dependencies=[Depends(device_or_supervisor)])
    def download(name: str, version: str, range_header: Annotated[str | None, Header(alias="Range")] = None) -> StreamingResponse:
        entry = next((e for e in _index() if e.get("name") == name and str(e.get("version")) == version), None)
        if entry is None:
            raise HTTPException(404, "Unknown artifact")
        path = (_dir() / Path(str(entry["file"])).name).resolve()  # basename only: no path traversal
        if not path.is_file() or _dir().resolve() not in path.parents:
            raise HTTPException(404, "Artifact file is missing")
        size = path.stat().st_size
        start, end, status = 0, size - 1, 200
        if range_header:
            m = RANGE_RE.match(range_header.strip())
            if not m:
                raise HTTPException(416, "Unsupported Range")
            start = int(m.group(1))
            end = int(m.group(2)) if m.group(2) else size - 1
            end = min(end, size - 1)
            if start >= size or start > end:
                raise HTTPException(416, "Range not satisfiable", headers={"Content-Range": f"bytes */{size}"})
            status = 206
        headers = {"Accept-Ranges": "bytes", "Content-Length": str(end - start + 1)}
        if status == 206:
            headers["Content-Range"] = f"bytes {start}-{end}/{size}"
        return StreamingResponse(_stream(path, start, end), status_code=status, media_type="application/octet-stream", headers=headers)

    return router
