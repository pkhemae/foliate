# Contributing

Thank you for helping. This page is the short version; the [wiki](https://github.com/pkhemae/foliate/wiki) explains how the code works.

## Before you start

- For anything beyond a small fix, open an issue first so the approach can be agreed on.
- Read [Scheduling](https://github.com/pkhemae/foliate/wiki/Scheduling), and Folia's [overview](https://docs.papermc.io/folia/reference/overview/) if Folia is new to you. Threading mistakes are the usual ones, and neither Paper nor the unit tests show them.

## Workflow

1. Branch off `main`: `feat/<issue>-<slug>`, `fix/...`, `chore/...` or `docs/...`.
2. Make small commits that follow [Conventional Commits](https://www.conventionalcommits.org/), in English.
3. Check your change:

   ```bash
   ./gradlew build
   ```

   ```bash
   ./gradlew runFolia -PserverTest=true
   ```

   The second command boots a real Folia server. The first time, it stops and asks you to accept
   the Minecraft EULA in `run/eula.txt`; run it again after that.
4. Open a pull request that says what changed, how you verified it, and what you could not verify. Link the issue with `Closes #<number>`.

## What a change needs

- Tests for the logic it adds. Code that schedules is tested with `ManualSchedulers`, see [Testing](https://github.com/pkhemae/foliate/wiki/Testing).
- No `BukkitScheduler`, no blocking call on a tick thread, no entity or block touched outside the region that owns it.
- Shared state that is safe to read from several threads.
- An entry under "Unreleased" in `CHANGELOG.md` when the change is visible to users.
- An update to the documentation it affects (the developer guide, which for the template is its wiki), mentioned in the pull request.
