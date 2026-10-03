# Add to cloud/gateway/app/main.py

from datetime import date
from typing import List, Dict, Any

class AggregatedMetrics(StrictModel):
    device_id: str
    timestamp: int
    date: str
    total_households: int
    total_members: int
    households_with_consent: int
    villages_count: int
    age_distribution: Dict[str, int]
    visits_today: int
    visits_by_type: Dict[str, int]
    questions_asked: int
    danger_signs_detected: int
    top_query_topics: List[str]
    unanswered_gaps: int
    protocols_accessed: Dict[str, int]
    average_response_time_ms: int
    sync_success_rate: float
    app_crashes: int
    network_quality: str
    features_used: Dict[str, int]
    screen_time_minutes: Dict[str, int]

@app.post(\"/v1/metrics\")
async def submit_metrics(
    metrics: AggregatedMetrics,
    device: Annotated[dict, Depends(authenticate_device)]
):
    '''Submit aggregated operational metrics (no PII).'''
    
    # Store in Qdrant metrics collection
    point_id = str(uuid.uuid4())
    
    await qdrant.upsert(
        collection_name=\"metrics\",
        points=[models.PointStruct(
            id=point_id,
            payload={
                \"device_id\": metrics.device_id,
                \"timestamp\": metrics.timestamp,
                \"date\": metrics.date,
                \"total_households\": metrics.total_households,
                \"total_members\": metrics.total_members,
                \"villages_count\": metrics.villages_count,
                \"visits_today\": metrics.visits_today,
                \"questions_asked\": metrics.questions_asked,
                \"network_quality\": metrics.network_quality,
                # ... all other fields
            },
            vector=[0.0] * 384  # Dummy vector for compatibility
        )]
    )
    
    return {\"status\": \"ok\", \"point_id\": point_id}

@app.get(\"/v1/metrics/summary\")
async def get_metrics_summary(
    days: int = 7,
    supervisor: Annotated[dict, Depends(verify_supervisor)]
):
    '''Get aggregated metrics summary for dashboard.'''
    
    # Query recent metrics
    results = await qdrant.scroll(
        collection_name=\"metrics\",
        limit=1000,
        with_payload=True
    )
    
    # Aggregate across all devices
    total_asha_workers = len(set(p.payload[\"device_id\"] for p in results[0]))
    total_households = sum(p.payload.get(\"total_households\", 0) for p in results[0])
    total_visits = sum(p.payload.get(\"visits_today\", 0) for p in results[0])
    
    return {
        \"total_asha_workers\": total_asha_workers,
        \"total_households\": total_households,
        \"total_members\": sum(p.payload.get(\"total_members\", 0) for p in results[0]),
        \"total_visits_week\": total_visits,
        \"active_devices_today\": len([p for p in results[0] if p.payload.get(\"date\") == date.today().isoformat()]),
    }

@app.get(\"/v1/metrics/device/{device_id}\")
async def get_device_metrics(
    device_id: str,
    supervisor: Annotated[dict, Depends(verify_supervisor)]
):
    '''Get metrics for specific ASHA worker device.'''
    
    results = await qdrant.scroll(
        collection_name=\"metrics\",
        scroll_filter=models.Filter(
            must=[models.FieldCondition(
                key=\"device_id\",
                match=models.MatchValue(value=device_id)
            )]
        ),
        limit=30,  # Last 30 days
        with_payload=True
    )
    
    return {
        \"device_id\": device_id,
        \"metrics\": [p.payload for p in results[0]]
    }
