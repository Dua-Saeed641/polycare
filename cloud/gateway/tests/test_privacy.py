import base64
import struct
import unittest
import uuid

from fastapi import HTTPException

from app.main import Hlc, Op, _validate_cloud_op


def signal(payload=None):
    value = {
        "dense_f16": base64.b64encode(struct.pack("<384e", *([0.01] * 384))).decode(),
        "model_id": "polycare-e5-small-v1",
        "simhash": 12,
        "village_code": "village_17",
        "week": 21,
        "age_band": "20-29",
        "sex": "F",
    }
    if payload:
        value.update(payload)
    return value


def operation(kind="SIGNAL", payload=None):
    return Op(
        op_id=str(uuid.uuid7()),
        device_id="device-1",
        hlc=Hlc(wall_ms=1, logical=0, node="device-1"),
        kind=kind,
        payload=payload or signal(),
        prev_hash=base64.b64encode(bytes(32)).decode(),
        signature=base64.b64encode(bytes(64)).decode(),
    )


class PrivacyTests(unittest.TestCase):
    def test_accepts_strict_deidentified_signal(self):
        _validate_cloud_op(operation())

    def test_rejects_personal_record_type(self):
        with self.assertRaises(HTTPException) as raised:
            _validate_cloud_op(operation("VISIT", {"visit": "checkup"}))
        self.assertEqual(raised.exception.status_code, 403)

    def test_rejects_identifier_in_signal_text(self):
        with self.assertRaises(HTTPException) as raised:
            _validate_cloud_op(operation(payload=signal({"note": "Call 9876543210"})))
        self.assertEqual(raised.exception.status_code, 403)

    def test_rejects_extra_fields_even_without_identifier(self):
        with self.assertRaises(HTTPException) as raised:
            _validate_cloud_op(operation(payload=signal({"note": "all good"})))
        self.assertEqual(raised.exception.status_code, 422)

    def test_rejects_non_finite_vector(self):
        payload = signal({"dense_f16": base64.b64encode(struct.pack("<384e", *([float("nan")] + [0.0] * 383))).decode()})
        with self.assertRaises(HTTPException) as raised:
            _validate_cloud_op(operation(payload=payload))
        self.assertEqual(raised.exception.status_code, 422)


if __name__ == "__main__":
    unittest.main()
