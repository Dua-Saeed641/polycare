"""
Gateway metrics endpoints for supervisor dashboard.
Aggregated operational statistics (NO patient PII).
"""

from datetime import UTC, datetime
from typing import Annotated, Any
import hmac
import os

from fastapi import Depends, Header, HTTPException
from pydantic import BaseModel, ConfigDict
from qdrant_client import QdrantClient, models


class AggregatedMetrics(BaseModel):
    model_config = ConfigDict(extra="forbid")
    device_id: str
    timestamp: int
    date: str
    total_households: int
    total_members: int
    households_with_consent: int
    villages_count: int
    age_distribution: dict[str, int]
    visits_today: int
    visits_by_type: dict[str, int]
    questions_asked: int
    danger_signs_detected: int
    top_query_topics: list[str]
    unanswered_gaps: int
    protocols_accessed: dict[str, int]
    average_response_time_ms: int
    sync_success_rate: float
    app_crashes: int
    network_quality: str
    features_used: dict[str, int]
    screen_time_minutes: dict[str, int]


def verify_supervisor(authorization: Annotated[str | None, Header()] = None) -> bool:
    """Verify supervisor dashboard access token."""
    expected = os.environ.get("POLYCARE_SUPERVISOR_TOKEN", "")
    if len(expected) < 16 or not authorization or not hmac.compare_digest(
        authorization.removeprefix("Bearer "), expected
    ):
        raise HTTPException(401, "Supervisor token required")
    return True


def build_metrics_router(authenticate, qdrant_client, pid_func):
    """Build metrics router for supervisor dashboard."""
    from fastapi import APIRouter
    
    router = APIRouter()
    
    @router.post("/v1/metrics")
    async def submit_metrics(
        metrics: AggregatedMetrics,
        device_id: Annotated[str, Depends(authenticate)],
        client: Annotated[QdrantClient, Depends(qdrant_client)]
    ) -> dict[str, str]:
        """Submit aggregated operational metrics (no PII)."""
        point_id = pid_func("metrics", f"{metrics.device_id}_{metrics.timestamp}")
        
        await client.upsert(
            collection_name="metrics",
            points=[models.PointStruct(
                id=point_id,
                vector=[0.0] * 384,  # Dummy vector for compatibility
                payload=metrics.model_dump()
            )],
            wait=True
        )
        
        return {"status": "ok", "point_id": point_id}
    
    @router.get("/v1/metrics/summary")
    async def get_metrics_summary(
        days: int = 7,
        _supervisor: Annotated[bool, Depends(verify_supervisor)] = True,
        client: Annotated[QdrantClient, Depends(qdrant_client)] = None
    ) -> dict[str, Any]:
        """Get aggregated metrics summary for dashboard."""
        results, _ = await client.scroll(
            collection_name="metrics",
            limit=1000,
            with_payload=True
        )
        
        device_ids = set(p.payload["device_id"] for p in results)
        total_households = sum(p.payload.get("total_households", 0) for p in results)
        total_members = sum(p.payload.get("total_members", 0) for p in results)
        total_visits = sum(p.payload.get("visits_today", 0) for p in results)
        total_questions = sum(p.payload.get("questions_asked", 0) for p in results)
        
        return {
            "total_asha_workers": len(device_ids),
            "total_households": total_households,
            "total_members": total_members,
            "total_visits_week": total_visits,
            "total_questions": total_questions,
            "active_devices_today": len([p for p in results if p.payload.get("date") == datetime.now(UTC).date().isoformat()]),
        }
    
    @router.get("/v1/metrics/device/{device_id}")
    async def get_device_metrics(
        device_id: str,
        _supervisor: Annotated[bool, Depends(verify_supervisor)] = True,
        client: Annotated[QdrantClient, Depends(qdrant_client)] = None
    ) -> dict[str, Any]:
        """Get metrics for specific ASHA worker device."""
        results, _ = await client.scroll(
            collection_name="metrics",
            scroll_filter=models.Filter(
                must=[models.FieldCondition(
                    key="device_id",
                    match=models.MatchValue(value=device_id)
                )]
            ),
            limit=30,
            with_payload=True
        )
        
        return {
            "device_id": device_id,
            "metrics": [p.payload for p in results]
        }
    
    @router.post("/v1/devices/{device_id}/revoke")
    async def revoke_device(
        device_id: str,
        _supervisor: Annotated[bool, Depends(verify_supervisor)] = True,
        client: Annotated[QdrantClient, Depends(qdrant_client)] = None
    ) -> dict[str, str]:
        """Revoke a device (lost/stolen phone security)."""
        point_id = pid_func("revoked_devices", device_id)
        
        await client.upsert(
            collection_name="revoked_devices",
            points=[models.PointStruct(
                id=point_id,
                vector=[0.0] * 384,
                payload={
                    "device_id": device_id,
                    "revoked_at": datetime.now(UTC).isoformat(),
                    "reason": "device_revoked"
                }
            )],
            wait=True
        )
        
        return {"status": "revoked", "device_id": device_id}
    
    return router
