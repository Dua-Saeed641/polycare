import base64
import json
import os
import struct
import unittest
import uuid
from datetime import UTC, datetime
from unittest.mock import patch

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from fastapi.testclient import TestClient
from qdrant_client import QdrantClient

from app import main


class QdrantGatewayTests(unittest.TestCase):
    def setUp(self):
        self.db = QdrantClient(":memory:")
        self.env = patch.dict(os.environ, {
            "QDRANT_URL": "https://qdrant.example", "QDRANT_API_KEY": "q" * 24,
            "POLYCARE_ENROLLMENT_TOKEN": "e" * 48, "POLYCARE_TOKEN_SECRET": "t" * 48,
            "POLYCARE_KNOWLEDGE_TOKEN": "k" * 48, "POLYCARE_SUPERVISOR_TOKEN": "s" * 48,
        })
        self.env.start()
        self.qdrant_patch = patch.object(main, "QdrantClient", return_value=self.db)
        self.qdrant_patch.start()
        self.client_context = TestClient(main.app)
        self.client = self.client_context.__enter__()
        self.private = Ed25519PrivateKey.generate()
        self.device_id = "device-test-1"
        public = self.private.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)
        response = self.client.post("/v1/devices/register", headers={"X-Enrollment-Token": "e" * 48}, json={
            "device_id": self.device_id, "public_key": base64.b64encode(public).decode()})
        self.assertEqual(response.status_code, 201, response.text)
        challenge = self.client.post("/v1/auth/challenge", json={"device_id": self.device_id}).json()
        signature = self.private.sign(base64.b64decode(challenge["nonce"]))
        auth = self.client.post("/v1/auth/verify", json={"challenge_id": challenge["challenge_id"],
            "device_id": self.device_id, "signature": base64.b64encode(signature).decode()})
        self.assertEqual(auth.status_code, 200, auth.text)
        self.headers = {"Authorization": "Bearer " + auth.json()["access_token"]}

        class StubEmbedder:
            def embed(self, texts):
                return iter([[1.0] + [0.0] * 383 for _ in texts])

            def query_embed(self, texts):
                return iter([[1.0] + [0.0] * 383 for _ in texts])

        main.app.state.embedder = StubEmbedder()

    def tearDown(self):
        self.client_context.__exit__(None, None, None)
        self.qdrant_patch.stop()
        self.env.stop()
        self.db.close()

    def make_op(self, payload=None, kind="SIGNAL", prev_hash=None, wall_ms=None):
        payload = payload or {
            "dense_f16": base64.b64encode(struct.pack("<384e", *([0.01] * 384))).decode(),
            "model_id": "polycare-e5-small-v1", "simhash": 34, "village_code": "village_17",
            "week": 21, "age_band": "20-29", "sex": "F",
        }
        unsigned = {"op_id": str(uuid.uuid7()), "device_id": self.device_id,
            "hlc": {"wall_ms": wall_ms or int(datetime.now(UTC).timestamp() * 1000), "logical": 0, "node": self.device_id},
            "kind": kind, "payload": payload, "prev_hash": prev_hash or base64.b64encode(bytes(32)).decode()}
        signed = {**unsigned, "prev_hash": base64.b64decode(unsigned["prev_hash"]).hex()}
        canonical = json.dumps(signed, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()
        return {**unsigned, "signature": base64.b64encode(self.private.sign(canonical)).decode()}

    def test_register_auth_push_retry_and_pull_are_qdrant_backed(self):
        op = self.make_op()
        pushed = self.client.post("/v1/ops/push", headers=self.headers, json={"ops": [op]})
        self.assertEqual(pushed.status_code, 200, pushed.text)
        self.assertEqual(pushed.json()["accepted_op_ids"], [op["op_id"]])
        duplicate = self.client.post("/v1/ops/push", headers=self.headers, json={"ops": [op]})
        self.assertEqual(duplicate.status_code, 200, duplicate.text)
        self.assertEqual(duplicate.json()["duplicate_op_ids"], [op["op_id"]])
        pulled = self.client.get("/v1/ops/pull", headers=self.headers)
        self.assertEqual(pulled.status_code, 200, pulled.text)
        self.assertEqual([item["op_id"] for item in pulled.json()["ops"]], [op["op_id"]])
        self.assertEqual(self.db.count("sync_ops").count, 1)
        self.assertEqual(self.db.count("signals").count, 1)

    def test_personal_visit_is_rejected_before_storage(self):
        op = self.make_op(payload={"name": "Asha"}, kind="VISIT")
        response = self.client.post("/v1/ops/push", headers=self.headers, json={"ops": [op]})
        self.assertEqual(response.status_code, 403)
        self.assertEqual(self.db.count("sync_ops").count, 0)

    def test_re_registration_does_not_reset_chain(self):
        op = self.make_op()
        self.assertEqual(self.client.post("/v1/ops/push", headers=self.headers, json={"ops": [op]}).status_code, 200)
        public = self.private.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)
        response = self.client.post("/v1/devices/register", headers={"X-Enrollment-Token": "e" * 48}, json={
            "device_id": self.device_id, "public_key": base64.b64encode(public).decode()})
        self.assertEqual(response.status_code, 201)
        self.assertEqual(base64.b64decode(main._point(self.db, "devices", self.device_id, "device")["chain_head"]),
            base64.b64decode(self.client.post("/v1/ops/push", headers=self.headers, json={"ops": [op]}).json()["chain_head"]))

    def test_fastembed_knowledge_ingest_and_search_use_separate_qdrant_collection(self):
        ingested = self.client.post("/v1/knowledge/anc", headers={"Authorization": "Bearer " + "k" * 48}, json={
            "document_id": "anc", "language": "en", "text": "Four antenatal checkups during pregnancy.", "source_version": "2026-09"})
        self.assertEqual(ingested.status_code, 200, ingested.text)
        searched = self.client.get("/v1/knowledge/search?query=antenatal%20checkups", headers=self.headers)
        self.assertEqual(searched.status_code, 200, searched.text)
        self.assertEqual(searched.json()["results"][0]["document_id"], "anc")
        self.assertEqual(self.db.count("knowledge").count, 1)

    def test_team_routes_require_auth_and_deliver_supervisor_answer_and_guidance(self):
        self.assertEqual(self.client.get("/v1/answers").status_code, 401)
        supervisor = {"X-Supervisor-Token": "s" * 48}
        published = self.client.post("/v1/supervisor/answers", headers=supervisor, json={
            "question": "How much ORS should I give?", "answer": "Follow the packet instructions.",
        })
        self.assertEqual(published.status_code, 201, published.text)
        pulled = self.client.get("/v1/answers", headers=self.headers)
        self.assertEqual(pulled.status_code, 200, pulled.text)
        self.assertEqual(pulled.json()["answers"][0]["question"], "How much ORS should I give?")

        guidance = self.client.post("/v1/supervisor/guidance", headers=supervisor, json={
            "title": "Village advisory", "body": "Contact the health centre.", "villages": ["village_17"],
        })
        self.assertEqual(guidance.status_code, 201, guidance.text)
        received = self.client.get("/v1/guidance?village_code=village_17", headers=self.headers)
        self.assertEqual(received.status_code, 200, received.text)
        self.assertEqual([card["title"] for card in received.json()["cards"]], ["Village advisory"])

        merkle = self.client.get("/v1/merkle", headers=self.headers)
        self.assertEqual(merkle.status_code, 200, merkle.text)
        self.assertEqual(len(merkle.json()["children"]), 16)


if __name__ == "__main__":
    unittest.main()
