"""Semantic Merkle index over team tips: the gateway's copy of
`android/core-common/.../sync/SemanticMerkle.kt`. The two must implement exactly the same rules or
every comparison reports a difference.

A 16-ary tree, four levels deep, keyed by the 16-bit SimHash *region* of each tip. Similar meanings
share a prefix, so two devices that disagree about one topic disagree in one small subtree.

  region(simhash)  the 16-bit value as 4 lowercase hex digits (0x0a3f -> "0a3f")
  EMPTY            sha256("empty") as lowercase hex; a subtree with no ops hashes to it
  leaf (4 digits)  EMPTY if no ops, else sha256("leaf|" + "\\n".join(sorted(op_id@wall.logical)))
  inner (0-3)      16 child hashes for digits 0..f; all EMPTY -> EMPTY, else sha256("node|" + ",".join(children))
"""

from __future__ import annotations

import hashlib
from dataclasses import dataclass

HEX = "0123456789abcdef"


def sha256_hex(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


EMPTY = sha256_hex("empty")


def region(simhash: int) -> str:
    return f"{simhash & 0xFFFF:04x}"


@dataclass(frozen=True)
class Entry:
    op_id: str
    simhash: int
    wall_ms: int
    logical: int


class SemanticMerkle:
    def __init__(self, entries: list[Entry]) -> None:
        self._by_region: dict[str, list[Entry]] = {}
        for e in entries:
            self._by_region.setdefault(region(e.simhash), []).append(e)
        self._memo: dict[str, str] = {}

    def children(self, prefix: str) -> list[str]:
        if not 0 <= len(prefix) <= 3:
            raise ValueError("prefix must be 0-3 digits")
        return [self._hash(prefix + d) for d in HEX]

    def leaf_ops(self, reg: str) -> list[Entry]:
        if len(reg) != 4:
            raise ValueError("region must be 4 digits")
        return sorted(self._by_region.get(reg, []), key=lambda e: e.op_id)

    def _hash(self, prefix: str) -> str:
        if prefix not in self._memo:
            self._memo[prefix] = self._leaf(prefix) if len(prefix) == 4 else self._inner(prefix)
        return self._memo[prefix]

    def _leaf(self, reg: str) -> str:
        entries = self._by_region.get(reg)
        if not entries:
            return EMPTY
        lines = sorted(f"{e.op_id}@{e.wall_ms}.{e.logical}" for e in entries)
        return sha256_hex("leaf|" + "\n".join(lines))

    def _inner(self, prefix: str) -> str:
        kids = [self._hash(prefix + d) for d in HEX]
        return EMPTY if all(k == EMPTY for k in kids) else sha256_hex("node|" + ",".join(kids))
