"""Aggregate the Example 14 fidelity/placement ablation into results/fidelity_ablation.

Metric definition: a request is *served* when its final state is COMPLETED and
its end-to-end response meets the class objective (``e2e_met``).  Docket's
``all_slo_met`` column is false for every request in this scenario because the
trace carries a zero token-time objective, so it is not used here.
"""
from __future__ import annotations

import json
import re
from pathlib import Path

import numpy as np
import pandas as pd

ROOT = Path(__file__).resolve().parents[2]
SWEEP = ROOT / "target" / "ex14sweep"
OUT = ROOT / "results" / "fidelity_ablation"
OUT.mkdir(parents=True, exist_ok=True)

ARM_LABEL = {
    "ex14-naive": "naive placement, full fidelity",
    "ex14-fidelity": "naive placement + ladder",
    "ex14-static-edge": "static edge tier + ladder",
    "ex14-joint-nofid": "joint placement, full fidelity",
    "ex14-joint": "joint placement + ladder",
}
ORDER = ["ex14-naive", "ex14-fidelity", "ex14-static-edge",
         "ex14-joint-nofid", "ex14-joint"]


def load() -> pd.DataFrame:
    frames = []
    for folder in sorted(SWEEP.glob("ex14-*-ov*/*/rep*/requests.csv")):
        name = folder.parents[1].name
        match = re.match(r"(ex14-[a-z-]+)-ov([0-9.]+)$", name)
        if not match:
            continue
        arm, level = match.group(1), float(match.group(2))
        data = pd.read_csv(folder)
        data["arm"] = arm
        data["overhead"] = level
        data["seed"] = int(folder.parent.name.replace("rep", ""))
        data["served"] = ((data["final_state"] == "COMPLETED")
                          & (data["e2e_met"] == True)).astype(int)
        data["token_ratio"] = (data["output_tokens_generated"]
                               / data["output_tokens_requested"].replace(0, np.nan))
        frames.append(data)
    if not frames:
        raise SystemExit(f"No request records under {SWEEP}")
    return pd.concat(frames, ignore_index=True)


def summarise(data: pd.DataFrame) -> tuple[pd.DataFrame, pd.DataFrame]:
    rows = []
    for (arm, level), block in data.groupby(["arm", "overhead"]):
        per_seed = block.groupby("seed")["served"].mean()
        rows.append({
            "arm": ARM_LABEL.get(arm, arm),
            "arm_key": arm,
            "overhead": level,
            "requests": int(len(block)),
            "seeds": int(block["seed"].nunique()),
            "served_ratio": float(per_seed.mean()),
            "served_sd": float(per_seed.std(ddof=1)) if len(per_seed) > 1 else float("nan"),
            "mean_fidelity": float(block["fidelity_level"].mean()),
            "token_ratio": float(block["token_ratio"].mean()),
            "e2e_mean_s": float(block["e2e_s"].mean()),
            "ttft_mean_s": float(block["ttft_s"].mean()),
            "energy_j": float(block["energy_j"].mean()),
            "cloud_share": float((block["replica_id"] == 4).mean()),
        })
    runs = pd.DataFrame(rows).sort_values(["overhead", "arm_key"]).reset_index(drop=True)
    pivot = runs.pivot_table(index="overhead", columns="arm_key",
                             values="served_ratio").reset_index()
    return runs, pivot


