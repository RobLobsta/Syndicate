# PROG-033: build, guardrails, and the verification harness

**Date:** 2026-08-14
**Category:** progress
**Related Docs:** docs/02_technical_architecture.md#D02-S4.5, docs/12_testing_validation_ci.md#D12-S5.4, docs/14_test_environment.md#D14-S5.13

**Status:** active

## Summary
Eight Gradle modules build from a clean clone with only a JDK. Layering, headless safety, package
roots, cosmetic isolation, the version catalogue, blueprint cross-references and memory-entry format
are all enforced by check tasks rather than by convention. `./gradlew check validateDocs` is green,
including `game-client`.

## Details

**Scope:** `build-logic/`, `.github/workflows/`, `memory-system/`, `test-environment/`.

**Status of Work:**

| Area | State | Notes |
|---|---|---|
| Eight modules, dependency rules enforced | done | `LayeringCheckTask`, `ModuleRules` |
| Headless safety and cosmetic isolation | done | G17 and G6 as check tasks (DEC-006) |
| Version catalogue as the single version source | done | No inline versions anywhere |
| `validateDocs` and `lintMemory` | done | Blueprint citations and entry format |
| Verification harness | done | Re-checks tool output inside real Bullet; asset, physics, vehicle scenes |
| CI: one run per commit, stages as steps | done | DEC-010; stages that cannot pass stay out |
| Packaging: `distZip` | done | Portable, all-platform natives, `assets/` included |
| Packaging: `packageWindows` (jpackage) | done | Host-only; run it on Windows |
| Performance budgets and gates | not_started | D12-S5.6 budgets are unmeasured |
| CI capture of the client's screens | not_started | Possible now (DISC-046) and not yet wired |

## Rationale / Context
The guardrails are the reason a session can move fast without re-reading D02: a layering mistake is a
build failure rather than a review finding. Knowing they exist is what stops a session hand-checking
things the build already checks.

## Impact
- A change that a check task rejects is a spec violation, not a build quirk — read the cited section.
- Client screens can now be photographed in CI, which would make a visual regression a failing build.
