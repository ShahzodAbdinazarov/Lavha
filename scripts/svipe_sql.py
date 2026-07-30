#!/usr/bin/env python3
"""Run SQL against the Svipe prod/dev Postgres over SSH (no local psql, no sshpass needed).

    python3 scripts/svipe_sql.py prod "SELECT count(*) FROM videos;"
    python3 scripts/svipe_sql.py dev  -f query.sql

NO CREDENTIALS LIVE IN THIS FILE — this repo is public (the Maps-API-key leak taught us that).
Host/password are read at run time from, in order:

  1. env: SVIPE_PROD_HOST / SVIPE_PROD_PASSWORD (or SVIPE_DEV_*)
  2. docs/handoff/SVIPE_HANDOFF.md — git-ignored (/docs/handoff/ in .gitignore), the single place
     live server credentials are kept. Parsed out of its "Tez havolalar" SSH quick-reference block,
     which tags each host/password with PROD or DEV on the same line.

Dev prefers the ~/.ssh/lavha_deploy key and only falls back to the password; prod has no key on this
Mac, so it authenticates with the root password over paramiko.

Output is psql -tA (tab-separated, no headers) so it pipes cleanly.
"""

import argparse
import os
import re
import shlex
import sys

import paramiko

HANDOFF = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "docs", "handoff", "SVIPE_HANDOFF.md",
)

TARGETS = {
    "prod": {
        "key": None,
        "container": "svipe-prod-db-1",
        "user": "svipe",
        "db": "svipe",
        "handoff_hint": "PROD",   # how the handoff doc tags this environment
    },
    "dev": {
        "key": "~/.ssh/lavha_deploy",
        "container": "lavha-dev-db-1",
        "user": "lavha",
        "db": "lavha",
        "handoff_hint": "DEV",
    },
}

def _from_handoff(hint):
    """(host, password) for a target, scraped from the git-ignored handoff doc.

    Two shapes in that doc's SSH quick-reference block, both tagging the environment inline:
        ssh root@<ip>   # PROD (svipe.uz)
        # --- SSH ---  (PROD: parol <secret> | DEV: ... parol <secret>)
    """
    try:
        with open(HANDOFF, encoding="utf-8") as fh:
            text = fh.read()
    except OSError:
        return None, None
    host = re.search(
        r"root@(\d+\.\d+\.\d+\.\d+)[^\n]*?#[^\n]*?" + re.escape(hint), text
    )
    secret = re.search(
        re.escape(hint) + r":[^|)\n]*?paro[l'`]?\s+`?([A-Za-z0-9]{6,})", text, re.IGNORECASE
    )
    return (host.group(1) if host else None), (secret.group(1) if secret else None)


def _credentials(target):
    t = TARGETS[target]
    env = target.upper()
    host = os.environ.get(f"SVIPE_{env}_HOST")
    password = os.environ.get(f"SVIPE_{env}_PASSWORD")
    if not host or not password:
        h, p = _from_handoff(t["handoff_hint"])
        host = host or h
        password = password or p
    if not host:
        raise SystemExit(
            f"no host for {target}: set SVIPE_{env}_HOST/SVIPE_{env}_PASSWORD, "
            f"or keep {HANDOFF} in place"
        )
    return host, password


def run(target, sql, timeout=180):
    t = TARGETS[target]
    host, password = _credentials(target)
    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    kwargs = {"username": "root", "timeout": 30, "banner_timeout": 30, "look_for_keys": False}
    if t["key"]:
        keyfile = os.path.expanduser(t["key"])
        if os.path.exists(keyfile):
            kwargs["key_filename"] = keyfile
    if password:
        kwargs["password"] = password
    client.connect(host, **kwargs)
    inner = "psql -U %s -d %s -tA -c %s" % (t["user"], t["db"], shlex.quote(sql))
    cmd = "docker exec -i %s sh -c %s" % (t["container"], shlex.quote(inner))
    _, stdout, stderr = client.exec_command(cmd, timeout=timeout)
    out = stdout.read().decode("utf-8", "replace")
    err = stderr.read().decode("utf-8", "replace")
    code = stdout.channel.recv_exit_status()
    client.close()
    return code, out, err


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("target", choices=sorted(TARGETS))
    ap.add_argument("sql", nargs="?")
    ap.add_argument("-f", "--file")
    args = ap.parse_args()
    sql = args.sql
    if args.file:
        with open(args.file) as fh:
            sql = fh.read()
    if not sql:
        ap.error("give SQL inline or with -f")
    code, out, err = run(args.target, sql)
    sys.stdout.write(out)
    if err.strip():
        sys.stderr.write(err)
    sys.exit(code)


if __name__ == "__main__":
    main()