def findings(runs: pd.DataFrame) -> str:
    wide = runs.pivot(index="overhead", columns="arm_key", values="served_ratio")
    fid = runs.pivot(index="overhead", columns="arm_key", values="mean_fidelity")
    ratio = runs[runs["arm_key"] == "ex14-joint"].pivot(
        index="overhead", columns="arm_key", values="token_ratio")["ex14-joint"]

    lines = [
        "# Docket check of the P2 component ablation",
        "",
        "Docket is **our own** discrete-event LLM-serving simulator. This experiment is",
        "therefore an internal robustness check with a different cost model, not an",
        "independent-platform replication. It asks one question: does the P2 conclusion",
        "that placement selection is inert survive replacing the linear MI/payload",
        "surrogate with prefill, decode, batching and a KV cache?",
        "",
        "The answer is **no, and that is informative**. In Docket the placement decision",
        "dominates, and the two studies together locate the boundary of the P2 claim.",
        "",
        "## What was run",
        "",
        "Example 14 (`examples/14-fidelity-aware-edge-routing`) replays one generated",
        "agentic trace at four overhead levels (1x, 1.5x, 2x, 3x) through five arms that",
        "change exactly two decisions: the routing rule and whether the deadline ladder",
        "runs. Five seeds per cell, 20 cells. The model is an invented 1B decoder, the",
        "fleet is a phone accelerator, two on-premise servers and a cloud card behind a",
        "40 ms WAN, and every cost parameter is marked `synthetic`.",
        "",
        "A request counts as served when its final state is `COMPLETED` **and** it meets",
        "its end-to-end class objective (0.80 s for retrieval, 0.25 s for control).",
        "Docket's `all_slo_met` column is false for every request because the generated",
        "trace carries a zero token-time objective; it is deliberately not used.",
        "",
        "| Overhead | naive, full fidelity | ladder only | static edge + ladder | joint, no ladder | joint + ladder |",
        "|---|---|---|---|---|---|",
    ]
    for level, row in wide.iterrows():
        cells = " | ".join(f"{row[a]:.3f}" for a in ORDER)
        lines.append(f"| {level:g}x | {cells} |")
    lines += [
        "",
        "The mean generated-token ratio under the joint policy falls from "
        f"{ratio.loc[1.0]:.2f} at 1x to {ratio.loc[3.0]:.2f} at 3x, so here the fidelity "
        "reduction has a real, priced quality cost: about "
        f"{100 * (1 - ratio.loc[3.0]):.0f}% of the requested reasoning tokens are shed at 3x.",
        "",
        "## Reading the result",
        "",
        "**Placement is not inert in this scenario.** The joint rule routes to the cloud",
        "card when the on-premise machines cannot hold the context, and the 40 ms WAN is",
        "cheap relative to the 0.80 s retrieval deadline. With the cloud removed, the",
        "same ladder serves almost nothing at every level: removing placement costs",
        "97--99 points. That is the opposite of P2, where the joint scorer was worth",
        "exactly zero once the static edge tier was available.",
        "",
        "**The ladder matters conditionally, not unconditionally.** At 3x the joint",
        f"policy without the ladder serves {wide.loc[3.0, 'ex14-joint-nofid']:.3f}; with it, "
        f"{wide.loc[3.0, 'ex14-joint']:.3f}, a gain of "
        f"{100 * (wide.loc[3.0, 'ex14-joint'] - wide.loc[3.0, 'ex14-joint-nofid']):.1f} points. "
        "But attaching the ladder to the untuned load balancer or to the static edge tier",
        "buys nothing. The two decisions interact: the ladder rescues a policy that",
        "already has somewhere feasible to put the shrunken request, and cannot rescue",
        "one that does not. P2's three-way ANOVA reports exactly this term",
        "(`placement x ladder x overhead`, partial eta-squared = 0.692); Docket is a",
        "second measurement of the same interaction in a regime where the placement",
        "factor has room to move.",
        "",
        "**This is the heterogeneous regime of P1.** The cloud card is seven times the",
        "edge server (700 vs 100 TFLOPS) behind a 40 ms hop. P1's capacity experiment",
        "found an architecture benefit of +65.7 points precisely when the edge was weak",
        "and the cloud fast, and -3.6 to -19.6 when the edge was adequate. Docket is the",
        "first case; P2's 30-device fleet is the second. Neither paper can claim the",
        "other's regime, and the Docket check is the evidence for that boundary.",
        "",
        "## What this does not establish",
        "",
        "* The hardware is invented (round-number capacities, synthetic roofline), so",
        "  these are not predictions about a real phone or GPU.",
        "* The trace is generated by `make_agentic_trace.py`, not recorded from a",
        "  production agent, so arrival burstiness is synthetic.",
        "* Docket is the authors' simulator. The result is an internal validation with a",
        "  different cost model, and is reported as such in the paper.",
        "* The `static edge + ladder` arm refuses requests whose context the edge tier",
        "  cannot hold, which is by design in `EdgeTierOnlyRouting`; it is a data-sovereignty",
        "  baseline, not a capacity-matched edge tier. P2's static edge arm has ample",
        "  capacity, which is why the two studies differ.",
        "",
        "## Reproduction",
        "",
        "```powershell",
        "# From the repository root:",
        "mvn -q -DskipTests package",
        "javac -cp target/docket-1.0.1.jar -d target/ex14-classes `",
        "  examples/14-fidelity-aware-edge-routing/src/main/java/com/mechalikh/docket/examples/fidelityedge/*.java",
        "powershell -File scripts/run_ex14.ps1",
        "python results/fidelity_ablation/aggregate.py",
        "```",
        "",
        "The committed outputs are `results/fidelity_ablation/summary.csv`, `pivot.csv`,",
        "`metadata.json` and this file.",
    ]
    return "\n".join(lines) + "\n"


def main() -> None:
    data = load()
    runs, pivot = summarise(data)
    runs.to_csv(OUT / "summary.csv", index=False)
    pivot.to_csv(OUT / "pivot.csv", index=False)
    (OUT / "FINDINGS.md").write_text(findings(runs), encoding="utf-8")
    metadata = {
        "simulator": "Docket (authors' own LLM-serving simulator)",
        "example": "14-fidelity-aware-edge-routing",
        "cost_provenance": "synthetic",
        "arms": ARM_LABEL,
        "metric": "COMPLETED and e2e met",
        "repetitions": 5,
        "cells": int(len(runs)),
    }
    (OUT / "metadata.json").write_text(json.dumps(metadata, indent=2), encoding="utf-8")
    print(pivot.to_string(index=False))


if __name__ == "__main__":
    main()
