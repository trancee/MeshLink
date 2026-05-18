#!/usr/bin/env python3

from __future__ import annotations

import re
from dataclasses import dataclass, field
from pathlib import Path

INPUT_PATH = Path("meshlink/api/jvm/meshlink.api")
OUTPUT_PATH = Path("docs/reference/generated-public-api.md")

DECLARATION_PATTERN = re.compile(r"^public (?P<prefix>.+?) class (?P<name>\S+)(?P<suffix>.*)\{$")
FIELD_PATTERN = re.compile(r"^\s*public .* field (?P<name>\S+) (?P<type>\S+);$")
FUNCTION_PATTERN = re.compile(r"^\s*public .* fun (?P<name>\S+) \((?P<params>[^)]*)\)(?P<return>\S+)?$")

SKIPPED_FUNCTIONS = {"equals", "hashCode", "toString", "valueOf", "values", "getEntries"}


@dataclass
class TypeSurface:
    declaration: str
    internal_name: str
    package_name: str
    has_instance_field: bool = False
    constants: list[str] = field(default_factory=list)
    members: list[str] = field(default_factory=list)

    @property
    def display_name(self) -> str:
        return self.internal_name.replace("/", ".").replace("$", ".")

    @property
    def kind(self) -> str:
        if "annotation class" in self.declaration:
            return "annotation"
        if "interface class" in self.declaration:
            return "interface"
        if ": java/lang/Enum" in self.declaration:
            return "enum"
        if self.has_instance_field and "<init>" not in self.members:
            return "object"
        if "$" in self.internal_name:
            return "nested type"
        if "abstract class" in self.declaration:
            return "abstract class"
        return "class"

    @property
    def surface_summary(self) -> str:
        entries: list[str] = []
        if self.constants:
            entries.append("constants: " + ", ".join(f"`{name}`" for name in self.constants))
        if self.members:
            entries.append(", ".join(f"`{name}`" for name in self.members))
        return "<br>".join(entries) if entries else "—"


def package_name_for(internal_name: str) -> str:
    return internal_name.rsplit("/", 1)[0].replace("/", ".")


def property_name(candidate: str) -> str:
    return candidate[:1].lower() + candidate[1:]


def normalized_member_name(name: str) -> str:
    return name.split("$", 1)[0].split("-", 1)[0]


def humanize_function(name: str, params: str) -> str | None:
    normalized_name = normalized_member_name(name)
    if normalized_name in SKIPPED_FUNCTIONS or normalized_name.endswith("$default"):
        return None
    if normalized_name == "<init>":
        return "init"
    if normalized_name.startswith("get") and not params and len(normalized_name) > 3:
        return property_name(normalized_name[3:])
    if normalized_name.startswith("set") and len(normalized_name) > 3:
        return property_name(normalized_name[3:]) + " ="
    return f"{normalized_name}()"


def parse_types() -> list[TypeSurface]:
    lines = INPUT_PATH.read_text(encoding="utf-8").splitlines()
    parsed: list[TypeSurface] = []
    current: TypeSurface | None = None

    for line in lines:
        declaration = DECLARATION_PATTERN.match(line)
        if declaration is not None:
            internal_name = declaration.group("name")
            if internal_name.endswith("$DefaultImpls"):
                current = None
                continue
            current = TypeSurface(
                declaration=line.strip(),
                internal_name=internal_name,
                package_name=package_name_for(internal_name),
            )
            parsed.append(current)
            continue

        if current is None:
            continue

        if line.strip() == "}":
            current = None
            continue

        field_match = FIELD_PATTERN.match(line)
        if field_match is not None:
            field_name = field_match.group("name")
            if field_name == "INSTANCE":
                current.has_instance_field = True
                continue
            if current.kind == "enum":
                current.constants.append(field_name)
            continue

        function_match = FUNCTION_PATTERN.match(line)
        if function_match is None:
            continue
        humanized = humanize_function(
            function_match.group("name"),
            function_match.group("params").strip(),
        )
        if humanized is None:
            continue
        if humanized not in current.members:
            current.members.append(humanized)

    return parsed


def render(types: list[TypeSurface]) -> str:
    grouped: dict[str, list[TypeSurface]] = {}
    for surface in types:
        grouped.setdefault(surface.package_name, []).append(surface)

    parts = [
        "# Generated public API symbol tables",
        "",
        "This page is generated from the checked-in public API dump used for binary compatibility validation.",
        "",
        "Use this page as a completeness appendix for the public SDK surface.",
        "For behavior and usage, prefer the human-written [MeshLink SDK API reference](meshlink-sdk-api.md) and [How to use MeshLink from Swift](../how-to/use-meshlink-from-swift.md).",
        "",
    ]

    for package_name in sorted(grouped):
        parts.append(f"## Package `{package_name}`")
        parts.append("")
        parts.append("| Type | Kind | Public surface |")
        parts.append("|---|---|---|")
        for surface in sorted(grouped[package_name], key=lambda item: item.display_name):
            parts.append(
                f"| `{surface.display_name}` | {surface.kind} | {surface.surface_summary} |"
            )
        parts.append("")

    return "\n".join(parts)


def main() -> int:
    surfaces = parse_types()
    OUTPUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT_PATH.write_text(render(surfaces), encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
