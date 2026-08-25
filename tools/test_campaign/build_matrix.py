"""Assembles docs/TEST_MATRIX.md from tools/test_campaign/results/*.json.

    python tools/test_campaign/build_matrix.py
"""
from __future__ import annotations

import json
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
RESULTS = HERE / "results"
OUT = HERE.parent.parent / "docs" / "TEST_MATRIX.md"

SECTION_ORDER = ["login", "tag_assignment", "offload", "settings", "presence"]
SECTION_TITLES = {
    "login": "Login & Session (contract §4)",
    "tag_assignment": "Tag Assignment (contract §5)",
    "offload": "Offload (contract §6)",
    "settings": "Settings, Provisioning & Diagnostics",
    "presence": "Presence & Reconnect (contract §8)",
}


def main():
    sections = []
    for name in SECTION_ORDER:
        path = RESULTS / f"{name}.json"
        if path.exists():
            sections.append(json.loads(path.read_text()))

    total = sum(len(s["results"]) for s in sections)
    passed = sum(1 for s in sections for r in s["results"] if r["verdict"] == "PASS")

    lines = [
        "# Station 1 App — Functional Test Matrix",
        "",
        f"Campaign against the v3.1.0 backend simulator (`tools/station_sim.py`) on the",
        f"physical Chainway C72 (`HC720DE260100322`), driven over adb with scans injected",
        f"as scanner broadcasts. Simulator itself verified by {74} pytest cases and a",
        "17-check live protocol run (`tools/test_campaign/fake_scanner.py`).",
        "",
        f"**Overall: {passed}/{total} passed** — generated "
        + time.strftime("%Y-%m-%d %H:%M UTC", time.gmtime()),
        "",
    ]
    for section in sections:
        name = section["campaign"]
        results = section["results"]
        ok = sum(1 for r in results if r["verdict"] == "PASS")
        lines += [
            f"## {SECTION_TITLES.get(name, name)} — {ok}/{len(results)}",
            "",
            "| ID | Case | Verdict | Notes |",
            "|---|---|---|---|",
        ]
        for r in results:
            notes = "; ".join(r["notes"]) if r["notes"] else ""
            if r["verdict"] != "PASS" and r["detail"]:
                notes = (notes + "; " if notes else "") + r["detail"]
            notes = notes.replace("|", "\\|").replace("\n", " ")
            lines.append(f"| {r['id']} | {r['description']} | {r['verdict']} | {notes} |")
        lines.append("")

    OUT.write_text("\n".join(lines), encoding="utf-8")
    print(f"wrote {OUT}: {passed}/{total} passed")


if __name__ == "__main__":
    main()
