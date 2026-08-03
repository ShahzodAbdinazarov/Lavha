#!/usr/bin/env python3
"""Upload the Play AAB to Google Play, end to end, with the artifact checked first.

    scripts/publish-play.py [--track internal|alpha|beta|production] [--aab PATH]
                            [--rollout 0.1] [--changelog "..."] [--dry-run]

Why this exists: every other step of a Svipe release is a script, and the Play upload was the one
manual drag-and-drop left. A manual step is where the wrong flavor gets shipped — and shipping
`bundleAfatRelease` instead of `bundleBundleAfatRelease` puts READ_CALL_LOG in front of a reviewer
and gets the release rejected. So this refuses to upload anything that has not passed
scripts/verify-play-aab.sh.

Credentials: a Google Play Developer API service account JSON, found in this order —
  1. $PLAY_SERVICE_ACCOUNT_JSON            (path)
  2. ~/.config/svipe/play-service-account.json
The account needs "Release to production, exclude devices, and use app signing" on uz.svipe.app.
Create it in Google Cloud (Play Android Developer API enabled), then invite it in Play Console ->
Users and permissions. Nothing else about the release needs a browser after that.

Install once:  python3 -m pip install --user google-api-python-client google-auth
"""
from __future__ import annotations

import argparse
import os
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
DEFAULT_AAB = REPO / "TMessagesProj_App/build/outputs/bundle/bundleAfatRelease/TMessagesProj_App-bundleAfat-release.aab"
PACKAGE = "uz.svipe.app"
CRED_FALLBACK = Path.home() / ".config/svipe/play-service-account.json"
SCOPE = "https://www.googleapis.com/auth/androidpublisher"


def die(msg: str) -> None:
    print(f"FAIL: {msg}", file=sys.stderr)
    sys.exit(1)


def find_credentials() -> Path:
    env = os.environ.get("PLAY_SERVICE_ACCOUNT_JSON")
    if env:
        p = Path(env).expanduser()
        if not p.is_file():
            die(f"PLAY_SERVICE_ACCOUNT_JSON points at {p}, which does not exist")
        return p
    if CRED_FALLBACK.is_file():
        return CRED_FALLBACK
    die(
        "no Play service account. Put the JSON at "
        f"{CRED_FALLBACK} or set PLAY_SERVICE_ACCOUNT_JSON.\n"
        "      Google Cloud -> service account -> enable Play Android Developer API -> key (JSON),\n"
        "      then Play Console -> Users and permissions -> invite that service account on "
        f"{PACKAGE}."
    )
    raise AssertionError("unreachable")


def verify_artifact(aab: Path) -> None:
    """The artifact is the proof — never the source. See CLAUDE.md rule 3."""
    script = REPO / "scripts/verify-play-aab.sh"
    print(f"== verifying {aab.name} ==")
    r = subprocess.run([str(script), str(aab)])
    if r.returncode != 0:
        die("the AAB failed verification — refusing to upload it to Play")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--track", default="internal",
                    choices=["internal", "alpha", "beta", "production"])
    ap.add_argument("--aab", type=Path, default=DEFAULT_AAB)
    ap.add_argument("--rollout", type=float, default=None,
                    help="staged rollout fraction, e.g. 0.1 — omit for a full release")
    ap.add_argument("--changelog", default=None, help="release notes (en-US)")
    ap.add_argument("--dry-run", action="store_true",
                    help="verify and authenticate, then stop before creating the edit")
    args = ap.parse_args()

    if not args.aab.is_file():
        die(f"no AAB at {args.aab}\n"
            f"      build it with: ./gradlew :TMessagesProj_App:bundleBundleAfatRelease\n"
            f"      (NOT bundleAfatRelease — that variant carries READ_CALL_LOG and Play rejects it)")

    verify_artifact(args.aab)
    cred_path = find_credentials()

    try:
        from google.oauth2 import service_account            # type: ignore
        from googleapiclient.discovery import build          # type: ignore
        from googleapiclient.http import MediaFileUpload     # type: ignore
    except ImportError:
        die("missing deps. Run: python3 -m pip install --user google-api-python-client google-auth")

    creds = service_account.Credentials.from_service_account_file(
        str(cred_path), scopes=[SCOPE])
    service = build("androidpublisher", "v3", credentials=creds, cache_discovery=False)
    edits = service.edits()
    print(f"== authenticated as {creds.service_account_email} ==")

    if args.dry_run:
        print("dry run — artifact verified and credentials work; nothing uploaded")
        return

    edit_id = edits.insert(body={}, packageName=PACKAGE).execute()["id"]
    try:
        print(f"== uploading {args.aab.stat().st_size / 1e6:.0f} MB ==")
        media = MediaFileUpload(str(args.aab), mimetype="application/octet-stream",
                                resumable=True)
        bundle = edits.bundles().upload(
            packageName=PACKAGE, editId=edit_id, media_body=media).execute()
        version_code = bundle["versionCode"]
        print(f"   uploaded versionCode {version_code}")

        release: dict = {"versionCodes": [version_code], "status": "completed"}
        if args.rollout is not None:
            release["status"] = "inProgress"
            release["userFraction"] = args.rollout
        if args.changelog:
            release["releaseNotes"] = [{"language": "en-US", "text": args.changelog}]

        edits.tracks().update(packageName=PACKAGE, editId=edit_id, track=args.track,
                              body={"track": args.track, "releases": [release]}).execute()
        edits.commit(packageName=PACKAGE, editId=edit_id).execute()
    except Exception:
        # An abandoned edit blocks the next one with "an edit is already in progress".
        try:
            edits.delete(packageName=PACKAGE, editId=edit_id).execute()
            print("rolled back the draft edit", file=sys.stderr)
        except Exception:
            pass
        raise

    where = f"{args.track} at {args.rollout:.0%}" if args.rollout is not None else args.track
    print(f"PUBLISHED versionCode {version_code} to {where}")


if __name__ == "__main__":
    main()
