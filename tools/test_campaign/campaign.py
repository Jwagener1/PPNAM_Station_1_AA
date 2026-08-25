"""Tiny campaign framework: runs cases, records verdicts, writes results JSON.

    from campaign import Campaign
    c = Campaign("login")
    with c.case("L1", "SCRAM login succeeds") as case:
        ...assert...
        case.note("landed on MainActivity")
    c.finish()   # prints summary, writes results/login.json, exit code
"""
from __future__ import annotations

import json
import sys
import time
import traceback
from pathlib import Path

RESULTS = Path(__file__).resolve().parent / "results"


class _Case:
    def __init__(self, campaign, case_id, description):
        self.campaign = campaign
        self.id = case_id
        self.description = description
        self.notes: list[str] = []
        self.shots: list[str] = []

    def note(self, text: str):
        print(f"    note: {text}")
        self.notes.append(text)

    def shot(self, path):
        self.shots.append(str(path))

    def __enter__(self):
        print(f"[{self.id}] {self.description}")
        return self

    def __exit__(self, exc_type, exc, tb):
        verdict = "PASS" if exc_type is None else "FAIL"
        detail = ""
        if exc_type is not None:
            detail = "".join(traceback.format_exception_only(exc_type, exc)).strip()
            print(f"    {detail}")
        print(f"  {verdict}  {self.id}")
        self.campaign.results.append({
            "id": self.id,
            "description": self.description,
            "verdict": verdict,
            "detail": detail,
            "notes": self.notes,
            "shots": self.shots,
        })
        return True  # a failing case never aborts the campaign


class Campaign:
    def __init__(self, name: str):
        self.name = name
        self.results: list[dict] = []
        self.started = time.time()

    def case(self, case_id: str, description: str) -> _Case:
        return _Case(self, case_id, description)

    def finish(self) -> int:
        RESULTS.mkdir(exist_ok=True)
        out = RESULTS / f"{self.name}.json"
        out.write_text(json.dumps({
            "campaign": self.name,
            "ranAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
            "durationS": round(time.time() - self.started, 1),
            "results": self.results,
        }, indent=1))
        failed = [r for r in self.results if r["verdict"] != "PASS"]
        print(f"\n[{self.name}] {len(self.results) - len(failed)}/{len(self.results)} passed"
              + (f" — FAILED: {', '.join(r['id'] for r in failed)}" if failed else ""))
        print(f"results: {out}")
        return 1 if failed else 0


def expect(condition, message: str):
    if not condition:
        raise AssertionError(message)
    return True
