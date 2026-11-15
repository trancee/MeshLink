"""Unit tests for tools/lab/lab.py: python3 -m unittest discover -s tools/lab/tests"""

import contextlib
import gzip
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import lab  # noqa: E402

KEY = bytes(range(32))
CONFIG = {
    "adb_server": "tcp:adb-server:5037",
    "teams": {"a": {"id": "team-a", "bundle": "ch.trancee.lab.host"}, "b": {"id": "team-b", "bundle": "ch.trancee.lab.host.b"}},
    "devices": {
        "pixel6": {"platform": "android", "serial": "pixel6-serial"},
        "ip15": {"platform": "ios", "udid": "ip15-udid", "team": "a", "tool": "devicectl"},
        "se2016": {"platform": "ios", "udid": "se2016-udid", "team": "b", "tool": "imd"},
    },
}


def write_config(directory, config):
    (directory / "devices.json").write_text(json.dumps(config))


# Synthetic captures. Real ones arrive uppercase, and tools/check_private.py rejects uppercase address literals.
STATIC_ADDRESS = "c4:1a:2b:3c:4d:5e".upper()
RANDOM_ADDRESS = "4c:1a:2b:3c:4d:5e".upper()
NEXT_ADDRESS = "4c:1a:2b:3c:4d:5f".upper()

class ConfigurationTest(unittest.TestCase):
    def test_the_environment_overrides_the_configuration_folder(self):
        self.assertEqual(Path("/lab"), lab.config_dir({"MESHLINK_LAB_CONFIG": "/lab"}))
        self.assertEqual(Path.home() / ".config" / "meshlink-lab", lab.config_dir({}))

    def test_a_missing_configuration_names_its_path(self):
        with tempfile.TemporaryDirectory() as folder:
            with self.assertRaisesRegex(lab.LabError, "no lab configuration at .*devices.json"):
                lab.load_config(Path(folder))

    def test_an_iphone_needs_a_tool_and_a_configured_team(self):
        broken = [
            {"platform": "ios", "udid": "u", "team": "a", "tool": "itunes"},
            {"platform": "ios", "udid": "u", "team": "c", "tool": "imd"},
            {"platform": "ios", "team": "a", "tool": "imd"},
            {"platform": "windows-phone"},
        ]
        for device in broken:
            with self.subTest(device=device), tempfile.TemporaryDirectory() as folder:
                write_config(Path(folder), dict(CONFIG, devices={"x": device}))
                with self.assertRaises(lab.LabError):
                    lab.load_config(Path(folder))

    def test_a_valid_configuration_loads(self):
        with tempfile.TemporaryDirectory() as folder:
            write_config(Path(folder), CONFIG)
            self.assertEqual(CONFIG, lab.load_config(Path(folder)))


class AliasTest(unittest.TestCase):
    def test_aliases_resolve_in_order_and_carry_their_alias(self):
        devices = lab.resolve(CONFIG, ["ip15", "pixel6"])

        self.assertEqual(["ip15", "pixel6"], [device["alias"] for device in devices])
        self.assertEqual("ip15-udid", devices[0]["udid"])

    def test_a_missing_alias_is_named_with_the_known_ones(self):
        with self.assertRaises(lab.LabError) as error:
            lab.resolve(CONFIG, ["pixel6", "pixel9"])

        self.assertIn("['pixel9']", str(error.exception))
        self.assertIn("['ip15', 'pixel6', 'se2016']", str(error.exception))

    def test_a_phone_named_twice_is_refused(self):
        with self.assertRaisesRegex(lab.LabError, "named twice"):
            lab.resolve(CONFIG, ["pixel6", "pixel6"])

    def test_iphones_use_their_team_bundle_and_android_the_lab_package(self):
        pixel6, ip15, se2016 = lab.resolve(CONFIG, ["pixel6", "ip15", "se2016"])

        self.assertEqual("ch.trancee.lab.host", lab.bundle_id(CONFIG, pixel6))
        self.assertEqual("ch.trancee.lab.host", lab.bundle_id(CONFIG, ip15))
        self.assertEqual("ch.trancee.lab.host.b", lab.bundle_id(CONFIG, se2016))


