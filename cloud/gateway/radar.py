"""Outbreak Radar: the gateway's copy of the phone's algorithm (android/core-common/.../OutbreakRadar.kt).

A *new dense region of vector space* across several villages is the alert, not a keyword count.
Signals inside the recent window are grouped by leader clustering on cosine similarity; a group
with enough signals from enough distinct villages is an ALERT, a large single-village group a WATCH.
Vectors are only compared with vectors of the same ``model_id`` (invariant 4).
"""

from __future__ import annotations

import math
import os
from collections import Counter
from dataclasses import dataclass, field

WINDOW_MS = int(os.getenv("RADAR_WINDOW_DAYS", "7")) * 24 * 60 * 60 * 1000
CLUSTER_COSINE = float(os.getenv("RADAR_CLUSTER_COSINE", "0.80"))
MIN_SIGNALS = int(os.getenv("RADAR_MIN_SIGNALS", "3"))
MIN_VILLAGES = int(os.getenv("RADAR_MIN_VILLAGES", "2"))


@dataclass
class Signal:
    id: str
    model_id: str
    vector: list[float]
    village: str
    category: str
    label: str
    wall_ms: int
    count: int = 1


@dataclass
class Alert:
    key: str
    label: str
    category: str
    level: str  # "WATCH" | "ALERT"
    count: int
    villages: list[str]
    first: int
    last: int


@dataclass
class _Cluster:
    members: list[Signal] = field(default_factory=list)
    centroid: list[float] = field(default_factory=list)

    def add(self, s: Signal) -> None:
        unit = _normalized(s.vector)
        n = len(self.members) + 1
        if not self.centroid:
            self.centroid = unit
        else:
            self.centroid = _normalized([c * (n - 1) + u for c, u in zip(self.centroid, unit)])
        self.members.append(s)


def _normalized(v: list[float]) -> list[float]:
    norm = math.sqrt(sum(x * x for x in v))
    return v if norm == 0 else [x / norm for x in v]


def cosine(a: list[float], b: list[float]) -> float:
    dot = sum(x * y for x, y in zip(a, b))
    na = math.sqrt(sum(x * x for x in a))
    nb = math.sqrt(sum(y * y for y in b))
    return 0.0 if na == 0 or nb == 0 else dot / (na * nb)


def detect(signals: list[Signal], now_ms: int) -> list[Alert]:
    recent = [s for s in signals if 0 <= now_ms - s.wall_ms <= WINDOW_MS or s.wall_ms > now_ms]
    alerts: list[Alert] = []
    by_model: dict[str, list[Signal]] = {}
    for s in recent:
        by_model.setdefault(s.model_id, []).append(s)

    for model_id, group in by_model.items():
        clusters: list[_Cluster] = []
        for s in sorted(group, key=lambda x: x.wall_ms):
            if not s.vector:
                continue
            unit = _normalized(s.vector)
            best, best_sim = None, CLUSTER_COSINE
            for c in clusters:
                if len(c.centroid) != len(unit):
                    continue
                sim = cosine(c.centroid, unit)
                if sim >= best_sim:
                    best, best_sim = c, sim
            if best is None:
                best = _Cluster()
                clusters.append(best)
            best.add(s)

        for c in clusters:
            total = sum(m.count for m in c.members)
            villages = sorted({m.village for m in c.members if m.village})
            if total >= MIN_SIGNALS and len(villages) >= MIN_VILLAGES:
                level = "ALERT"
            elif total >= MIN_SIGNALS * 2:
                level = "WATCH"
            else:
                continue
            label = Counter(m.label for m in c.members).most_common(1)[0][0] or "Unspecified symptoms"
            category = Counter(m.category for m in c.members).most_common(1)[0][0]
            alerts.append(
                Alert(
                    key=f"{model_id}:{min(m.id for m in c.members)}",
                    label=label,
                    category=category,
                    level=level,
                    count=total,
                    villages=villages,
                    first=min(m.wall_ms for m in c.members),
                    last=max(m.wall_ms for m in c.members),
                )
            )
    alerts.sort(key=lambda a: (a.level == "ALERT", a.count), reverse=True)
    return alerts
