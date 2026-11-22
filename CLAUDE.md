# Foliate

Boilerplate for Folia plugins. The code is the source of truth; this file only holds what the code
cannot tell you. The developer guide is the GitHub wiki (a separate git repository,
`foliate.wiki.git`): update the page a change affects.

## Development workflow

1. Work starts from a GitHub issue.
2. Branch off `main`: `feat/<issue>-<slug>`, `fix/...`, `chore/...`, `docs/...`. Never commit to `main`.
3. Small Conventional Commits, in English.
4. Verify behaviour on Folia, not only with `./gradlew build`: Paper and MockBukkit run everything
   on one thread and cannot detect region violations. `./gradlew runFolia` starts a server to try
   things by hand; `./gradlew runFolia -PserverTest=true` runs the automated smoke test. What needs
   a connected player is checked by a CI job that joins with a headless client (the script is in
   `.github/scripts/`); HeadlessMC only allows an offline account in CI, so it is not run locally.
5. Push and open a PR containing `Closes #<issue>`. CI must be green.
6. Review the PR with fresh eyes, post the findings on the PR, fix them.
7. Squash-merge and delete the branch. With stacked PRs, retarget the next PR to `main` before
   deleting the merged branch from the command line: GitHub then closes a PR left without a base.

## Architecture conventions

- **Folia first.** There is no main thread. Never use `BukkitScheduler`, never block a tick thread,
  and only touch an entity or block from the region that owns it.
- **Shared state is thread-safe.** Events and commands run in parallel on region threads: use
  immutable snapshots or concurrent collections, never plain mutable fields.
- `core` is reusable infrastructure and knows nothing of the plugin's features. A module owns its
  settings class and receives a supplier of it (`() -> config.get().scoreboard()`), so a reload takes
  effect; `PluginConfig` only composes the sections into the single `config.yml`.
- `contracts` holds what features offer each other (none in the template): interfaces and value types only,
  implementations stay in their module or hook. Modules, hooks and commands depend on `core` and
  `contracts`, never on each other; `ArchitectureTest` enforces it.
- A feature's subcommands live in its `command` package and its module offers them
  (`SubcommandProvider`); `commands` only holds those about the whole plugin. A permission node is a `PERMISSION` constant
  next to the subcommand that checks it, and is declared in `build.gradle.kts`.
- `FoliatePlugin` is the composition root. Dependencies are passed through constructors: no static
  singletons, no DI container.
- `onDisable` does no world work and schedules nothing: nothing runs for a disabled plugin
  (`FoliaSchedulers` skips the region and global tasks Folia would still run). Regions have stopped
  ticking only when Folia stops: when the plugin alone is disabled (a plugin manager, Paper's
  `/reload`, a failed start), a task or event already under way may still be running, so shared
  state stays thread-safe.
- Third-party plugins are optional hooks (`softdepend` + `compileOnly`), isolated so their classes
  are only loaded when the plugin is installed.
- Prefer the native Paper API (Adventure/MiniMessage, Brigadier, Data Components) over a library.
  Bundled libraries are shaded and relocated.
- Dependency versions live in `gradle/libs.versions.toml`. The Minecraft target is changed there
  only.
- Files the plugin owns are read with `core.yaml.Yaml` or ConfigLib (YAML 1.2), never Bukkit's
  `YamlConfiguration` (YAML 1.1 would change the type of values such as `no`).
- Code that schedules depends on the `Schedulers` interface. Anything using timers or cancellation
  is tested with `ManualSchedulers`: MockBukkit cannot cancel tasks.
- Every package that holds classes has a `package-info.java` annotated with `@NullMarked`.
  NullAway checks those annotations when compiling the plugin and the server test, and Error Prone
  runs on every source set; with `-Werror`, any of their warnings fails the build.

## Laying out a feature

A feature is a module (`modules/<feature>`) or a hook (`hooks/<plugin>`). Every feature reads the
same way, whatever its size:

```
modules/fly/
├── FlyModule.java      entry point: builds the feature, follows enable/reload/disable, offers its
│                       subcommands, lists its MIGRATIONS. Wiring only, no logic
├── FlySettings.java    its section of config.yml, when it has one
├── FlySessions.java    the domain: what the feature holds and does (state, rules, records)
├── command/            FlyCommand (the command typed alone), SpeedCommand: one subcommand each
├── listener/           FlyListener: turns an event into a call on the domain, nothing more
└── storage/            FlyRepository, and what only the SQL needs
```

- A feature has no other package than `command`, `listener` and `storage`, each made when it gets a
  class. The rest (domain, records, settings, exceptions) stays in the feature's own package; a
  feature too large for one package is two features, talking through `contracts`.
- Names: the package is the feature's name, one lowercase word (`fly`, `profile`), which `name()`
  also returns. The entry point is `<Feature>Module`, or `<Plugin>Hook` for a hook; then
  `<Feature>Settings`, `<Feature>Repository`, `<Reason>Exception`. A subcommand is `<Word>Command`
  after the word typed after the plugin's command (`/foliate speed` → `SpeedCommand`), and what the
  command does typed alone is `<Feature>Command`. Those words, and with them the `command.<word>.`
  messages and the `<plugin>.command.<word>` nodes, are shared by every feature: pick them so.
  A listener is `<Topic>Listener`. A domain class is a noun for what it holds (`FlySessions`,
  `FlyLedger`), never a `Manager`, `Utils` or `Helper`.
- A helper that knows nothing of the feature, such as finding a player by name or running work on
  a player's thread, belongs in `core`: written once, for every feature.
- What lies below a feature's package is public so that its own package can reach it, but only the
  feature uses it: outside, only the feature's own package is known, and nothing but that package
  depends on its commands and listeners. `ArchitectureTest` checks the subpackages, these
  dependencies, and the names and places of entry points, commands and listeners.
- Resources follow the feature's name: `migrations/<feature>/NNN_<what>.sql`; in `messages.yml`
  its messages under `<feature>.` and those of its subcommands under `command.<word>.`; its section
  `<feature>:` in `config.yml`; permission nodes `<plugin>.command.<word>` (`.others` to act on
  another player) and `<plugin>.<feature>.<what>` for the rest.
- Tests mirror the package of the class they test. A feature's check on a real server is
  `<Feature>Check`, in the same package of the `serverTest` source set.
