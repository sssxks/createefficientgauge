import struct
import tempfile
import unittest
import io
from contextlib import redirect_stdout
from pathlib import Path

import sparkprofile


def varint(value: int) -> bytes:
    result = bytearray()
    while value >= 0x80:
        result.append((value & 0x7F) | 0x80)
        value >>= 7
    result.append(value)
    return bytes(result)


def field(number: int, wire_type: int, value: bytes | int) -> bytes:
    encoded = varint((number << 3) | wire_type)
    if wire_type == 0:
        return encoded + varint(value)
    if wire_type == 1:
        return encoded + value
    if wire_type == 2:
        return encoded + varint(len(value)) + value
    raise AssertionError(wire_type)


def text(number: int, value: str) -> bytes:
    return field(number, 2, value.encode())


def message(number: int, value: bytes) -> bytes:
    return field(number, 2, value)


def doubles(number: int, *values: float) -> bytes:
    return field(number, 2, struct.pack("<" + "d" * len(values), *values))


def packed_ints(number: int, *values: int) -> bytes:
    return field(number, 2, b"".join(varint(value) for value in values))


def sample_profile() -> bytes:
    platform = (
        field(1, 0, 1)
        + text(2, "NeoForge")
        + text(3, "test")
        + text(4, "1.21.1")
        + field(7, 0, 999)
    )
    java = text(1, "Test Vendor") + text(2, "25")
    jvm = text(1, "Test VM") + text(3, "25-test")
    system = message(6, java) + field(7, 0, 123_000) + message(9, jvm)
    world = field(1, 0, 42)
    platform_stats = field(3, 0, 100_000) + message(8, world)
    aggregator = field(1, 0, 0) + field(2, 0, 1)
    metadata = (
        field(2, 0, 1_000)
        + field(3, 0, 4_000)
        + message(5, aggregator)
        + message(7, platform)
        + message(8, platform_stats)
        + message(9, system)
        + field(11, 0, 11_000)
        + field(15, 0, 0)
        + field(16, 0, 0)
    )

    # Node 0 is the leaf. Node 1 and node 2 intentionally have the same method
    # and are nested, modelling a bridge/recursive frame. The thread root points
    # at node 2.
    leaf = text(3, "example.Leaf") + text(4, "work") + doubles(8, 4.0)
    inner = (
        text(3, "example.Renderer")
        + text(4, "render")
        + doubles(8, 8.0)
        + packed_ints(9, 0)
    )
    outer = (
        text(3, "example.Renderer")
        + text(4, "render")
        + doubles(8, 12.0)
        + packed_ints(9, 1)
    )
    thread = (
        text(1, "Render thread")
        + message(3, leaf)
        + message(3, inner)
        + message(3, outer)
        + doubles(4, 12.0)
        + packed_ints(5, 2)
    )
    source_entry = text(1, "mod/example") + message(2, text(1, "Example"))
    return (
        message(1, metadata)
        + message(2, thread)
        + message(3, source_entry)
        + packed_ints(6, 17)
    )


class SparkProfileTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = Path(self.temp.name) / "sample.sparkprofile"
        self.path.write_bytes(sample_profile())

    def tearDown(self):
        self.temp.cleanup()

    def test_reads_metadata_and_flattened_tree(self):
        profile = sparkprofile.read_profile(self.path)
        self.assertEqual("NeoForge", profile.metadata.platform_name)
        self.assertEqual("25", profile.metadata.java_version)
        self.assertEqual(10_000, profile.metadata.duration_ms)
        self.assertEqual(123_000, profile.metadata.system_uptime_ms)
        self.assertEqual(42, profile.metadata.entity_count)
        self.assertEqual([17], profile.time_windows)
        thread = profile.select_thread("render")
        self.assertEqual(12.0, thread.value)
        self.assertEqual("example.Renderer.render", thread.children()[0].display_name)

    def test_inclusive_stats_deduplicate_nested_identical_method(self):
        thread = sparkprofile.read_profile(self.path).select_thread(None)
        stats = sparkprofile.method_stats(thread)
        renderer = stats["example.Renderer.render"]
        self.assertEqual(2, renderer.occurrences)
        self.assertEqual(20.0, renderer.raw_inclusive)
        self.assertEqual(12.0, renderer.inclusive)
        self.assertEqual(8.0, renderer.self_value)
        self.assertEqual(100.0, renderer.share(thread.value))

    def test_cli_json_summary(self):
        output = io.StringIO()
        with redirect_stdout(output):
            result = sparkprofile.main(["summary", str(self.path), "--json"])
        self.assertEqual(0, result)
        self.assertIn('"platform_name": "NeoForge"', output.getvalue())


if __name__ == "__main__":
    unittest.main()
