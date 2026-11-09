# CLAUDE.md

@AGENTS.md

## Claude Code specifics

- `AGENTS.md` (imported above) is the single source of truth for project rules. Put shared guidance there, not here; keep this file for Claude-only notes.
- Read the relevant section of `application-plan.md` before starting a task (e.g. §5.1 for `DimensionCalculator`). Don't rely on memory of the spec.
- At the start of a session, read `PROGRESS.md` and `DECISIONS.md` (once they exist) to find the current milestone.
- The shell is PowerShell 5.1 on Windows: chain with `;` / `if ($?) { ... }`, not `&&`. Run Gradle as `.\gradlew.bat ...`.
  Long Gradle runs (instrumented tests, release builds) can go in the background.
- Before saying a task is done, run the milestone gate (`.\gradlew.bat lint test`, plus instrumented tests from M2 on)
  and report the real result. If an emulator isn't available, say so instead of claiming the tests pass.
- The repo is not under git yet. Don't run `git init` or commit unless asked.
- Ask the user before: adding any dependency outside spec §2, changing the package/applicationId, or deviating from a hard rule.
