# PolyCare gateway

FastAPI service phones sync with. Run locally:

    pip install -r requirements.txt
    uvicorn main:app --host 0.0.0.0 --port 8080

or `docker compose up -d` from `cloud/`. Open `http://localhost:8080` for the supervisor dashboard.
On the phone: Sync -> Gateway address -> `http://<computer LAN IP>:8080`.

## Wire format (v1)

| Call | Body / query | Notes |
|---|---|---|
| `GET /v1/health` | | `{service, version}` |
| `POST /v1/ops` | `{device, ops:[{id,hlc,e,a,eid,p}], plusOnes:[{dedupKey,village,wallMs}]}` | idempotent on `id`; returns `{accepted,duplicate,rejected}` |
| `GET /v1/pull?device=&since=` | | `{cursor, alerts:[...], answers:[...]}` |
| `GET /v1/alerts`, `GET /v1/gaps`, `GET /v1/stats` | | dashboard data |
| `POST /v1/gaps/answer` | `{question, answer, author}` | supervisor; answer reaches the phone on its next pull |

Only `signal` and `gap` ops are stored. Any other entity, or a payload carrying a name/household
field, is rejected (`rejected` in the response) and never written: invariant 7 is enforced here as
well as on the phone (`SyncGate`).

`radar.py` is the same algorithm as `android/core-common/.../OutbreakRadar.kt`; thresholds are
env-tunable (`RADAR_WINDOW_DAYS`, `RADAR_CLUSTER_COSINE`, `RADAR_MIN_SIGNALS`, `RADAR_MIN_VILLAGES`).
Set `QDRANT_URL` (and `QDRANT_API_KEY`) to also mirror signal vectors into a Qdrant collection.