class ArgumentTest(unittest.TestCase):
    def test_each_phone_gets_the_other_as_peer_and_both_share_one_mesh(self):
        launches = lab.plan(["pixel6", "ip15"], None, "direct-message", "unsecured", "r-1", 90)

        self.assertEqual(
            ["scenario=direct-message", "role=any", "peer=ip15", "mesh=lab-r-1", "profile=unsecured", "run=r-1", "duration=90"],
            launches["pixel6"],
        )
        self.assertIn("peer=pixel6", launches["ip15"])
        self.assertIn("mesh=lab-r-1", launches["ip15"])

    def test_roles_apply_per_phone_in_order(self):
        launches = lab.plan(["pixel6", "ip15"], ["central", "peripheral"], "direct-message", "unsecured", "r", 60)

        self.assertIn("role=central", launches["pixel6"])
        self.assertIn("role=peripheral", launches["ip15"])

    def test_invalid_plans_are_refused(self):
        invalid = [
            (["pixel6"], None, "r", 60),
            (["pixel6", "ip15"], ["central"], "r", 60),
            (["pixel6", "ip15"], ["central", "both"], "r", 60),
            (["pixel6", "ip15"], None, "../r", 60),
            (["pixel6", "ip15"], None, "r", 4),
            (["pixel6", "ip15"], None, "r", 3601),
        ]
        for aliases, roles, run_id, duration in invalid:
            with self.subTest(aliases=aliases, roles=roles, run_id=run_id, duration=duration):
                with self.assertRaises(lab.LabError):
                    lab.plan(aliases, roles, "direct-message", "unsecured", run_id, duration)

    def test_the_command_line_splits_alias_lists(self):
        args = lab.parser().parse_args(["run", "--scenario", "direct-message", "--devices", "pixel6, ip15"])

        self.assertEqual(["pixel6", "ip15"], args.devices)
        self.assertEqual("unsecured", args.profile)
        self.assertEqual(60, args.duration)


class PermissionTest(unittest.TestCase):
    def test_android_12_and_later_get_the_nearby_devices_permissions(self):
        self.assertEqual(("BLUETOOTH_SCAN", "BLUETOOTH_CONNECT", "BLUETOOTH_ADVERTISE"), lab.android_permissions(31))

    def test_android_8_to_11_get_fine_location(self):
        self.assertEqual(("ACCESS_FINE_LOCATION",), lab.android_permissions(26))
        self.assertEqual(("ACCESS_FINE_LOCATION",), lab.android_permissions(30))


class ProfileTest(unittest.TestCase):
    def test_all_three_lab_profiles_reach_the_apps(self):
        for profile in ("unsecured", "classical", "post-quantum"):
            with self.subTest(profile=profile):
                args = lab.parser().parse_args(
                    ["run", "--scenario", "direct-message", "--devices", "pixel6,ip15", "--profile", profile]
                )
                launches = lab.plan(args.devices, None, args.scenario, args.profile, "r", 60)

                self.assertIn(f"profile={profile}", launches["pixel6"])

    def test_other_profile_names_are_refused(self):
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            lab.parser().parse_args(["run", "--scenario", "direct-message", "--devices", "a,b", "--profile", "none"])
        with self.assertRaisesRegex(lab.LabError, "profile must be one of"):
            lab.launch_args("direct-message", "any", "b", "m", "none", "r", 60)


class PseudonymTest(unittest.TestCase):
    def test_a_pseudonym_is_keyed_lowercase_and_stable(self):
        first = lab.pseudonym(KEY, RANDOM_ADDRESS)

        self.assertRegex(first, r"^(?:[0-9a-f]{2}:){5}[0-9a-f]{2}$")
        self.assertEqual(first, lab.pseudonym(KEY, "4c:1a:2b:3c:4d:5e"))
        self.assertNotEqual(first, lab.pseudonym(bytes(32), RANDOM_ADDRESS))
        self.assertNotEqual(first, lab.pseudonym(KEY, NEXT_ADDRESS))
        self.assertNotIn("1a:2b:3c:4d:5e", first)

    def test_a_pseudonym_keeps_the_two_address_type_bits(self):
        for top in (0x00, 0x40, 0x80, 0xC0):
            address = f"{top | 0x15:02X}" + RANDOM_ADDRESS[2:]
            with self.subTest(address=address):
                self.assertEqual(top, int(lab.pseudonym(KEY, address)[:2], 16) & 0xC0)

    def test_text_keeps_everything_but_the_addresses(self):
        text = '{"address":"' + STATIC_ADDRESS + '","uuid":"0A:0B:0C:0D:0E:0F:10","n":1}'

        out = lab.pseudonymize(KEY, text)

        self.assertEqual(text.replace(STATIC_ADDRESS, lab.pseudonym(KEY, STATIC_ADDRESS)), out)

    def test_a_short_key_is_refused(self):
        with tempfile.TemporaryDirectory() as folder:
            (Path(folder) / "pseudonym.key").write_bytes(bytes(16))
            with self.assertRaisesRegex(lab.LabError, "16 bytes"):
                lab.load_key(Path(folder))


