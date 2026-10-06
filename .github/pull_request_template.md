<!-- One concern per PR; see CONTRIBUTING.md. -->

## What changed

## Why

## Verification

- [ ] `./gradlew build` is green (tests, architecture check, apiCheck, detekt)
- [ ] New parser behaviour has a `ConformanceCorpusTest` case
- [ ] New decoration behaviour has a `DecorationGoldenTest` golden
- [ ] Public API changes include regenerated `api/*.api` dumps and a rationale below
- [ ] Tanseki functional suite run against a seeded daemon, or stated as not run

## API changes (if any)

Why the published API had to change:
