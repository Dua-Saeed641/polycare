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

Signatures cover canonical UTF-8 JSON with sorted keys and compact separators for `op_id`,
`device_id`, `hlc`, `kind`, `payload`, and `prev_hash`. REST transports signature and previous
hash as base64. `proto/sync.proto` is the wire contract; an Android gateway client is still needed.