class ResultTest(unittest.TestCase):
    def test_every_phone_must_pass_and_report_done(self):
        results = {
            "pixel6": [{"event": "link", "role": "central"}, {"event": "done", "result": "pass", "failures": []}],
            "ip15": [{"event": "start"}],
        }

        text, passed = lab.summary(results)

        self.assertFalse(passed)
        self.assertIn("pixel6     pass       role=central", text)
        self.assertIn("ip15       incomplete no done record", text)
        self.assertTrue(text.endswith("result: fail"))

    def test_passing_phones_pass_the_run(self):
        done = [{"event": "done", "result": "pass", "failures": []}]

        self.assertTrue(lab.summary({"a": done, "b": done})[1])
        self.assertEqual(("fail", ["x"]), lab.outcome([{"event": "done", "result": "fail", "failures": ["x"]}]))


class GrantTest(unittest.TestCase):
    def test_the_report_keeps_grant_lines_errors_and_the_failure_summary(self):
        # Arrange
        output = "\n".join([
            "noise",
            "LAB-GRANT tapped Allow",
            "Testing failed:",
            "\tLabGrantUITests-Runner encountered an error (Lost connection to testmanagerd)",
            "",
            "** TEST EXECUTE FAILED **",
            "more noise",
        ])

        # Act
        report = lab.grant_report(output)

        # Assert
        self.assertEqual([
            "LAB-GRANT tapped Allow",
            "Testing failed:",
            "LabGrantUITests-Runner encountered an error (Lost connection to testmanagerd)",
            "** TEST EXECUTE FAILED **",
        ], report)


def phone_records(peer_node, start=0):
    """One passing direct-message phone: linked at +100 ms, Sent at +130 ms, received at +180 ms."""
    return [
        {"t_ms": start, "event": "start"},
        {"t_ms": start + 100, "event": "link", "peer_node": peer_node, "role": "central"},
        {"t_ms": start + 110, "event": "send", "role": "central"},
        {"t_ms": start + 120, "event": "status", "status": "Held", "role": "central"},
        {"t_ms": start + 130, "event": "status", "status": "Sent", "role": "central"},
        {"t_ms": start + 180, "event": "received", "intact": True, "role": "central"},
        {"t_ms": start + 900, "event": "done", "result": "pass", "failures": []},
    ]


class TimingTest(unittest.TestCase):
    def test_timings_measure_from_start_send_and_link_on_one_clock(self):
        # Act
        timing = lab.timings(phone_records("b", start=5))

        # Assert
        self.assertEqual({"connect_ms": 100, "send_to_sent_ms": 20, "link_to_received_ms": 80}, timing)

    def test_a_missing_event_leaves_its_timing_empty(self):
        # Act
        timing = lab.timings([{"t_ms": 0, "event": "start"}, {"t_ms": 9, "event": "done"}])

        # Assert
        self.assertEqual({"connect_ms": None, "send_to_sent_ms": None, "link_to_received_ms": None}, timing)


class IdentityTest(unittest.TestCase):
    def test_each_phone_gets_the_node_ids_its_peers_recorded_across_runs(self):
        # Arrange
        runs = [
            {"a": phone_records("node-b"), "b": phone_records("node-a")},
            {"a": phone_records("node-b"), "b": phone_records("node-a2")},
        ]

        # Act
        ids = lab.identities(runs)

        # Assert
        self.assertEqual({"a": ["node-a", "node-a2"], "b": ["node-b"]}, ids)


def write_run(directory, run_id, devices):
    run_dir = directory / run_id
    run_dir.mkdir()
    meta = {
        "run": run_id, "scenario": "direct-message", "profile": "unsecured", "duration_s": 60,
        "commit": "0123456789abcdef", "date": "2026-11-20T10:00:00+01:00",
        "method": f"python3 tools/lab/lab.py run --scenario direct-message --devices {','.join(devices)} --profile unsecured",
        "xcode": "Xcode 26.1",
        "devices": {alias: dict(device, args=[f"run={run_id}"]) for alias, device in devices.items()},
    }
    (run_dir / "run.json").write_text(json.dumps(meta))
    return run_dir, meta


PIXEL6 = {"platform": "android", "model": "Pixel 6", "os": "Android 17 (API 37)"}
IP15 = {"platform": "ios", "model": "iPhone15,4", "os": "iOS 17.6.1"}
SE2016 = {"platform": "ios", "model": "iPhone8,4", "os": "iOS 15.8"}


class MethodTest(unittest.TestCase):
    def test_the_method_repeats_the_command_line_that_was_run(self):
        method = lab.method("direct-message", ["xcover4", "ip12mini"], "unsecured", ["central", "peripheral"],
                            "rt-1", build=False)

        self.assertEqual("python3 tools/lab/lab.py run --scenario direct-message --devices xcover4,ip12mini "
                         "--profile unsecured --roles central,peripheral --run-id rt-1 --no-build", method)

    def test_defaults_stay_off_the_method(self):
        method = lab.method("direct-message", ["a", "b"], "unsecured", None, "rt-1", build=True)

        self.assertEqual("python3 tools/lab/lab.py run --scenario direct-message --devices a,b "
                         "--profile unsecured --run-id rt-1", method)


