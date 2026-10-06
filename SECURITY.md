# Security policy

Fude is a client-side Markdown editor library: it holds no credentials,
performs no network calls, and persists nothing. Its attack surface is
malformed input reaching the parser and spans reaching the renderer, both of
which are treated as untrusted by design (see `addStyle` clipping and the
range invariants in `BlockRangePropertyTest`).

## Supported versions

| Version | Supported |
|---|---|
| 0.1.x (pre-release snapshots) | Best effort — fixes land on `main` |

There has been no stable release yet. If you depend on a snapshot, pin the
exact version; APIs may change before 1.0 (changes are gated by `apiCheck`,
so they are always visible in the diff).

## Reporting a vulnerability

Do **not** open a public issue for a suspected vulnerability. Report it
through a [GitHub Security
Advisory](https://docs.github.com/en/code-security/security-advisories)
on this repository, or contact the maintainers privately via the contact
listed on the repository profile.

Include:

- What the input or interaction is
- What you expected to happen and what happened instead
- The Fude version (or commit) and platform you observed it on

You will receive an acknowledgement, and we will keep you informed as a fix
is developed. Once fixed, the advisory is published alongside the patch and
credited to the reporter unless anonymity is preferred.
