#!/usr/bin/env python3
"""
RAG quality eval harness — item #10 of the chunk/answer-quality improvement pass.

Runs every question in golden_questions.json against a live cobalt-rag-api
instance's POST /api/ask/formal, scores each response against its expected
programIds/keywords, and writes a timestamped results file under eval/results/
so runs are comparable over time (e.g. before vs. after a retrieval change).

Usage:
    python3 run_eval.py [--base-url http://localhost:8083] [--label baseline]

Deliberately dependency-free (stdlib only) — matches the pragmatic verification
scripts already used to check things live in this project, not a new test
framework. Scoring is intentionally simple (substring/membership checks), not
a semantic grader — good enough to catch regressions, not a RAGAS-style scorer.
"""
import argparse
import json
import sys
import time
import urllib.request
import urllib.error
from pathlib import Path
from datetime import datetime, timezone

HERE = Path(__file__).parent


def ask(base_url: str, question: str, view_mode: str, timeout: int = 60) -> dict:
    body = json.dumps({"question": question, "viewMode": view_mode}).encode()
    req = urllib.request.Request(
        f"{base_url}/api/ask/formal",
        data=body,
        headers={"Content-Type": "application/json"},
    )
    start = time.time()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            elapsed = time.time() - start
            return {"ok": True, "elapsed": elapsed, "data": json.loads(resp.read())}
    except urllib.error.HTTPError as e:
        return {"ok": False, "elapsed": time.time() - start, "error": f"HTTP {e.code}: {e.read()[:200]}"}
    except Exception as e:
        return {"ok": False, "elapsed": time.time() - start, "error": str(e)}


def score_one(case: dict, result: dict) -> dict:
    if not result["ok"]:
        return {
            "id": case["id"], "question": case["question"], "pass": False,
            "reason": f"request failed: {result['error']}", "elapsed": result["elapsed"],
        }

    data = result["data"]
    answer = (data.get("answer") or "").lower()
    sources = data.get("sources") or []
    chunks_retrieved = data.get("chunksRetrieved", 0)
    source_program_ids = {s.get("programId") for s in sources if s.get("programId")}

    expected_programs = set(case.get("expectedProgramIds") or [])
    program_hit = (not expected_programs) or bool(expected_programs & source_program_ids)

    expected_keywords = case.get("expectedKeywords") or []
    keyword_hits = [kw for kw in expected_keywords if kw.lower() in answer]
    keyword_ratio = (len(keyword_hits) / len(expected_keywords)) if expected_keywords else 1.0

    retrieved_something = chunks_retrieved > 0
    looks_out_of_scope = "i can help with four areas" in answer or "cannot assist" in answer or "cannot help" in answer

    passed = retrieved_something and program_hit and keyword_ratio >= 0.5 and not (expected_programs and looks_out_of_scope)

    return {
        "id": case["id"],
        "question": case["question"],
        "pass": passed,
        "chunksRetrieved": chunks_retrieved,
        "expectedProgramIds": sorted(expected_programs),
        "actualProgramIds": sorted(source_program_ids),
        "programHit": program_hit,
        "keywordHits": keyword_hits,
        "keywordTotal": len(expected_keywords),
        "keywordRatio": round(keyword_ratio, 2),
        "outOfScope": looks_out_of_scope,
        "elapsedSeconds": round(result["elapsed"], 2),
        "answerPreview": (data.get("answer") or "")[:180],
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://localhost:8083")
    parser.add_argument("--label", default="run", help="label for this run, used in the results filename")
    args = parser.parse_args()

    questions = json.loads((HERE / "golden_questions.json").read_text())
    print(f"Loaded {len(questions)} questions. Target: {args.base_url}\n")

    results = []
    for i, case in enumerate(questions, 1):
        print(f"[{i}/{len(questions)}] {case['id']} ... ", end="", flush=True)
        result = ask(args.base_url, case["question"], case.get("viewMode", "tech"))
        scored = score_one(case, result)
        results.append(scored)
        status = "PASS" if scored["pass"] else "FAIL"
        print(f"{status}  ({scored.get('elapsedSeconds', '?')}s)")
        if not scored["pass"]:
            print(f"    programHit={scored.get('programHit')} "
                  f"expected={scored.get('expectedProgramIds')} actual={scored.get('actualProgramIds')} "
                  f"keywords={scored.get('keywordHits')}/{scored.get('keywordTotal')} "
                  f"chunksRetrieved={scored.get('chunksRetrieved')}")

    passed = sum(1 for r in results if r["pass"])
    total = len(results)
    avg_elapsed = sum(r.get("elapsedSeconds", 0) for r in results) / total if total else 0

    print(f"\n{'='*60}")
    print(f"Score: {passed}/{total} ({100*passed/total:.0f}%)   avg latency: {avg_elapsed:.2f}s")
    print(f"{'='*60}")

    results_dir = HERE / "results"
    results_dir.mkdir(exist_ok=True)
    timestamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    out_path = results_dir / f"{timestamp}_{args.label}.json"
    out_path.write_text(json.dumps({
        "timestamp": timestamp,
        "label": args.label,
        "baseUrl": args.base_url,
        "score": {"passed": passed, "total": total, "avgLatencySeconds": round(avg_elapsed, 2)},
        "results": results,
    }, indent=2))
    print(f"Results written to {out_path}")
    return 0 if passed == total else 1


if __name__ == "__main__":
    sys.exit(main())
