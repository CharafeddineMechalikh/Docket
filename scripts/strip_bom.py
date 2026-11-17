"""Strips UTF-8 byte-order marks from the Example 14 files and checks the rest of the repo.

PowerShell's ``Set-Content -Encoding UTF8`` writes a BOM, which a ``.properties`` reader takes for
the first character of the first key, so the first comment line of a scenario comes back as an
unused key named ``"?#"`` instead of as a comment. The committed scenarios in this repository carry
no BOM, so neither may the new ones.
"""
import glob
import os

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
EXAMPLE = os.path.join(ROOT, "examples", "14-fidelity-aware-edge-routing")

fixed = []
for path in glob.glob(os.path.join(EXAMPLE, "**", "*.properties"), recursive=True):
    with open(path, "rb") as handle:
        raw = handle.read()
    if raw.startswith(b"\xef\xbb\xbf"):
        with open(path, "wb") as handle:
            handle.write(raw[3:])
        fixed.append(os.path.relpath(path, ROOT))
print("stripped BOM from %d file(s):" % len(fixed))
for path in fixed:
    print("  " + path)

committed = glob.glob(os.path.join(ROOT, "examples", "*", "scenarios", "*.properties"))
committed += glob.glob(os.path.join(ROOT, "scenarios", "*.properties"))
bommed = [p for p in committed if open(p, "rb").read(3) == b"\xef\xbb\xbf"]
print("checked %d other scenario file(s); %d carry a BOM" % (len(committed), len(bommed)))
