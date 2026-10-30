#!/usr/bin/env python3
"""Download the upstream trace files the replay examples are cut from.

The four sources are large: the Qwen trace is 56 MB of JSONL and BurstGPT is a 50 MB CSV, while
the windows this repository replays sit at the start of the first and inside the dense burst of
the second. A prefix is therefore fetched with an HTTP Range request, and only complete lines of
that prefix are used by convert_traces.py. Nothing is written outside target/traces-cache, which
is gitignored, and no converted output is produced here.

Usage:
    python examples/real-traces/fetch_traces.py [--cache DIR] [name ...]

Run it from the repository root. With no names, every source is fetched.
"""

import argparse
import hashlib
import sys
import urllib.error
import urllib.request
from pathlib import Path

SOURCES = {
    "azure-conv": {
        "url": "https://raw.githubusercontent.com/Azure/AzurePublicDataset/master/data/"
               "AzureLLMInferenceTrace_conv.csv",
        "file": "azure_conv_2023.csv",
        "bytes": None,
        "note": "the full 719 KB file, 19,366 requests recorded on 2023-11-16",
    },
    "qwen-traceA": {
        "url": "https://media.githubusercontent.com/media/alibaba-edu/"
               "qwen-bailian-usagetraces-anon/main/qwen_traceA_blksz_16.jsonl",
        "file": "qwen_traceA_part.jsonl",
        "bytes": 4_000_000,
        "note": "the first 4 MB of a 56 MB LFS object, about 4,000 rows out of two hours",
    },
    "kimi-conversation": {
        "url": "https://raw.githubusercontent.com/kvcache-ai/Mooncake/main/FAST25-release/"
               "traces/conversation_trace.jsonl",
        "file": "mooncake_conversation.jsonl",
        "bytes": None,
        "note": "the full 3 MB file, 12,031 requests sampled from one hour",
    },
    "burstgpt": {
        "url": "https://raw.githubusercontent.com/HPMLL/BurstGPT/main/data/BurstGPT_1.csv",
        "file": "burstgpt_1_part.csv",
        "bytes": 4_000_000,
        "note": "the first 4 MB of a 50 MB file, 114,000 rows up to about 9.8 days",
    },
}


def fetch(name: str, cache: Path) -> Path:
    source = SOURCES[name]
    target = cache / source["file"]
    request = urllib.request.Request(source["url"])
    if source["bytes"] is not None:
        request.add_header("Range", "bytes=0-%d" % (source["bytes"] - 1))
    with urllib.request.urlopen(request, timeout=120) as response:
        body = response.read()
        status = response.status
    target.write_bytes(body)
    digest = hashlib.sha256(body).hexdigest()
    print("%-18s %9d bytes  http %d  sha256 %s" % (name, len(body), status, digest))
    print("%-18s %s" % ("", source["note"]))
    return target


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--cache", default="target/traces-cache",
                        help="directory that receives the upstream files (default: target/traces-cache)")
    parser.add_argument("names", nargs="*", choices=[[]] + list(SOURCES),
                        help="sources to fetch; all of them when omitted")
    args = parser.parse_args()
    cache = Path(args.cache)
    cache.mkdir(parents=True, exist_ok=True)
    for name in args.names or list(SOURCES):
        try:
            fetch(name, cache)
        except urllib.error.HTTPError as error:
            print("%-18s FAILED http %d %s" % (name, error.code, error.reason), file=sys.stderr)
            return 1
        except urllib.error.URLError as error:
            print("%-18s FAILED %s" % (name, error.reason), file=sys.stderr)
            return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
