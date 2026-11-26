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

## Architecture

- **Folia first.** There is no main thread. Never use `BukkitScheduler`, never block a tick thread,
  and only touch an entity or block from the region that owns it.
- **Shared state is thread-safe.** Events and commands run in parallel on region threads: share
  immutable values swapped atomically, concurrent collections, or a `volatile` field that stands
  on its own, never a plain mutable field.
- **Layers.** `FoliatePlugin` is the composition root: dependencies go through constructors, with
  no static singletons and no DI container. `core` is reusable infrastructure and knows nothing of
  the features. `modules` and `hooks` hold the features; `commands`, the subcommands about the
  whole plugin. `contracts` holds what features offer each other (interfaces and value types; none
  in the template). Features never depend on each other.
- `onDisable` does no world work and schedules nothing. When the plugin alone is disabled (a plugin
  manager, Paper's `/reload`, a failed start), a task or event already under way may still run, so
  shared state stays thread-safe until the end.
- Third-party plugins are optional hooks (`softdepend` + `compileOnly`), whose classes are only
  loaded when the plugin is installed. Prefer the native Paper API (Adventure/MiniMessage,
  Brigadier, Data Components) over a library; bundled libraries are shaded and relocated.
  Versions, the Minecraft target included, live in `gradle/libs.versions.toml`.
- Files the plugin owns are read with `core.yaml.Yaml` or ConfigLib (YAML 1.2), never Bukkit's
  `YamlConfiguration` (YAML 1.1 changes the type of values such as `no`).
- Code that schedules depends on `Schedulers`, and is tested with `ManualSchedulers` when it uses
  timers or cancellation: MockBukkit cannot cancel tasks.
- Every package that holds classes has a `package-info.java` annotated with `@NullMarked`. NullAway and Error Prone
  run with `-Werror`: any of their warnings fails the build.

## A feature

```
modules/<feature>/          (or hooks/<plugin>/)
├── <Feature>Module.java    the entry point (<Plugin>Hook for a hook), alone here with its nested
│                           types: builds the feature, follows enable/reload/disable, offers its
│                           subcommands through SubcommandProvider, lists its MIGRATIONS. Wiring,
│                           no logic
├── command/                <Word>Command, one class per subcommand, and what they share
├── listener/               <Topic>Listener: turns an event into a call on the domain, nothing more
├── placeholder/            <Feature>Placeholders
├── config/                 <Feature>Settings, its section of config.yml
├── storage/                <What>Repository, and what only the SQL needs
└── <concept>/              the domain, one package per part of it
```

- **Roles** are these five, fixed. Only the entry point (and the same role) uses commands,
  listeners and placeholders; a concept may use `config` and `storage`. Nothing below the entry
  point uses it: it hands its parts what they need. Outside the feature, only the entry point and
  its settings are known; `PluginConfig` composes the settings into `config.yml`.
- **Concepts** hold the domain: a value next to the code that works on it. Each is one lowercase
  noun of the domain (an arena, a round, a shop), never a kind of code (`util`, `manager`, `model`,
  `service`...), with 15 classes at most. They are layered, never in a circle: a class that ties
  several together is a concept above them.
- **One level.** Nothing goes below a role or a concept. A feature that needs more is two features,
  talking through `contracts`.
- **Names.**
  - The package is the feature's name, one lowercase word, which `name()` returns.
  - A subcommand is named after the word typed (`/foliate <word>` → `<Word>Command`). What the
    command does typed alone is `<Feature>Command`.
  - A domain class is a noun for what it holds, never a `Manager`, `Utils` or `Helper`.
  - An exception is `<Reason>Exception`.
- **Shared, never copied.** What two features share goes in `core` when it knows nothing of either
  (finding a player, running work on their thread, `RefusedException`). It goes in `contracts` when
  one feature offers it to the other.
- **Resources follow the feature's name:**
  - `migrations/<feature>/NNN_<what>.sql`;
  - in `messages.yml`, its messages under `<feature>.` and those of its subcommands under
    `command.<word>.`;
  - its section `<feature>:` in `config.yml`, and its files under `<feature>/` in the data folder
    (a hook keeps what its plugin reads where that plugin looks: zMenu's menus in `inventories/`);
  - its permission nodes: `<plugin>.command.<word>` (`.others` for another player) and
    `<plugin>.<feature>.<what>`.

  A node is a `PERMISSION` constant next to the class that checks it (a subcommand, most often),
  declared in `build.gradle.kts`. Subcommand words, with their messages and nodes, are shared by every
  feature: pick them so.
- **Tests** mirror the package of the class they test. A feature's check on a real server is
  `<Feature>Check`, in the feature's own package of the `serverTest` source set, so that it reaches
  the entry point's package-private accessors.
- `ArchitectureTest` checks this layout. The wiki's Architecture page explains it with the
  template's own features.

## Writing a feature

- **One job per class**, which the first sentence of its Javadoc says. Past about 400 lines, split
  it along its jobs, at the latest when it is next changed.
- **Values are records.** A change makes a new value.
- **Refusals.** The domain refuses with `RefusedException`, its message key written inline, and the
  command that asked tells the sender. No command checks a rule that the domain checks. Being
  checked, it is caught inside a task given to a scheduler.
- **Settings** are one class per section, with nested classes for its sub-sections. They check
  themselves in `validate()`, throwing `ConfigException` for an administrator, and are read
  through their supplier at each use, so that a reload takes effect. What must not change while
  it runs is copied once, when it starts.
- **Files** are read and written off the tick threads. What several servers share goes in the
  database.
