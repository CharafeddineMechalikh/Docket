"""Writes the sibling arm scenarios of Example 14 from the committed joint arm.

Each arm differs from ``ex14-joint.properties`` in exactly the two decisions the ablation is
about: which placement rule names the replica, and whether the fidelity ladder runs at all.
Everything else — model, fleet, bearers, trace, serving limits, seed — is the same bytes, so a
difference in the results is a difference in those two decisions and nothing else.

Run it from this folder:  python make_arm_scenarios.py
"""
import io
import os

HERE = os.path.dirname(os.path.abspath(__file__))
BASE = os.path.join(HERE, "scenarios", "ex14-joint.properties")

EDGE_CLASS = "com.mechalikh.docket.examples.fidelityedge.EdgeTierOnlyRouting"
JOINT_CLASS = "com.mechalikh.docket.examples.fidelityedge.JointTierBudgetRouting"

# arm -> (routing rule, does the ladder run?)
ARMS = {
    # No ladder and no tier rule: the load balancer an operator runs when nothing is tuned.
    "ex14-naive": ("least-predicted-work", False),
    # The ladder alone, over the same untuned load balancer: isolates the reasoning budget.
    "ex14-fidelity": ("least-predicted-work", True),
    # The static edge tier plus the ladder: the strong baseline the joint policy must beat.
    "ex14-static-edge": (EDGE_CLASS, True),
    # The static edge tier without the ladder: completes the 2 x 3 placement-by-fidelity
    # factorial that the paper analyses, and isolates the ladder on the constrained tier.
    "ex14-edge-nofid": (EDGE_CLASS, False),
    # The joint placement with the ladder disabled: the arm that shows what shedding is worth.
    "ex14-joint-nofid": (JOINT_CLASS, False),
}

# The keys the arms replace; everything else is copied through untouched.
DROPPED = ("policy.routing.class", "policy.routing", "policy.fidelity", "policy.fidelity.class",
           "fidelity.levels", "fidelity.minTokens", "fidelity.margin")


def rewrite(base, arm, routing, fidelity):
    """Returns the text of one arm file, with the two decisions swapped and no comment drift."""
    kept = []
    skipComment = 0
    for line in base.split("\n"):
        key = line.split("=", 1)[0].strip() if "=" in line and not line.startswith("#") else ""
        if key in DROPPED:
            continue
        if not fidelity and line.startswith("# The fidelity ladder, in reasoning tokens"):
            # The paragraph that introduces the ladder belongs with the ladder: leaving it above a
            # line that says no policy runs would make the file describe a rule it does not have.
            skipComment = 4
            continue
        if skipComment > 0 and line.startswith("#"):
            skipComment -= 1
            continue
        kept.append(line)
    body = "\n".join(kept).replace("experiment.name=ex14-joint", "experiment.name=" + arm)
    lines = body.rstrip("\n").split("\n")
    # The routing and fidelity keys belong in the block that already held them, so the
    # generated file reads like the hand-written one rather than like a patch.
    for index, line in enumerate(lines):
        if line.startswith("cost.model="):
            block = []
            if routing.startswith("com."):
                block.append("policy.routing.class=" + routing)
            else:
                block.append("policy.routing=" + routing)
            if fidelity:
                block.append("policy.fidelity=deadline-ladder")
                block.append("fidelity.levels=1,0.5,0.25,0.1")
                block.append("fidelity.minTokens=64")
                block.append("fidelity.margin=0.15")
            else:
                block.append("# No fidelity policy: every request generates the trace it asked for.")
            lines[index:index] = block
            break
    return "\n".join(lines) + "\n"


def main():
    with io.open(BASE, encoding="utf-8") as handle:
        base = handle.read()
    for arm, (routing, fidelity) in ARMS.items():
        path = os.path.join(HERE, "scenarios", arm + ".properties")
        with io.open(path, "w", encoding="utf-8", newline="\n") as handle:
            handle.write(rewrite(base, arm, routing, fidelity))
        print("wrote scenarios/%s.properties" % arm)


if __name__ == "__main__":
    main()