class EvidenceTest(unittest.TestCase):
    def test_evidence_records_every_t9_field_and_pseudonymized_raw_files(self):
        with tempfile.TemporaryDirectory() as folder:
            run_dir, meta = write_run(Path(folder), "r-1", {"pixel6": PIXEL6, "ip15": IP15})
            docs = Path(folder) / "evidence"
            done = '{"t_ms":9,"event":"done","result":"pass","failures":[]}\n'
            (run_dir / "pixel6.jsonl").write_text('{"t_ms":1,"event":"scan","address":"' + STATIC_ADDRESS + '"}\n' + done)
            (run_dir / "ip15.jsonl").write_text('{"t_ms":1,"event":"pad","x":"' + "y" * 70_000 + '"}\n' + done)

            with mock.patch.object(lab, "EVIDENCE", docs):
                record = lab.evidence([run_dir], "smoke", KEY, "2026-11-20")

            readme = (record / "README.md").read_text()
            self.assertEqual(docs / "2026-11-20-smoke", record)
            for field in ("2026-11-20T10:00:00+01:00", "0123456789abcdef", meta["method"], "android-ios",
                          "Pixel 6", "Android 17 (API 37)", "iPhone15,4", "iOS 17.6.1", "raw/r-1/pixel6.jsonl",
                          "result: pass"):
                self.assertIn(field, readme)
            raw = (record / "raw" / "r-1" / "pixel6.jsonl").read_text()
            self.assertNotIn(STATIC_ADDRESS, raw)
            self.assertIn(lab.pseudonym(KEY, STATIC_ADDRESS), raw)
            self.assertIn("result", gzip.decompress((record / "raw" / "r-1" / "ip15.jsonl.gz").read_bytes()).decode())
            self.assertFalse((record / "raw" / "r-1" / "ip15.jsonl").exists())

    def test_one_record_covers_several_runs_with_timings_roles_and_identities(self):
        with tempfile.TemporaryDirectory() as folder:
            docs = Path(folder) / "evidence"
            runs = []
            for run_id, devices, ids in (("r-1", {"pixel6": PIXEL6, "ip15": IP15}, ("node-i", "node-p")),
                                         ("r-2", {"se2016": SE2016, "ip15": IP15}, ("node-i", "node-s"))):
                run_dir, _ = write_run(Path(folder), run_id, devices)
                for alias, peer_node in zip(devices, ids):
                    lines = "\n".join(json.dumps(r) for r in phone_records(peer_node))
                    (run_dir / f"{alias}.jsonl").write_text(lines + "\n")
                runs.append(run_dir)

            with mock.patch.object(lab, "EVIDENCE", docs):
                record = lab.evidence(runs, "radio-tracer", KEY, "2026-11-20")

            readme = (record / "README.md").read_text()
            for field in ("android-ios and ios-ios", "| `r-1` | `pixel6` | central | pass | 100 | 20 | 80 |",
                          "| `ip15` | `node-i` | same |", "| `se2016` | `node-s` | same |",
                          "raw/r-2/se2016.jsonl", "**Result:** pass"):
                self.assertIn(field, readme)

    def test_an_android_only_run_names_no_xcode(self):
        with tempfile.TemporaryDirectory() as folder:
            run_dir, _ = write_run(Path(folder), "r-1", {"pixel6": PIXEL6, "pixel6b": PIXEL6})
            for alias in ("pixel6", "pixel6b"):
                (run_dir / f"{alias}.jsonl").write_text('{"t_ms":9,"event":"done","result":"pass","failures":[]}\n')

            with mock.patch.object(lab, "EVIDENCE", Path(folder) / "evidence"):
                record = lab.evidence([run_dir], "android", KEY, "2026-11-20")

            self.assertNotIn("Xcode", (record / "README.md").read_text())

    def test_one_record_needs_a_name_when_it_covers_several_runs(self):
        with tempfile.TemporaryDirectory() as folder:
            first, _ = write_run(Path(folder), "r-1", {"pixel6": PIXEL6, "ip15": IP15})
            second, _ = write_run(Path(folder), "r-2", {"pixel6": PIXEL6, "ip15": IP15})

            with self.assertRaisesRegex(lab.LabError, "--name"):
                lab.evidence([first, second], None, KEY, "2026-11-20")


if __name__ == "__main__":
    unittest.main()
