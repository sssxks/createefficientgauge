#!/usr/bin/env python3
"""Dependency-free reader for spark .sparkprofile protobuf files.

This is intentionally an agent/debugging tool, not a stable public CLI.  It
implements only the protobuf messages needed to inspect SamplerData files and
keeps unknown fields so newer spark exports normally remain readable.
"""

from __future__ import annotations

import argparse
import json
import re
import struct
import sys
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Iterable, Iterator, Sequence


class ProfileFormatError(ValueError):
    pass


@dataclass(frozen=True)
class ProtoField:
    number: int
    wire_type: int
    value: int | bytes


def _read_varint(data: bytes, offset: int) -> tuple[int, int]:
    value = 0
    shift = 0
    while offset < len(data) and shift < 70:
        byte = data[offset]
        offset += 1
        value |= (byte & 0x7F) << shift
        if byte < 0x80:
            return value, offset
        shift += 7
    raise ProfileFormatError("truncated or oversized protobuf varint")


def protobuf_fields(data: bytes) -> list[ProtoField]:
    fields: list[ProtoField] = []
    offset = 0
    while offset < len(data):
        key, offset = _read_varint(data, offset)
        number = key >> 3
        wire_type = key & 7
        if number == 0:
            raise ProfileFormatError("protobuf field number 0 is invalid")

        if wire_type == 0:
            value, offset = _read_varint(data, offset)
        elif wire_type == 1:
            end = offset + 8
            if end > len(data):
                raise ProfileFormatError("truncated protobuf fixed64")
            value = data[offset:end]
            offset = end
        elif wire_type == 2:
            length, offset = _read_varint(data, offset)
            end = offset + length
            if end > len(data):
                raise ProfileFormatError("truncated protobuf length-delimited field")
            value = data[offset:end]
            offset = end
        elif wire_type == 5:
            end = offset + 4
            if end > len(data):
                raise ProfileFormatError("truncated protobuf fixed32")
            value = data[offset:end]
            offset = end
        else:
            raise ProfileFormatError(f"unsupported protobuf wire type {wire_type}")
        fields.append(ProtoField(number, wire_type, value))
    return fields


def _values(fields: Sequence[ProtoField], number: int) -> list[int | bytes]:
    return [field.value for field in fields if field.number == number]


def _last(fields: Sequence[ProtoField], number: int, default=None):
    values = _values(fields, number)
    return values[-1] if values else default


def _bytes(value: int | bytes | None) -> bytes:
    if not isinstance(value, bytes):
        raise ProfileFormatError("expected a length-delimited protobuf field")
    return value


def _integer(value: int | bytes | None, default: int = 0) -> int:
    if value is None:
        return default
    if not isinstance(value, int):
        raise ProfileFormatError("expected a protobuf varint field")
    return value


def _text(value: int | bytes | None) -> str:
    if value is None:
        return ""
    return _bytes(value).decode("utf-8", errors="replace")


def _packed_varints(value: bytes) -> list[int]:
    result: list[int] = []
    offset = 0
    while offset < len(value):
        item, offset = _read_varint(value, offset)
        result.append(item)
    return result


def _repeated_varints(fields: Sequence[ProtoField], number: int) -> list[int]:
    result: list[int] = []
    for field in fields:
        if field.number != number:
            continue
        if field.wire_type == 0:
            result.append(_integer(field.value))
        elif field.wire_type == 2:
            result.extend(_packed_varints(_bytes(field.value)))
        else:
            raise ProfileFormatError(f"field {number} is not a repeated varint")
    return result


