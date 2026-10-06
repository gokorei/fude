#!/usr/bin/env python3
"""Seed a live Tanseki daemon with the Markdown that Fude's functional suite reads.

Every other suite in this repository runs against fixtures checked into the repo.
``TansekiFunctionalTest`` deliberately does not: it reads Markdown back out of a
running knowledge store over HTTP, because the failures worth catching are the
ones a fixture author never writes by hand.

That makes the seed script load-bearing. If it seeds the wrong bytes, the suite
fails somewhere deep in the parser and the real cause is a hundred miles away.
So this script does not stop at "the write returned 200" — it reads every
document back through ``documents:get``, the exact endpoint the test suite uses,
and compares byte for byte. A round-trip that does not hold is reported here,
loudly, and exits non-zero.

Re-running is safe. Documents are upserted, so seeding twice against the same
daemon produces the same state.

Usage
-----
    python3 scripts/seed_tanseki.py                       # http://127.0.0.1:8088
    python3 scripts/seed_tanseki.py --base-url http://host:8088
    TANSEKI_URL=http://host:8088 python3 scripts/seed_tanseki.py

Standard library only, deliberately: the Kotlin suite carries its own minimal
JSON reader so it needs no JSON library, and this script is no different.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.request

DEFAULT_BASE_URL = "http://127.0.0.1:8088"
COLLECTION = "fude-functional-test"

# 1. Every inline kind in one paragraph.
INLINES = """# Inline coverage

