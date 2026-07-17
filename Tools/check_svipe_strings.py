#!/usr/bin/env python3
"""Guard: every string Svipe adds must follow the user's Telegram language.

Two ways that breaks, both of which have happened:
  1. A key is added to values/strings.xml but not to values-uz / values-ru, so an Uzbek or Russian
     user reads it in English (or, when the base was filled in Uzbek, everyone else read Uzbek).
  2. A user-facing literal is written straight into Java, so no language can ever translate it.

Run standalone, or let the Gradle `checkSvipeStrings` task run it as part of the build.

Scope is Svipe's own strings only: the keys this fork adds on top of upstream (computed from git, so
it needs no hand-kept list) and the Java files this fork adds. Upstream Telegram keys are localized
by Telegram's cloud langpack and are none of our business.
"""
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "TMessagesProj/src/main/res"
BASE = RES / "values/strings.xml"
# The languages Svipe ships. Telegram's own keys cover far more, but those come from the cloud
# langpack; ours are app resources, so these are the folders that must carry them.
LOCALES = ["values-uz", "values-ru"]
UPSTREAM = "origin/master"

# Keys that are deliberately identical in every language (brand names, wordmarks).
BRAND = {"AppName", "AppNameBeta", "SvipeIntroTitle", "MainTabsReels"}

# Plural keys resolve per-language: LocaleController appends _one/_few/_many/_other and falls back to
# _other, so a locale only has to carry the forms its plural rules can produce.
PLURAL_SUFFIX = re.compile(r"_(zero|one|two|few|many|other)$")


def git(*args):
    return subprocess.run(["git", "-C", str(ROOT), *args], capture_output=True, text=True).stdout


def keys_of(path):
    if not path.exists():
        return {}
    return {e.get("name"): "".join(e.itertext()) for e in ET.parse(path).getroot().findall("string")}


def svipe_keys():
    """Keys this fork adds on top of upstream, read from the diff rather than a hand-kept list.

    Diffed against the working tree, not HEAD, so a key added in the edit you are about to build is
    checked now rather than one commit too late.
    """
    diff = git("diff", UPSTREAM, "--", str(BASE.relative_to(ROOT)))
    if not diff:
        return set()
    added = set()
    for line in diff.splitlines():
        if line.startswith("+") and not line.startswith("+++"):
            m = re.search(r'<string name="([^"]+)"', line)
            if m:
                added.add(m.group(1))
    return added


def our_java():
    files = git("log", "--diff-filter=A", "--format=", "--name-only", f"{UPSTREAM}..HEAD").split()
    return sorted({f for f in files if f.endswith(".java") and (ROOT / f).exists()})


# A literal is user-facing prose if a UI sink takes it, or if it is Cyrillic, or if it carries the
# apostrophes Uzbek prose is full of. Deliberately narrow: this must not cry wolf, or it gets ignored.
SINK = re.compile(
    r"\b(setText|setMessage|setTitle|setSubtitle|showMessage|setPositiveButton|setNegativeButton|"
    r"setNeutralButton|createSimpleBulletin|makeText|setProfileTitle|setProfileSubtitle)\s*\("
)
LITERAL = re.compile(r'"((?:[^"\\]|\\.)*)"')
CYRILLIC = re.compile(r"[Ѐ-ӿ]")
UZBEK_APOSTROPHE = re.compile(r"[a-z]\\'[a-z]", re.I)
# Punctuation, glyphs and format specifiers carry no language and need no translation.
ALLOWED = re.compile(r"^(%[\d.]*[sdf]|\s|[.,:()\[\]/·—–›‹«»✓@#-])*$")


def hardcoded():
    bad = []
    for rel in our_java():
        for n, line in enumerate((ROOT / rel).read_text(encoding="utf-8", errors="ignore").splitlines(), 1):
            s = line.strip()
            if s.startswith(("//", "*", "/*")):
                continue
            if "R.string." in line or "formatPluralString" in line:
                continue
            for m in LITERAL.finditer(line):
                lit = m.group(1)
                if not lit.strip() or ALLOWED.match(lit):
                    continue
                if CYRILLIC.search(lit) or UZBEK_APOSTROPHE.search(lit) or (SINK.search(line) and " " in lit):
                    bad.append(f"{rel}:{n}: hardcoded user-facing text {lit!r}")
    return bad


def main():
    problems = []
    ours = svipe_keys()
    if not ours:
        print(f"check_svipe_strings: no Svipe keys found vs {UPSTREAM} — nothing to check")
        return 0

    base = keys_of(BASE)
    for loc in LOCALES:
        have = keys_of(RES / loc / "strings.xml")
        for key in sorted(ours):
            if key in BRAND or key not in base:
                continue
            if PLURAL_SUFFIX.search(key):
                # Only _other is mandatory; the rest are per-language plural forms.
                if not key.endswith("_other"):
                    continue
                if not any(k for k in have if PLURAL_SUFFIX.sub("", k) == PLURAL_SUFFIX.sub("", key)):
                    problems.append(f"{loc}: no plural forms for {PLURAL_SUFFIX.sub('', key)}")
                continue
            if key not in have:
                problems.append(f"{loc}: missing {key} (a {loc[7:]} user would read it in English)")

    problems += hardcoded()

    if problems:
        print("Svipe strings must follow the user's Telegram language. Problems:\n", file=sys.stderr)
        for p in problems:
            print(f"  {p}", file=sys.stderr)
        print(
            f"\nAdd the key to {'/'.join(LOCALES)}, or route the literal through "
            "LocaleController.getString(R.string.…).",
            file=sys.stderr,
        )
        return 1

    print(f"check_svipe_strings: {len(ours)} Svipe keys, all present in {', '.join(LOCALES)}; "
          "no hardcoded user-facing text")
    return 0


if __name__ == "__main__":
    sys.exit(main())
