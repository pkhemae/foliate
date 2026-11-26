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

A feature is a module (`modules/<feature>`) or a hook (`hooks/<plugin>`). Every feature is laid out
the same way, and grows the same way: by the parts of its domain, never by kinds of code.

```
modules/elytra/
├── ElytraModule.java        entry point: builds the feature, follows enable/reload/disable,
│                            offers its subcommands, lists its MIGRATIONS. Wiring only, no logic
├── ElytraSettings.java      its section of config.yml, when it has one
├── ElytraPlaceholders.java  its placeholders, when it has some
├── Workshop.java …          the classes the whole feature shares, or that tie its concepts
│                            together (Kit, Restorations, Workshop)
├── command/                 roles: one subcommand per class (StartCommand, JoinCommand)
├── listener/                       one listener per topic (RaceListener, EditorListener)
├── storage/                        the repositories, and what only the SQL needs
├── course/                  concepts: one part of the domain, its values and its logic together
│                            (Course, Courses, Ring, RingBlocks)
├── editor/                  (Editors, Tool)
└── race/                    (ElytraGame, Race, Racer, Records)
```

A class that uses several concepts, such as `Workshop`, which edits courses but checks the race and
the edit mode first, sits above them, in the feature's own package: in `course`, it would make
`course` and `editor` use each other.

- **Roles** are `command`, `listener` and `storage`, and only those. Every subcommand of the
  feature is in `command`, every listener in `listener`, every repository in `storage`, whichever
  concept it serves. A listener turns an event into a call on the domain, nothing more.
- **Concepts** are the other subpackages. One is made when the feature's own package would pass
  10 classes, or as soon as a few classes clearly make one part of the domain: the courses of a
  race, its edit mode, the round of a game. Its name is one lowercase noun of the domain, singular:
  `course`, `race`, `editor`, `round`, `session`, `button`. Never a kind of code: no `util`,
  `helper`, `manager`, `model`, `data`, `service`, `impl`, `common`, `shared`, `misc`, `api`,
  `base`. A value and the code that works on it sit side by side, not records in one package and
  their logic in another.
- **One level.** Nothing goes below a role or a concept. A concept past 15 classes is two concepts;
  a feature that needs a deeper tree is two features, talking through `contracts`.
- **Concepts are layered.** When `race` uses `course`, `course` does not use `race`, directly or
  through another concept. The feature's own package and its roles may use any concept; a concept
  may use the feature's own package and `storage`, never its commands or listeners.
- **Names:**
  - The package is the feature's name, one lowercase word (`fly`, `profile`), which `name()`
    also returns.
  - The entry point is `<Feature>Module`, or `<Plugin>Hook` for a hook. Then come
    `<Feature>Settings`, `<Feature>Placeholders`, `<Feature>Repository` (`<What>Repository` for
    a feature with several: `RecordsRepository`) and `<Reason>Exception`.
  - A subcommand is `<Word>Command`, after the word typed after the plugin's command
    (`/foliate speed` → `SpeedCommand`). What the command does typed alone is `<Feature>Command`.
    Those words, and with them the `command.<word>.` messages and the `<plugin>.command.<word>`
    nodes, are shared by every feature: pick them so.
  - A listener is `<Topic>Listener`.
  - A domain class is a noun for what it holds (`Course`, `Racer`, `Workshop`), never a `Manager`,
    `Utils` or `Helper`. The plural names the collection of a value (`Courses`, `Records`).
- **What two features share:** if it knows nothing of either, it belongs in `core`, written once for
  every feature: finding a player by name, running work on a player's thread, refusing with a
  message. If one feature offers it to another, it goes in `contracts`. A class is never copied
  from one feature to another.
- **Visibility.** What lies below a feature's package is public so that its own package can reach
  it, but only the feature uses it. From outside, only the feature's own package is known, and
  nothing but that package and classes of the same role depend on its commands and listeners.
- **Resources follow the feature's name:**
  - `migrations/<feature>/NNN_<what>.sql`;
  - in `messages.yml`, its messages under `<feature>.` and those of its subcommands under
    `command.<word>.`;
  - its section `<feature>:` in `config.yml`;
  - permission nodes `<plugin>.command.<word>` (`.others` to act on another player), and
    `<plugin>.<feature>.<what>` for the rest.
- **Tests** mirror the package of the class they test. A feature's check on a real server is
  `<Feature>Check`, in the same package of the `serverTest` source set.
- **`ArchitectureTest` checks:**
  - where commands and listeners live and how they are named;
  - the names of concepts, the single level and the sizes;
  - the circles between concepts, in the bytecode and in the imports;
  - the entry points, and who depends on what.

## Writing a feature

- **One job per class**, which the first sentence of its Javadoc says. A class past about 400
  lines, or whose first sentence needs two "and"s, does several: split it along its jobs, at the
  latest when it is next changed.
- **Values are records, immutable.** A change makes a new value. State that several threads read is
  an immutable value swapped atomically, or a concurrent collection. A plain mutable field is
  never shared; a `volatile` field that stands on its own, written by one thread, is the most it
  may be.
- **Refusals.** The domain refuses what cannot be done with `core.message.RefusedException` (a
  message key, written inline, and its arguments). The command that asked catches it and tells
  the sender. Keep the rule and its reason together, and never let a command check a rule that the
  domain also checks. It is a checked exception: a task given to a scheduler catches it inside.
- **Settings.** There is one class per section of `config.yml`, with nested classes for its
  sub-sections. It checks itself in `validate()`, which throws `ConfigException` with a message
  an administrator can act on, and is read through the supplier at each use, so that a reload takes effect. Something that must not change while it
  runs (a game's rules) is read once when it starts, into a value of its own.
- **Files the feature owns** sit under `<feature>/` in the data folder and are read and written off
  the tick threads. A hook keeps what its plugin reads where that plugin looks: zMenu's menus in
  `inventories/`. Data several servers may share goes in the database.
- **A family of similar features** (the events of an event plugin) shares its skeleton through
  `core` when that skeleton knows nothing of any one of them. Each feature stays complete on its
  own.
