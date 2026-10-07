# Contributing to Fude

> **Pull requests from outside the maintainer team are closed on arrival**
> by an automated guard — this is deliberate, not a misconfiguration. The
> way to contribute is to open an issue (bug or feature template) with a
> reproduction; code changes happen by maintainer invitation. Everything
> below applies once you have been invited, or if you are a maintainer.

## Building and testing

```bash
./gradlew build              # compile, test, and run every gate
./gradlew :fude-demo:run     # the desktop demo host
```

`./gradlew build` is the whole definition of green: unit tests, the
architecture check, binary-compatibility (`apiCheck`), and detekt. Keep it
green on every commit — CI runs the same command on JDK 17.

## Toolchain

Kotlin 2.3.21 · Compose Multiplatform 1.12.1 · Gradle 9.8.0 · JDK 17+.

Detekt 1.23.8 cannot start on Java 23 or newer. Run the build on JDK 17
through 22 if you want the static-analysis gate to actually execute; on a
newer JVM the build says so loudly and skips it. CI uses JDK 17, so the gate
is always enforced there.

## Module boundaries (enforced, not conventional)

- `:fude-core` is pure Kotlin: no Compose, no `java.*`. The build fails
  otherwise (`checkArchitecture`).
- `commonMain` anywhere: no `java.*`/`javax.*` imports — it compiles for
  every target.
- The library knows nothing about any host: no document IDs, no vaults, no
  network. Host dialects arrive through `SyntaxExtension`; see
  [`docs/extending.md`](docs/extending.md).

If your change needs something from a forbidden place, that is a design
discussion, not an import — open an issue first.

## Changing public API

`:fude-core` and `:fude` are published libraries; their API is covered by
`apiCheck` against checked-in `api/*.api` dumps. If your change alters a
public declaration:

1. Run `./gradlew apiDump` to regenerate the dumps.
2. Commit the dump changes alongside the code.
3. Say why the API change is needed in the PR description.

`:fude-demo` is not published and is exempt.

## Tests

- Every `@Test` must contain at least one assertion. A test that cannot fail
  inflates the count without verifying anything.
- New parser behaviour needs a case in `ConformanceCorpusTest`
  (`docs/conformance.md` is the written stance; the corpus pins it).
- New decoration behaviour needs a golden in `DecorationGoldenTest`.
- The Tanseki functional suite (`TansekiFunctionalTest`) needs a running
  daemon and skips without one — see "Testing" in the README for the seed
  script. If you can run it, do; if you cannot, say so in the PR.

## Style

- Detekt config is `detekt.yml`, chosen rather than defaulted. No new
  findings: either fix them or argue in the PR why the baseline should grow.
- No bare `TODO:`/`FIXME:` comments — an untracked task with no owner is a
  wish. File an issue and reference it instead.
- KDoc states contracts and invariants. Defect history belongs in
  `docs/` or in the test name, not in a comment the next fix orphans —
  stale figures in comments have misled before.

## Pull requests

- One concern per PR; keep the diff reviewable.
- Describe what changed, why, and how it was verified
  (there is a PR template).
- Large or structural changes (parser, sync pipeline, build): open an issue
  first so the approach is agreed before the code exists.
