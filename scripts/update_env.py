#!/usr/bin/env python3
"""Rewrite the LAVHA_APK_* keys in prod's .env.

Runs on the server and reads the changelog from a file rather than an argument:
the text contains apostrophes, and threading those through an ssh command line
truncated the shell command mid-string on the first attempt.
"""
import re
import sys

env_path, changelog_path, vn, vc, size, sha = sys.argv[1:7]
changelog = open(changelog_path, encoding="ascii").read().strip()

values = {
    "LAVHA_APK_VERSION_NAME": vn,
    "LAVHA_APK_VERSION_CODE": vc,
    "LAVHA_APK_SIZE": size,
    "LAVHA_APK_SHA256": sha,
    "LAVHA_APK_CHANGELOG": changelog,
}

lines = open(env_path, encoding="utf-8").read().splitlines()
seen = set()
out = []
for line in lines:
    m = re.match(r"^([A-Za-z0-9_]+)=", line)
    key = m.group(1) if m else None
    if key in values:
        out.append(f"{key}={values[key]}")
        seen.add(key)
    else:
        out.append(line)

missing = [k for k in values if k not in seen]
if missing:
    raise SystemExit(f"keys absent from .env, refusing to guess placement: {missing}")

open(env_path, "w", encoding="utf-8", newline="\n").write("\n".join(out) + "\n")
print("\n".join(l for l in out if l.startswith("LAVHA_APK")))