A paragraph with **strong**, *emphasis*, `code span`, ~~strikethrough~~,
a [link with a long destination](https://example.com/some/deep/path?query=1&x=2),
an ![image alt text](https://example.com/pic.png), and plain text around them.

Mixed: **bold with `code` inside** and *italic spanning [a link](https://x.dev)*.
"""

# 2. Every block kind, nested. The table is followed by a blank line and a
#    thematic break on purpose — that combination is EE2YS18B, and it is not
#    reproducible from the other blocks.
BLOCKS = """# Block coverage

## Heading level two

### Heading level three

A plain paragraph.

- Unordered item
    - Nested level two
        - Nested level three
- Second item with **bold**

1. Ordered one
2. Ordered two
    1. Ordered nested

> A block quote.
>
> > Nested quote inside.

| Column A | Column B | Column C |
|----------|----------|----------|
| `code`  | **bold** | plain    |
| [link](https://example.com) | 2 | 3 |

---

Final paragraph after the thematic break.
"""

# 3. Code fences must stay opaque, including hostile contents.
FENCES = """# Fence opacity

```kotlin
// Nothing here may be parsed as Markdown.
val heading = "# not a heading"
val link = "[[not a wikilink]]"
val pipe = "| not | a | table |"
val quote = "> not a quote"
val fence = "```"
**not bold**
```

~~~
tilde fence with ``` inside
~~~

Inline `code with # hash | pipe` stays inline.
"""

# 4. Host extension syntax the library has no knowledge of.
HOSTS = """# Host extensions

Mention someone with {{double braces}} and a handle like {{project-alpha}}.

> [!note]
> This callout came from a host extension, not the library.
"""

# 5. Unicode stress: the cases the editing ticket names explicitly.
UNICODE = """# Unicode

Emoji ZWJ family: 👨‍👩‍👧‍👦 · flag: 🇯🇵 · skin tone: 👋🏽 · flags: 🏳️‍🌈
Combining marks: é à ö ñ  (decomposed forms)
CJK: 日本語のテキスト、中文字符、한국어 텍스트
RTL: مرحبا بالعالم — mixed with English for bidi
Double-width: ＡＢＣ１２３
"""

# 6. A realistically long note, to measure against the 24fps trigger.
LONG_SECTIONS = [
    f"""## Section {i}

This is a paragraph in section {i}. It contains **bold text**, *emphasis*,
`inline code`, and a [link](https://example.com/section/{i}) so that the
renderer has real inline work to do rather than plain characters.

- A list item for section {i}
- Another item with **emphasis**
- A third item

```kotlin
fun section{i}() = {i}
```
"""
    for i in range(1, 121)
]
LONG_NOTE = "# A realistically long note\n\n" + "\n".join(LONG_SECTIONS)

# 7. CRLF line endings.
#
# Seeded and read back like everything else, but with one difference the others do
# not have: this document is expected NOT to survive byte-exact. A live knowledge
# store normalises CRLF to LF on the way in, so what comes back is the LF twin.
#
# That is worth pinning down rather than working around, because it tells us where
# the line-ending handling has to live. Fude's CRLF and lone-CR tests are fixture
# tests for exactly this reason: a document loaded from a store that normalises will
# never contain a CR, so only a fixture can prove the parser handles one. What the
# live document proves is the other half — that a store which hands us LF produces
# ordinary blocks, with no offset skew from a CR we never see.
CRLF_NOTE = (
    "# A note authored on Windows\r\n"
    "\r\n"
    "A paragraph with CRLF endings.\r\n"
    "\r\n"
    "- item one\r\n"
    "- item two\r\n"
)

# The LF twin of the above. Both are seeded so the suite can assert that the CRLF
# document's readback and this one are identical, which turns "the store normalised
# it" into a checked fact rather than an assumption.
CRLF_TWIN = CRLF_NOTE.replace("\r\n", "\n")

# Documents whose readback must equal the seeded bytes byte for byte.
DOCUMENTS: dict[str, str] = {
    "inlines": INLINES,
    "blocks": BLOCKS,
    "fences": FENCES,
    "hosts": HOSTS,
    "unicode": UNICODE,
    "long-note": LONG_NOTE,
}

# Documents the store is expected to rewrite. Key is the document id; value is the
# content the readback must equal instead.
NORMALISED_DOCUMENTS: dict[str, str] = {
    "crlf": CRLF_TWIN,
    "crlf-twin": CRLF_TWIN,
}


class SeedError(Exception):
    """A round-trip or transport failure that must stop the run."""


def post(base: str, path: str, body: dict, timeout: int = 30):
    """POSTs JSON and returns the decoded response."""
    request = urllib.request.Request(
        f"{base}{path}",
        # ensure_ascii=False keeps astral-plane emoji on the wire as real
        # characters. Escaping them would risk the daemon storing the escapes
        # as literal text, which is exactly the class of bug the round-trip
        # check below exists to catch.
        data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.status, json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        raise SeedError(
            f"POST {path} -> HTTP {error.code}: {error.read().decode('utf-8', 'replace')[:400]}"
        ) from error
    except urllib.error.URLError as error:
        raise SeedError(f"POST {path} -> {error.reason}") from error


def upsert(base: str, doc_id: str, content: str) -> str:
    status, result = post(
        base,
        "/v1/documents:upsert",
        {
            "id": doc_id,
            "content": content,
            "collection": COLLECTION,
            "path": f"{doc_id}.md",
        },
    )
    if status != 200:
        raise SeedError(f"upsert '{doc_id}' returned HTTP {status}")
    return result.get("revision", "") if isinstance(result, dict) else ""


def fetch(base: str, doc_id: str) -> str:
    """Reads a document back through the endpoint the Kotlin suite itself uses."""
    status, result = post(base, "/v1/documents:get", {"id": doc_id})
    if status != 200:
        raise SeedError(f"get '{doc_id}' returned HTTP {status}")
    if not isinstance(result, dict) or "content" not in result:
        raise SeedError(f"get '{doc_id}' returned no 'content' field: {result!r}")
    return result["content"]


def first_difference(left: str, right: str) -> str:
    """Describes where two strings part company, rather than just that they do."""
    limit = min(len(left), len(right))
    offset = next((i for i in range(limit) if left[i] != right[i]), limit)
    window = slice(max(0, offset - 30), offset + 30)
    return (
        f"first difference at char {offset} (seeded {len(left)} chars, "
        f"read back {len(right)} chars)\n"
        f"        seeded: {left[window]!r}\n"
        f"        readback:{right[window]!r}"
    )


def check_reachable(base: str) -> None:
    health = f"{base}/v1/health"
    try:
        with urllib.request.urlopen(health, timeout=5) as response:
            if not 200 <= response.status < 300:
                raise SeedError(f"{health} returned HTTP {response.status}")
    except urllib.error.HTTPError as error:
        raise SeedError(f"{health} returned HTTP {error.code}") from error
    except urllib.error.URLError as error:
        raise SeedError(
            f"Tanseki is not reachable at {base} ({error.reason}).\n"
            f"       Start a daemon, or pass --base-url. The functional suite skips\n"
            f"       cleanly when no daemon is present, so a green CI run proves\n"
            f"       nothing about real content unless this script has been run."
        ) from error


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Seed a live Tanseki daemon with Fude's functional-test documents."
    )
    parser.add_argument(
        "--base-url",
        default=os.environ.get("TANSEKI_URL", DEFAULT_BASE_URL),
        help=f"Tanseki base URL (default: $TANSEKI_URL, else {DEFAULT_BASE_URL})",
    )
    parser.add_argument(
        "--collection",
        default=COLLECTION,
        help=f"collection to seed into (default: {COLLECTION})",
    )
    args = parser.parse_args(argv)
    base = args.base_url.rstrip("/")

    try:
        check_reachable(base)
    except SeedError as error:
        print(f"error: {error}", file=sys.stderr)
        return 2

    failures = 0
    for doc_id, content in DOCUMENTS.items():
        lines = content.count("\n") + 1
        try:
            revision = upsert(base, doc_id, content)
            readback = fetch(base, doc_id)
        except SeedError as error:
            print(f"  FAIL {doc_id:12} {error}", file=sys.stderr)
            failures += 1
            continue

        # Byte-exact, not "close enough". The suite's grapheme assertions index
        # into these exact bytes, so a silently altered character is a test
        # failure three files away.
        if readback.encode("utf-8") != content.encode("utf-8"):
            print(
                f"  FAIL {doc_id:12} round-trip is not byte-exact\n"
                f"        {first_difference(content, readback)}",
                file=sys.stderr,
            )
            failures += 1
            continue

        print(f"    ok {doc_id:12} lines={lines:5} chars={len(content):6} rev={revision}")

    # Documents the store rewrites. Checked separately because the expectation is
    # inverted: here a byte-exact round-trip would be the failure.
    for doc_id, expected in NORMALISED_DOCUMENTS.items():
        seeded = CRLF_NOTE if doc_id == "crlf" else CRLF_TWIN
        try:
            revision = upsert(base, doc_id, seeded)
            readback = fetch(base, doc_id)
        except SeedError as error:
            print(f"  FAIL {doc_id:12} {error}", file=sys.stderr)
            failures += 1
            continue

        if readback.encode("utf-8") != expected.encode("utf-8"):
            print(
                f"  FAIL {doc_id:12} readback is neither the seeded bytes nor the LF twin\n"
                f"        {first_difference(expected, readback)}",
                file=sys.stderr,
            )
            failures += 1
            continue

        cr_count = seeded.count("\r")
        kept = readback.count("\r")
        note = "" if kept == 0 else f" (store kept {kept} CR)"
        print(
            f"    ok {doc_id:12} lines={readback.count(chr(10)) + 1:5} "
            f"chars={len(readback):6} rev={revision}  normalised {cr_count} CR{note}"
        )

    total = len(DOCUMENTS) + len(NORMALISED_DOCUMENTS)
    if failures:
        print(
            f"\n{failures} of {total} documents failed. "
            f"Refusing to leave a half-seeded daemon behind a green run.",
            file=sys.stderr,
        )
        return 1

    print(f"\nSeeded {total} documents into '{args.collection}' at {base}.")
    print("Now: ./gradlew :fude:jvmTest --tests \"dev.fude.functional.TansekiFunctionalTest\"")
    return 0


if __name__ == "__main__":
    sys.exit(main())