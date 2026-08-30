#!/usr/bin/env python3
"""Verifies the reactor's wiring before a release is deployed.

Central is immutable and release.yml refuses to reuse a version, so whatever ships is permanent.
The BOM check is the reason this exists: its entries are maintained by hand, and its own
description says the annotations artifact joins when it lands, so it will at some point name a
coordinate before the module producing it is there.
"""

from __future__ import annotations

import sys
import xml.etree.ElementTree as ElementTree
from dataclasses import dataclass
from pathlib import Path
from typing import Optional
from xml.etree.ElementTree import Element

POM_NAMESPACE = {"m": "http://maven.apache.org/POM/4.0.0"}
ROOT_POM = Path("pom.xml")
BOM_MODULE = "entity-metamodel-bom"


def text_of(element: Optional[Element]) -> Optional[str]:
    if element is None:
        return None

    return element.text


@dataclass(frozen=True)
class ReactorModule:
    directory: str
    artifact_id: str
    parent_version: Optional[str]
    own_version: Optional[str]


def declared_module_directories(root: Element) -> list[str]:
    directories = []

    for declaration in root.findall("m:modules/m:module", POM_NAMESPACE):
        if declaration.text is not None:
            directories.append(declaration.text.strip())

    return directories


def reactor_modules(root: Element) -> list[ReactorModule]:
    modules = []

    for directory in declared_module_directories(root):
        pom = Path(directory) / "pom.xml"

        if not pom.is_file():
            continue

        module_root = ElementTree.parse(pom).getroot()
        artifact_id = text_of(module_root.find("m:artifactId", POM_NAMESPACE))

        if artifact_id is None:
            continue

        parent_version = text_of(module_root.find("m:parent/m:version", POM_NAMESPACE))
        own_version = text_of(module_root.find("m:version", POM_NAMESPACE))

        modules.append(ReactorModule(directory, artifact_id, parent_version, own_version))

    return modules


def managed_dependencies(pom: Path) -> list[tuple[Optional[str], Optional[str]]]:
    declarations = ElementTree.parse(pom).getroot().findall(
        "m:dependencyManagement/m:dependencies/m:dependency", POM_NAMESPACE
    )

    managed = []
    for declaration in declarations:
        group_id = text_of(declaration.find("m:groupId", POM_NAMESPACE))
        artifact_id = text_of(declaration.find("m:artifactId", POM_NAMESPACE))
        managed.append((group_id, artifact_id))

    return managed


def check_version_stamped(root: Element, version: str) -> list[str]:
    own_version = text_of(root.find("m:version", POM_NAMESPACE))

    if own_version != version:
        return [f"reactor is at {own_version}, expected {version} — did the version stamp run?"]

    return []


def check_modules_readable(root: Element, modules: list[ReactorModule]) -> list[str]:
    parsed = {module.directory for module in modules}
    failures = []

    for directory in declared_module_directories(root):
        if directory not in parsed:
            failures.append(
                f"{directory} is declared as a module but its POM could not be read"
            )

    return failures


def check_modules_stamped(modules: list[ReactorModule], version: str) -> list[str]:
    failures = []

    for module in modules:
        if module.parent_version != version:
            failures.append(
                f"{module.directory} refers to parent {module.parent_version}, expected {version}"
            )

        if module.own_version is not None and module.own_version != version:
            failures.append(
                f"{module.directory} declares its own version {module.own_version}"
            )

    return failures


def check_bom_matches_reactor(root: Element, modules: list[ReactorModule]) -> list[str]:
    pom = Path(BOM_MODULE) / "pom.xml"

    if not pom.is_file():
        return []

    family_group_id = text_of(root.find("m:groupId", POM_NAMESPACE))
    if family_group_id is None:
        return [f"the reactor declares no groupId, so {BOM_MODULE}'s promises cannot be checked"]

    produced = {module.artifact_id for module in modules}
    promised = set()

    for group_id, artifact_id in managed_dependencies(pom):
        # Pinning a third party's version promises nothing about what this release publishes.
        if group_id != family_group_id:
            continue

        promised.add(artifact_id)

    failures = []

    for artifact_id in sorted(promised - produced):
        failures.append(
            f"{BOM_MODULE} manages {artifact_id}, which no module in this reactor builds"
        )

    for module in modules:
        if module.directory == BOM_MODULE:
            continue

        if module.artifact_id not in promised:
            failures.append(
                f"{module.artifact_id} publishes but {BOM_MODULE} does not manage it"
            )

    return failures


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print("usage: verify-release-wiring.py <release-version>", file=sys.stderr)
        return 2

    if not ROOT_POM.is_file():
        print(f"::error::{ROOT_POM} is missing — this is not a reactor checkout")
        return 1

    root = ElementTree.parse(ROOT_POM).getroot()

    modules = reactor_modules(root)

    failures = []
    failures.extend(check_version_stamped(root, argv[1]))
    failures.extend(check_modules_readable(root, modules))
    failures.extend(check_modules_stamped(modules, argv[1]))
    failures.extend(check_bom_matches_reactor(root, modules))

    if failures:
        print("::error::release wiring is wrong: " + "; ".join(failures))
        return 1

    print(f"reactor at {argv[1]}; the BOM promises only modules this reactor builds")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
