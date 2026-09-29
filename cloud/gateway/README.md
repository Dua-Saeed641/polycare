# PolyCare Sync Gateway

Qdrant Cloud is the gateway's only database. The gateway stores device keys, short lived auth
challenges, signed sync operations, de-identified signal vectors, and an explicitly published
shared-knowledge collection in Qdrant. Household/member/visit records are rejected and remain
on the Android device.

## Local run

1. Copy `cloud/.env.example` to `cloud/.env`; set distinct random secrets and your Qdrant Cloud
   HTTPS URL and API key.
2. Run `docker compose --env-file cloud/.env -f cloud/compose.yaml up --build` from the repo root.
3. Check `http://127.0.0.1:8080/healthz`.

This gateway must run as one replica: Qdrant has no compare-and-swap transaction for the device
chain head, so multi-replica writes can race. Signed operations are persisted before advancing the
head; a retry of the same operation is idempotent. Pull currently sorts a scroll of stored ops in
the gateway, which is suitable for the initial low volume deployment and should be replaced by a
Qdrant indexed paging strategy before scaling. Configure HTTPS ingress, rate limits, secret
rotation, backup policy, and monitoring before public deployment.

## Endpoints

- `POST /v1/devices/register`: enrollment-token protected Ed25519 key registration.
- `POST /v1/auth/challenge` and `POST /v1/auth/verify`: one-use, two-minute nonce challenge.
- `POST /v1/ops/push`: accepts signed batches; only de-identified `SIGNAL` and `VOTE` are allowed.
- `GET /v1/ops/pull`: returns approved operations with signer keys for client verification.
- `POST /v1/knowledge/{document_id}`: knowledge-publisher-token protected multilingual text
  embedding into the independent `knowledge` collection using Qdrant FastEmbed's multilingual
  MiniLM model (384 dimensions). This collection is not mixed with the Android E5 vector space.

### Team features (`app/team.py`)

Added on top of the sync foundation without changing its protocol:

- `GAP` op kind: `{"query": "..."}` only, 1-240 characters, after the same private-identifier scan.
- `GET /v1/answers?after_ms=`: a phone fetches supervisor answers newer than its cursor.
- `GET /v1/radar/alerts`: clusters the de-identified signal embeddings across all villages (this week
  and last) and returns alerts. Device token or supervisor token.
- `GET /v1/supervisor/gaps`, `POST /v1/supervisor/answers`, `GET /v1/supervisor/stats`: supervisor token
  (`POLYCARE_SUPERVISOR_TOKEN`, 16+ characters).
- `GET /dashboard`: the supervisor page (alerts, grouped unanswered questions, answer form).
- `TIP` op kind: a shared tip, exactly `{text (1-500 chars), dense_f16, model_id, simhash, village_code}`.
  `VOTE` (already in the foundation) names a tip by its op id; `GET /v1/votes` returns distinct-device
  vote counts.
- Team memory anti-entropy (`app/merkle.py`, mirrored in `android/core-common/.../SemanticMerkle.kt`):
  `GET /v1/merkle?prefix=` (16 child hashes of a 0-3 digit SimHash prefix), `GET /v1/merkle/leaf?region=`
  (op ids in a 4-digit region), `POST /v1/ops/fetch` (tip ops by id, with signer keys). A phone compares
  hashes level by level and fetches only what it lacks.
- Guidance cards: `POST /v1/supervisor/guidance` (title, body, village codes) and `GET /v1/guidance?after_ms=&village_code=`.
- `GET /v1/supervisor/qa`: supervisor question/answer pairs, the Skill Factory's extra training data.
- Artifact delivery (`app/artifacts.py`): `GET /v1/artifacts` and `GET /v1/artifacts/{name}/{version}/file`
  with HTTP `Range` (resumable). The gateway only serves; entries are signed offline by
  `tools/publish_artifact.py` and phones pin the publisher key, so the gateway cannot push a model.
  Directory: `POLYCARE_ARTIFACT_DIR` (default `data/artifacts`, holds `index.json` and the files).
- Hardening: a per-address rate limit (`POLYCARE_RATE_LIMIT_PER_MIN`, default 240, 0 disables) and a
  payload index on `sync_ops.kind`. Replacing the scroll-and-sort pull with indexed paging is still open.
- Radar thresholds are env-tunable: `RADAR_WINDOW_DAYS` (14), `RADAR_CLUSTER_COSINE` (0.80),
  `RADAR_MIN_SIGNALS` (3), `RADAR_MIN_VILLAGES` (2). `app/radar.py` mirrors
  `android/core-common/.../OutbreakRadar.kt`; keep them in sync.

The Android client (`android/app/.../sync/`) implements the signed protocol: the phone registers its
Ed25519 key with the enrollment token, authenticates by signing the challenge nonce, and pushes
hash-chained signed `SIGNAL` and `GAP` ops. It never sends household, member or visit records.

### Publishing an update

    python tools/publish_artifact.py keygen --key publisher.key                 # once; prints the public key
    python cloud/skill-factory/factory.py --id dengue-fever --title "Dengue and fever" \
        --card "Fever, rash, dengue warning signs" --sources asha-module-7 \
        --gateway https://... --supervisor-token "$POLYCARE_SUPERVISOR_TOKEN" \
        --key publisher.key --artifact-dir data/artifacts --version 1

Paste the public key into the app (Sync -> Updates -> Publisher key), then "Check for updates".

Signatures cover canonical UTF-8 JSON with sorted keys and compact separators for `op_id`,
`device_id`, `hlc`, `kind`, `payload`, and `prev_hash`. REST transports signature and previous
hash as base64. `proto/sync.proto` is the wire contract; the Android client is `android/app/.../sync/`.
