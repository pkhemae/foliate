# Contributing

Thank you for helping. This page is the short version; the [wiki](https://github.com/pkhemae/foliate/wiki) explains how the code works.

## Before you start

- For anything beyond a small fix, open an issue first so the approach can be agreed on.
- Read [Folia threading](https://github.com/pkhemae/foliate/wiki/Folia-threading). Most mistakes in a Folia plugin are threading mistakes, and neither Paper nor the unit tests can show them.

## Workflow

1. Branch off `main`: `feat/<issue>-<slug>`, `fix/...`, `chore/...` or `docs/...`.
2. Make small commits that follow [Conventional Commits](https://www.conventionalcommits.org/), in English.
3. Check your change:

   ```bash
   ./gradlew build
   ```

   ```bash
   ./gradlew runFolia -PselfTest=true
   ```

   The second command boots a real Folia server and needs the Minecraft EULA accepted in `run/eula.txt`.
4. Open a pull request that says what changed, how you verified it, and what you could not verify. Link the issue with `Closes #<number>`.

## What a change needs

- Tests for the logic it adds. Code that schedules is tested with `ManualSchedulers`, see [Testing](https://github.com/pkhemae/foliate/wiki/Testing).
- No `BukkitScheduler`, no blocking call on a tick thread, no entity or block touched outside the region that owns it.
- Shared state that is safe to read from several threads.
- An entry under "Unreleased" in `CHANGELOG.md` when the change is visible to users of the template.
- An update to the wiki page it affects, mentioned in the pull request.