def _repeated_doubles(fields: Sequence[ProtoField], number: int) -> list[float]:
    result: list[float] = []
    for field in fields:
        if field.number != number:
            continue
        if field.wire_type == 1:
            result.append(struct.unpack("<d", _bytes(field.value))[0])
        elif field.wire_type == 2:
            packed = _bytes(field.value)
            if len(packed) % 8:
                raise ProfileFormatError(f"field {number} has malformed packed doubles")
            result.extend(struct.unpack("<" + "d" * (len(packed) // 8), packed))
        else:
            raise ProfileFormatError(f"field {number} is not a repeated double")
    return result


@dataclass
class StackNode:
    class_name: str
    method_name: str
    parent_line_number: int
    line_number: int
    method_desc: str
    times: list[float]
    child_refs: list[int]

    @property
    def value(self) -> float:
        return sum(self.times)

    @property
    def method_key(self) -> str:
        suffix = self.method_desc if self.method_desc else ""
        return f"{self.class_name}.{self.method_name}{suffix}"

    @property
    def display_name(self) -> str:
        return f"{self.class_name}.{self.method_name}"

    @property
    def group_key(self) -> str:
        # Hidden JVM classes include a process-specific /0x... suffix. It is
        # useless in one profile and actively harmful when comparing launches.
        class_name = re.sub(r"/0x[0-9a-fA-F]+", "/0x*", self.class_name)
        # ImmediatelyFast generates md<launch-specific-hash>$... bridge names.
        method_name = re.sub(r"^md[0-9a-fA-F]+\$", "md*$", self.method_name)
        return f"{class_name}.{method_name}"


@dataclass
class ThreadProfile:
    name: str
    times: list[float]
    nodes: list[StackNode]
    child_refs: list[int]

    @property
    def value(self) -> float:
        return sum(self.times)

    def children(self, node: StackNode | None = None) -> list[StackNode]:
        refs = self.child_refs if node is None else node.child_refs
        children: list[StackNode] = []
        for ref in refs:
            if ref < 0 or ref >= len(self.nodes):
                raise ProfileFormatError(
                    f"thread {self.name!r} has invalid child ref {ref}/{len(self.nodes)}"
                )
            children.append(self.nodes[ref])
        return children


@dataclass
class ProfileMetadata:
    start_time_ms: int
    end_time_ms: int
    interval: int
    sampler_mode: int
    sampler_engine: int
    sampler_engine_version: str
    aggregator_type: int
    thread_grouper: int
    platform_type: int
    platform_name: str
    platform_version: str
    minecraft_version: str
    spark_version: int
    system_uptime_ms: int
    platform_uptime_ms: int
    java_vendor: str
    java_version: str
    jvm_name: str
    jvm_version: str
    entity_count: int
    source_count: int

    @property
    def duration_ms(self) -> int:
        return max(0, self.end_time_ms - self.start_time_ms)

    @property
    def engine_name(self) -> str:
        return {0: "java", 1: "async"}.get(self.sampler_engine, str(self.sampler_engine))

    @property
    def mode_name(self) -> str:
        return {0: "execution", 1: "allocation"}.get(
            self.sampler_mode, str(self.sampler_mode)
        )

    @property
    def unit(self) -> str:
        return "ms" if self.sampler_mode == 0 else "bytes"


@dataclass
class SparkProfile:
    path: Path
    metadata: ProfileMetadata
    threads: list[ThreadProfile]
    time_windows: list[int]

    def select_thread(self, name: str | None) -> ThreadProfile:
        if not self.threads:
            raise ProfileFormatError("profile contains no threads")
        if name is None:
            return max(self.threads, key=lambda thread: thread.value)
        exact = [thread for thread in self.threads if thread.name == name]
        if exact:
            return exact[0]
        partial = [thread for thread in self.threads if name.lower() in thread.name.lower()]
        if len(partial) == 1:
            return partial[0]
        available = ", ".join(repr(thread.name) for thread in self.threads)
        raise ProfileFormatError(f"thread {name!r} not found; available: {available}")


@dataclass
class MethodStats:
    method_key: str
    display_name: str
    occurrences: int = 0
    inclusive: float = 0.0
    raw_inclusive: float = 0.0
    self_value: float = 0.0

    def share(self, thread_value: float) -> float:
        return 0.0 if thread_value <= 0 else 100.0 * self.inclusive / thread_value

    def self_share(self, thread_value: float) -> float:
        return 0.0 if thread_value <= 0 else 100.0 * self.self_value / thread_value


def _parse_stack_node(data: bytes) -> StackNode:
    fields = protobuf_fields(data)
    return StackNode(
        class_name=_text(_last(fields, 3)),
        method_name=_text(_last(fields, 4)),
        parent_line_number=_integer(_last(fields, 5), -1),
        line_number=_integer(_last(fields, 6), -1),
        method_desc=_text(_last(fields, 7)),
        times=_repeated_doubles(fields, 8),
        child_refs=_repeated_varints(fields, 9),
    )


def _parse_thread(data: bytes) -> ThreadProfile:
    fields = protobuf_fields(data)
    nodes = [
        _parse_stack_node(_bytes(field.value))
        for field in fields
        if field.number == 3
    ]
    return ThreadProfile(
        name=_text(_last(fields, 1)),
        times=_repeated_doubles(fields, 4),
        nodes=nodes,
        child_refs=_repeated_varints(fields, 5),
    )


def _nested(fields: Sequence[ProtoField], number: int) -> list[ProtoField]:
    value = _last(fields, number)
    return protobuf_fields(_bytes(value)) if value is not None else []


def _parse_metadata(data: bytes) -> ProfileMetadata:
    fields = protobuf_fields(data)
    platform = _nested(fields, 7)
    platform_stats = _nested(fields, 8)
    system_stats = _nested(fields, 9)
    aggregator = _nested(fields, 5)
    world = _nested(platform_stats, 8)
    java = _nested(system_stats, 6)
    jvm = _nested(system_stats, 9)
    return ProfileMetadata(
        start_time_ms=_integer(_last(fields, 2)),
        end_time_ms=_integer(_last(fields, 11)),
        interval=_integer(_last(fields, 3)),
        sampler_mode=_integer(_last(fields, 15)),
        sampler_engine=_integer(_last(fields, 16)),
        sampler_engine_version=_text(_last(fields, 17)),
        aggregator_type=_integer(_last(aggregator, 1)),
        thread_grouper=_integer(_last(aggregator, 2)),
        platform_type=_integer(_last(platform, 1)),
        platform_name=_text(_last(platform, 2)),
        platform_version=_text(_last(platform, 3)),
        minecraft_version=_text(_last(platform, 4)),
        spark_version=_integer(_last(platform, 7)),
        system_uptime_ms=_integer(_last(system_stats, 7)),
        platform_uptime_ms=_integer(_last(platform_stats, 3)),
        java_vendor=_text(_last(java, 1)),
        java_version=_text(_last(java, 2)),
        jvm_name=_text(_last(jvm, 1)),
        jvm_version=_text(_last(jvm, 3)),
        entity_count=_integer(_last(world, 1)),
        source_count=sum(1 for field in fields if field.number == 13),
    )


def read_profile(path: str | Path) -> SparkProfile:
    profile_path = Path(path)
    fields = protobuf_fields(profile_path.read_bytes())
    metadata_value = _last(fields, 1)
    if metadata_value is None:
        raise ProfileFormatError("SamplerData.metadata is missing")
    threads = [
        _parse_thread(_bytes(field.value)) for field in fields if field.number == 2
    ]
    return SparkProfile(
        path=profile_path,
        metadata=_parse_metadata(_bytes(metadata_value)),
        threads=threads,
        time_windows=_repeated_varints(fields, 6),
    )


def method_stats(thread: ThreadProfile) -> dict[str, MethodStats]:
    """Aggregate nodes without double-counting nested identical methods.

    raw_inclusive intentionally sums every occurrence. inclusive only counts an
    occurrence when the same method key is not already in its ancestry. This is
    more useful for spark Java profiles containing synthetic/bridge frames with
    the same class and method name nested directly inside each other.
    """

    stats: dict[str, MethodStats] = {}

    def visit(node: StackNode, ancestry: frozenset[str], active_refs: frozenset[int]):
        node_id = id(node)
        if node_id in active_refs:
            raise ProfileFormatError(f"cycle in flattened node graph at {node.display_name}")
        key = node.group_key
        current = stats.setdefault(
            key,
            MethodStats(key, key),
        )
        current.occurrences += 1
        current.raw_inclusive += node.value
        if key not in ancestry:
            current.inclusive += node.value

        children = thread.children(node)
        child_value = sum(child.value for child in children)
        current.self_value += max(0.0, node.value - child_value)
        next_ancestry = ancestry | {key}
        next_active = active_refs | {node_id}
        for child in children:
            visit(child, next_ancestry, next_active)

    for root in thread.children():
        visit(root, frozenset(), frozenset())
    return stats


def _matches(pattern: re.Pattern[str] | None, stats: MethodStats) -> bool:
    return pattern is None or pattern.search(stats.method_key) is not None


def _metadata_dict(profile: SparkProfile) -> dict:
    result = asdict(profile.metadata)
    result.update(
        engine_name=profile.metadata.engine_name,
        mode_name=profile.metadata.mode_name,
        unit=profile.metadata.unit,
        duration_ms=profile.metadata.duration_ms,
        time_windows=profile.time_windows,
    )
    return result


def _summary_dict(
    profile: SparkProfile,
    thread: ThreadProfile,
    top: int,
    match: re.Pattern[str] | None,
) -> dict:
    stats = [stat for stat in method_stats(thread).values() if _matches(match, stat)]
    inclusive = sorted(stats, key=lambda stat: stat.inclusive, reverse=True)[:top]
    self_values = sorted(stats, key=lambda stat: stat.self_value, reverse=True)[:top]

    def encode(stat: MethodStats) -> dict:
        value = asdict(stat)
        value["share_percent"] = stat.share(thread.value)
        value["self_share_percent"] = stat.self_share(thread.value)
        return value

    return {
        "path": str(profile.path),
        "metadata": _metadata_dict(profile),
        "thread": {
            "name": thread.name,
            "value": thread.value,
            "node_count": len(thread.nodes),
            "unit": profile.metadata.unit,
        },
        "top_inclusive": [encode(stat) for stat in inclusive],
        "top_self": [encode(stat) for stat in self_values],
    }


def _print_summary(
    profile: SparkProfile,
    thread: ThreadProfile,
    top: int,
    match: re.Pattern[str] | None,
) -> None:
    metadata = profile.metadata
    interval_unit = "us" if metadata.sampler_mode == 0 else "bytes"
    print(f"file: {profile.path}")
    print(
        f"profile: {metadata.duration_ms / 1000:.3f}s, "
        f"{metadata.engine_name}/{metadata.mode_name}, interval={metadata.interval}{interval_unit}, "
        f"windows={len(profile.time_windows)}"
    )
    print(
        f"platform: {metadata.platform_name} {metadata.platform_version}, "
        f"Minecraft {metadata.minecraft_version}, Java {metadata.java_version}"
    )
    print(
        f"uptime: system={metadata.system_uptime_ms / 1000:.3f}s, "
        f"platform={metadata.platform_uptime_ms / 1000:.3f}s; "
        f"entities={metadata.entity_count}, sources={metadata.source_count}"
    )
    print(
        f"thread: {thread.name!r}, value={thread.value:.3f}{metadata.unit}, "
        f"nodes={len(thread.nodes)}"
    )

    stats = [stat for stat in method_stats(thread).values() if _matches(match, stat)]
    print("\ntop inclusive (nested identical methods deduplicated):")
    for stat in sorted(stats, key=lambda item: item.inclusive, reverse=True)[:top]:
        print(
            f"{stat.share(thread.value):8.3f}% "
            f"{stat.inclusive:12.3f}{metadata.unit} "
            f"self={stat.self_share(thread.value):7.3f}% "
            f"n={stat.occurrences:3d} {stat.display_name}"
        )

    print("\ntop self:")
    for stat in sorted(stats, key=lambda item: item.self_value, reverse=True)[:top]:
        print(
            f"{stat.self_share(thread.value):8.3f}% "
            f"{stat.self_value:12.3f}{metadata.unit} "
            f"incl={stat.share(thread.value):7.3f}% "
            f"n={stat.occurrences:3d} {stat.display_name}"
        )


def _comparison_rows(
    before: SparkProfile,
    before_thread: ThreadProfile,
    after: SparkProfile,
    after_thread: ThreadProfile,
    match: re.Pattern[str] | None,
) -> list[dict]:
    before_stats = method_stats(before_thread)
    after_stats = method_stats(after_thread)
    rows: list[dict] = []
    for key in before_stats.keys() | after_stats.keys():
        left = before_stats.get(key, MethodStats(key, key))
        right = after_stats.get(key, MethodStats(key, key))
        if not _matches(match, left if key in before_stats else right):
            continue
        left_share = left.share(before_thread.value)
        right_share = right.share(after_thread.value)
        rows.append(
            {
                "method_key": key,
                "display_name": left.display_name if key in before_stats else right.display_name,
                "before_value": left.inclusive,
                "before_share_percent": left_share,
                "after_value": right.inclusive,
                "after_share_percent": right_share,
                "delta_percentage_points": right_share - left_share,
                "before_self_share_percent": left.self_share(before_thread.value),
                "after_self_share_percent": right.self_share(after_thread.value),
            }
        )
    return rows


def _print_compare(
    before: SparkProfile,
    before_thread: ThreadProfile,
    after: SparkProfile,
    after_thread: ThreadProfile,
    top: int,
    match: re.Pattern[str] | None,
) -> None:
    unit = before.metadata.unit
    if unit != after.metadata.unit:
        raise ProfileFormatError("cannot compare profiles with different sampler modes")
    print(
        f"before: {before.path} ({before.metadata.duration_ms / 1000:.3f}s, "
        f"thread={before_thread.name!r}, value={before_thread.value:.3f}{unit})"
    )
    print(
        f"after:  {after.path} ({after.metadata.duration_ms / 1000:.3f}s, "
        f"thread={after_thread.name!r}, value={after_thread.value:.3f}{unit})"
    )
    rows = _comparison_rows(before, before_thread, after, after_thread, match)

    def print_rows(title: str, selected: Iterable[dict]) -> None:
        print(f"\n{title}:")
        for row in selected:
            print(
                f"{row['before_share_percent']:8.3f}% -> "
                f"{row['after_share_percent']:8.3f}% "
                f"({row['delta_percentage_points']:+8.3f}pp) "
                f"{row['display_name']}"
            )

    print_rows(
        "largest inclusive reductions",
        sorted(rows, key=lambda row: row["delta_percentage_points"])[:top],
    )
    print_rows(
        "largest inclusive increases",
        sorted(rows, key=lambda row: row["delta_percentage_points"], reverse=True)[:top],
    )


def _walk_tree(
    thread: ThreadProfile,
    node: StackNode,
    depth: int,
    max_depth: int,
    min_value: float,
    thread_value: float,
    active: frozenset[int],
) -> Iterator[str]:
    node_id = id(node)
    if node_id in active:
        yield "  " * depth + f"[cycle] {node.display_name}"
        return
    if node.value < min_value:
        return
    yield (
        "  " * depth
        + f"{100.0 * node.value / thread_value:8.3f}% {node.value:12.3f} "
        + node.display_name
    )
    if depth >= max_depth:
        return
    next_active = active | {node_id}
    for child in sorted(thread.children(node), key=lambda item: item.value, reverse=True):
        yield from _walk_tree(
            thread,
            child,
            depth + 1,
            max_depth,
            min_value,
            thread_value,
            next_active,
        )


def _compile_pattern(value: str | None) -> re.Pattern[str] | None:
    return re.compile(value, re.IGNORECASE) if value else None


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    summary = subparsers.add_parser("summary", help="print metadata and hot methods")
    summary.add_argument("profile")
    summary.add_argument("--thread")
    summary.add_argument("--top", type=int, default=20)
    summary.add_argument("--match", help="case-insensitive regex applied to method keys")
    summary.add_argument("--json", action="store_true")

    compare = subparsers.add_parser("compare", help="compare two profiles by method share")
    compare.add_argument("before")
    compare.add_argument("after")
    compare.add_argument("--thread")
    compare.add_argument("--top", type=int, default=20)
    compare.add_argument("--match", help="case-insensitive regex applied to method keys")
    compare.add_argument("--json", action="store_true")

    tree = subparsers.add_parser("tree", help="print subtrees rooted at matching methods")
    tree.add_argument("profile")
    tree.add_argument("root", help="case-insensitive regex applied to method keys")
    tree.add_argument("--thread")
    tree.add_argument("--depth", type=int, default=4)
    tree.add_argument("--min-share", type=float, default=0.05)
    tree.add_argument("--max-roots", type=int, default=5)
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        if args.command == "summary":
            profile = read_profile(args.profile)
            thread = profile.select_thread(args.thread)
            pattern = _compile_pattern(args.match)
            if args.json:
                print(json.dumps(_summary_dict(profile, thread, args.top, pattern), indent=2))
            else:
                _print_summary(profile, thread, args.top, pattern)
            return 0

        if args.command == "compare":
            before = read_profile(args.before)
            after = read_profile(args.after)
            before_thread = before.select_thread(args.thread)
            after_thread = after.select_thread(args.thread)
            pattern = _compile_pattern(args.match)
            rows = _comparison_rows(before, before_thread, after, after_thread, pattern)
            if args.json:
                print(
                    json.dumps(
                        {
                            "before": _metadata_dict(before),
                            "after": _metadata_dict(after),
                            "before_thread_value": before_thread.value,
                            "after_thread_value": after_thread.value,
                            "methods": sorted(
                                rows,
                                key=lambda row: abs(row["delta_percentage_points"]),
                                reverse=True,
                            )[: args.top],
                        },
                        indent=2,
                    )
                )
            else:
                _print_compare(
                    before, before_thread, after, after_thread, args.top, pattern
                )
            return 0

        if args.command == "tree":
            profile = read_profile(args.profile)
            thread = profile.select_thread(args.thread)
            pattern = _compile_pattern(args.root)
            assert pattern is not None
            roots = sorted(
                [node for node in thread.nodes if pattern.search(node.method_key)],
                key=lambda node: node.value,
                reverse=True,
            )[: args.max_roots]
            min_value = thread.value * args.min_share / 100.0
            for index, root in enumerate(roots):
                if index:
                    print()
                print(f"root {index + 1}/{len(roots)}:")
                print(
                    "\n".join(
                        _walk_tree(
                            thread,
                            root,
                            0,
                            args.depth,
                            min_value,
                            thread.value,
                            frozenset(),
                        )
                    )
                )
            if not roots:
                raise ProfileFormatError(f"no method matched {args.root!r}")
            return 0

        raise AssertionError(args.command)
    except (OSError, ProfileFormatError, re.error) as error:
        print(f"sparkprofile: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
