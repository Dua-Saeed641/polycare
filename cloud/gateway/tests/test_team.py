"""Radar clustering and the GAP op kind. Pure logic: no Qdrant needed."""

import base64
import unittest

from fastapi import HTTPException

from app import radar
from app.main import Hlc, Op, _validate_cloud_op


def sig(id_: str, village: str, vec: list[float], week_ms: int = 1_000) -> radar.Signal:
    return radar.Signal(id_, "polycare-e5-small-v1", vec, village, "1-4", "Similar symptoms, age 1-4", week_ms)


def gap(payload: dict) -> Op:
    return Op(
        op_id="0198d8a0-0000-7000-8000-000000000001", device_id="device-1", hlc=Hlc(wall_ms=1, logical=0, node="device-1"),
        kind="GAP", payload=payload, prev_hash=base64.b64encode(bytes(32)).decode(), signature=base64.b64encode(bytes(64)).decode(),
    )


class RadarTests(unittest.TestCase):
    def test_three_similar_cases_in_two_villages_raise_an_alert(self):
        now = 1_000
        alerts = radar.detect([sig("a", "v1", [1, 0]), sig("b", "v2", [0.99, 0.05]), sig("c", "v1", [1, 0.02])], now)
        self.assertEqual([a.level for a in alerts], ["ALERT"])
        self.assertEqual(alerts[0].villages, ["v1", "v2"])

    def test_one_village_is_not_an_alert(self):
        now = 1_000
        self.assertEqual(radar.detect([sig("a", "v1", [1, 0]), sig("b", "v1", [1, 0]), sig("c", "v1", [1, 0])], now), [])

    def test_dissimilar_cases_do_not_cluster(self):
        now = 1_000
        self.assertEqual(radar.detect([sig("a", "v1", [1, 0]), sig("b", "v2", [0, 1]), sig("c", "v3", [-1, 0])], now), [])


class GapTests(unittest.TestCase):
    def test_accepts_a_plain_question(self):
        _validate_cloud_op(gap({"query": "how much ORS for a 2 year old"}))

    def test_rejects_extra_fields(self):
        with self.assertRaises(HTTPException) as raised:
            _validate_cloud_op(gap({"query": "ORS dose", "confidence": "0.2"}))
        self.assertEqual(raised.exception.status_code, 422)

    def test_rejects_a_phone_number_in_the_question(self):
        with self.assertRaises(HTTPException) as raised:
            _validate_cloud_op(gap({"query": "call 9876543210 about the baby"}))
        self.assertEqual(raised.exception.status_code, 403)

    def test_rejects_an_empty_or_huge_question(self):
        for query in ("   ", "x" * 241):
            with self.assertRaises(HTTPException):
                _validate_cloud_op(gap({"query": query}))


if __name__ == "__main__":
    unittest.main()
