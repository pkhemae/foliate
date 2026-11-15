# Foliate

Boilerplate for Folia plugins. The code is the source of truth; this file only holds what the code
cannot tell you.

## Development workflow

1. Work starts from a GitHub issue.
2. Branch off `main`: `feat/<issue>-<slug>`, `fix/...`, `chore/...`, `docs/...`. Never commit to `main`.
3. Small Conventional Commits, in English.
4. Verify behaviour on Folia, not only with `./gradlew build`: Paper and MockBukkit run everything
   on one thread and cannot detect region violations. `./gradlew runFolia` starts a server to try
   things by hand; `./gradlew runFolia -PselfTest` runs the automated smoke test.
5. Push and open a PR containing `Closes #<issue>`. CI must be green.
6. Review the PR with fresh eyes, post the findings on the PR, fix them.
7. Squash-merge and delete the branch.

## Architecture conventions

- **Folia first.** There is no main thread. Never use `BukkitScheduler`, never block a tick thread,
  and only touch an entity or block from the region that owns it.
- **Shared state is thread-safe.** Events and commands run in parallel on region threads: use
  immutable snapshots or concurrent collections, never plain mutable fields.
- `FoliatePlugin` is the composition root. Dependencies are passed through constructors: no static
  singletons, no DI container.
- `onDisable` does no world work. Region tickers are already stopped when it runs.
- Third-party plugins are optional hooks (`softdepend` + `compileOnly`), isolated so their classes
  are only loaded when the plugin is installed.
- Prefer the native Paper API (Adventure/MiniMessage, Brigadier, Data Components) over a library.
  Bundled libraries are shaded and relocated.
- Dependency versions live in `gradle/libs.versions.toml`. The Minecraft target is changed there
  only.
- Every package has a `package-info.java` annotated with `@NullMarked`.
