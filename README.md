# Foliate

A starting point for Minecraft plugins that run on [Folia](https://github.com/PaperMC/Folia), the
fork of Paper where regions of the world tick in parallel. It targets Minecraft 26.2 and Java 25,
and the same jar runs on Paper.

Folia has no main thread, which breaks most of what plugin developers take for granted. Foliate
gives you the parts every plugin needs, already written the way Folia requires, so you start from
working code instead of a list of rules.

## What is in the box

| Need | What you get |
| --- | --- |
| Scheduling | One facade over Folia's global, region, entity and async schedulers. No `BukkitScheduler` anywhere. |
| Lifecycle | Modules with enable, reload and disable, started in order and stopped in reverse, with failures isolated. |
| Configuration | A typed `config.yml`, generated with comments, reloaded safely, migrated between versions. |
| Messages | MiniMessage text in per-language files, shown in each player's language. |
| Commands | `/foliate info`, `reload` and `menu`, on Paper's Brigadier API, with declared permissions. |
| Placeholders | Your values exposed to PlaceholderAPI, and PlaceholderAPI usable in your messages. Optional. |
| Menus | YAML menus rendered by [zMenu](https://github.com/Maxlego08/zMenu). Optional. |
| Sidebar | A per-player scoreboard drawn with FastBoard, since Bukkit scoreboards do not work on Folia. |
| Storage | A SQL database (SQLite out of the box, MySQL or MariaDB by configuration) with schema migrations. |
| Player data | A worked example of loading, caching and saving per-player data without blocking a tick or losing a write. |
| Metrics | bStats, off until you set your own bStats id, and switchable in `config.yml`. |
| Update check | Asks GitHub once at startup for a newer release and logs it. Switchable in `config.yml`. |
| Examples | `/example`: four commands showing how to reach another player, a block, world state and a file. |
| Tests | Unit tests, plus a smoke test that boots a real Folia server in CI. |

## Getting started

You need a JDK to launch Gradle; it downloads Java 25 by itself if you do not have it.

```bash
./gradlew build
```

builds the plugin into `build/libs/`. The jar without `-plain` in its name is the one to install.

```bash
./gradlew runFolia
```

starts a Folia test server in `run/` with the plugin, PlaceholderAPI and zMenu. On first start the
server stops and asks you to accept the Minecraft EULA in `run/eula.txt`. Add `-Phooks=false` to
start without the two optional plugins.

```bash
./gradlew runFolia -PselfTest=true
```

runs the smoke test: the plugin tests itself on the real server, then stops it, and the task fails
if anything went wrong. It needs the EULA accepted like any other run; CI passes
`-PacceptEula=true` instead of editing `run/eula.txt`. `./gradlew runServer -PselfTest=true` does
the same on Paper.

## The rules Folia imposes

There is no main thread. Each region of loaded chunks ticks on its own thread, and so do your event
handlers and commands. Three rules follow, and the code in this repository is built around them.

**Touch a thing only from the thread that owns it.** Go through `Schedulers` and pick the context
that owns what you touch:

```java
schedulers.entity(player).execute(() -> player.setHealth(20));          // a player or entity
schedulers.region(location).execute(() -> location.getBlock().setType(Material.STONE)); // blocks
schedulers.global().execute(() -> world.setStorm(true));                // weather, time, game rules
schedulers.async().execute(() -> repository.save(profile));             // blocking I/O, never the world
```

To chain work across contexts, use `run` and `supply`, whose futures complete even when the entity
they were bound to is gone:

```java
schedulers.async().supply(() -> repository.load(id))
        .thenCompose(data -> schedulers.entity(player).run(() -> apply(player, data)));
```

If the player logs out in between, the chain fails instead of hanging: its handlers receive a
`CompletionException` whose cause is `EntityRetiredException`. Teleport with
`schedulers.teleport(entity, location)`; `Entity#teleport` is broken on Folia.

**Shared state must be thread-safe.** Anything a module keeps in a field is read by several threads
at once. Use immutable snapshots replaced as a whole (the configuration, the messages) or
concurrent collections (the profile cache). Never a plain `HashMap`.

**Nothing can be scheduled while the plugin shuts down.** Region threads are already stopped when
`onDisable` runs: the server rejects new global and async tasks and silently drops region and entity
ones, and a future still waiting on such a task never completes. Whatever is left, such as saving
data, has to run synchronously in `disable()`, and must not touch the world.

`ExampleCommand` applies the first rule to four real cases, with the reasoning in its comments.

Paper and unit tests run everything on one thread, so they cannot catch a violation of these rules.
Only a Folia server can, which is why the smoke test exists.

## How the code is organised

```
dev.foliate
├── FoliatePlugin        the entry point: everything is created and wired here, by hand
├── core/                infrastructure you keep
│   ├── Platform         Folia detection
│   ├── scheduler/       Schedulers, TaskContext
│   ├── module/          Module, ModuleManager
│   ├── config/          PluginConfig (the content of config.yml), ConfigService
│   ├── message/         MessageService
│   ├── storage/         Database, Migrations
│   ├── placeholder/     PlaceholderRegistry
│   ├── menu/            Menus
│   └── yaml/            Yaml, the one YAML dialect for the plugin's files
├── command/             /foliate and its permissions
├── hook/                optional integrations: placeholderapi, zmenu
├── sidebar/             the FastBoard sidebar
├── profile/             the per-player data example
├── example/             /example, the commands that demonstrate the threading rules
├── metrics/             bStats
├── update/              the update check
└── selftest/            what the smoke test runs
```

Dependencies are passed through constructors. There are no static singletons and no injection
framework: `FoliatePlugin#start` is the one place that shows what depends on what.

## Making it yours

There is no rename script yet ([#27](https://github.com/pkhemae/foliate/issues/27)), so renaming is
a search for `foliate`, in any case, across the repository. The build file keeps the name in two
variables, which covers the generated `plugin.yml`, the relocation prefix and the smoke-test log
check. The rest, by kind:

- **Project**: `rootProject.name` in `settings.gradle.kts`; `group` and `description` in
  `gradle.properties`; `pluginName`, the authors, the website and the permission nodes in
  `build.gradle.kts`; the copyright line in `LICENSE`.
- **Yours to set**: the repository in `GitHubReleases.REPOSITORY`, which the update check reads
  releases from, and `FoliatePlugin.BSTATS_PLUGIN_ID`, your id on bStats.
- **Code**: the `dev.foliate` package and the `FoliatePlugin` class (rename them with your IDE,
  which also updates the tests); the command name and its description in `FoliateCommand` and
  `FoliatePlugin`; the nodes in `Permissions`; the placeholder identifier in `FoliateExpansion`.
- **Tests**: the ones that type the command (`"foliate info"`) or check the placeholder identifier
  and the default sidebar title need the new names.
- **What administrators see**: the prefix and the `command.info` text in `lang/*.yml`, the default
  sidebar title and the MySQL defaults in `PluginConfig`, the header in `ConfigService`, the
  example menu in `inventories/example.yml`, the pool and thread names in `Database`.
- **Decide before your first release**: the table names (`foliate_profiles`,
  `foliate_schema_history`). A migration that has shipped cannot be edited.
- **Smoke test**: the system properties `foliate.selftest` and `foliate.selftest.expect` and the
  verdict text appear both in `SelfTest` and in `build.gradle.kts`. Renaming them is optional, but
  change both sides together or the smoke test stops seeing the verdict.

### Removing a feature you do not need

A feature is its package, the tests of that package, and what refers to it:

| Feature | Also remove |
| --- | --- |
| `example` | its two lines in `FoliatePlugin`; `COMMAND_EXAMPLE` in `Permissions` and its node in `build.gradle.kts`; the `example` keys in `lang/*.yml`; `"example time"` in `SelfTest` |
| `metrics` | its line in `FoliatePlugin`; the `metrics` setting of `PluginConfig`; the `bstats` dependency |
| `update` | its line in `FoliatePlugin`; the `updateCheck` setting of `PluginConfig`; `updates` in `expectedModules` |
| `sidebar` | its line in `FoliatePlugin`; the `sidebar` section of `PluginConfig`; the `fastboard` dependency; `sidebar` in `expectedModules` (`build.gradle.kts`) |
| `profile` | its line in `FoliatePlugin`; `profiles` in `expectedModules`. Keep or replace the `V1` migration script: the database tests use its table |
| `core/storage` (with `profile`) | the `Database` lines in `FoliatePlugin` and in `SelfTest`; the `storage` section of `PluginConfig`; the `hikaricp` and `sqlite-jdbc` dependencies; `storage` in `expectedModules` |
| `hook/placeholderapi` | its line in `FoliatePlugin`; `PlaceholderAPI` in `softDepend`; its `compileOnly` and `testImplementation` dependencies, its repository and its entry in `testServerPlugins`; `placeholderapi` in `expectedModules` |
| `hook/zmenu` | its line in `FoliatePlugin` (pass `null` as the menus of `FoliateCommand`, or remove that parameter and the `menu` subcommand); `core/menu`; `zMenu` in `softDepend`; its dependencies, repository and `testServerPlugins` entry; `inventories/`; `zmenu` in `expectedModules` |

`expectedModules` is the list of modules the smoke test insists on finding enabled: leaving a removed
module in it makes the smoke test fail, which is the reminder.

## Common tasks

**Add a setting.** Add a field with its default and a `@Comment` to `PluginConfig`, and a getter.
The file on every server gains the new key on the next start. Read it with `config.get()` each
time you need it, so that a reload takes effect. Renaming or moving an existing key needs a step in
`ConfigMigrator`.

**Add a message.** Add the key to `src/main/resources/lang/en.yml` (and the other languages), then
`messages.send(player, "your.key", Placeholder.unparsed("name", value))`. Pass anything a player
typed with `Placeholder.unparsed`, never `Placeholder.parsed`.

**Add a feature.** Write a class implementing `Module` and register it in `FoliatePlugin`, after
the modules it depends on. Do the setup in `enable()`, undo it in `disable()`.

**Add a subcommand.** Add a branch in `FoliateCommand#build`, a constant in `Permissions`, and the
same node in the `permissions` block of `build.gradle.kts`; a test fails if the two lists differ.
Also add the new permission to `anySubcommand` in `build`: it decides who sees `/foliate` at all,
and a player holding only the new permission is otherwise locked out. A handler runs on the
sender's region: to act on another player or location, go through `Schedulers`.

**Integrate another plugin.** Follow `PlaceholderApiHook`: a `Module` created through
`FoliatePlugin#hook`, so it only exists when the other plugin is installed. Add the plugin to
`softDepend` and its API as `compileOnly`. Keep classes that extend the other plugin's types behind
the hook class. To have it on the test servers and required by the smoke test, add it to
`testServerPlugins` and `expectedModules` in `build.gradle.kts`.

**Add a menu.** Put the zMenu file in `src/main/resources/inventories/` and its name in
`ZMenuHook.MENUS`, then call `menus.open(player, "name")`. The `Menus` given by `FoliatePlugin` is
null when zMenu is not installed: check it, as `FoliateCommand` does.

**Change the database schema.** Add a script to `src/main/resources/db/migration/` and its name to
`Migrations.SCRIPTS`. Never edit a script that has shipped.

**Test a module that uses timers.** Use `ManualSchedulers` from the test sources: nothing runs
until the test calls `tick(n)`. MockBukkit cannot cancel scheduled tasks.

## Releasing

Push a tag named after the version, with a leading `v`:

```bash
git tag v1.2.0 && git push origin v1.2.0
```

The release workflow builds the plugin as that version, runs the tests, and publishes a GitHub
release with the jar attached and generated notes. A tag with a suffix (`v1.2.0-rc.1`) becomes a
pre-release. `gradle.properties` keeps the next snapshot version; bump it after a release. The
update check reads these GitHub releases.

## Outbound connections

Hangar and Modrinth require a plugin to say what it connects to. This one makes two kinds of
outbound connection, both of which an administrator can turn off in `config.yml`:

- **Update check** (`update-check`): one request to the GitHub API at startup, to read the latest
  release of the repository named in `GitHubReleases.REPOSITORY`. Nothing about the server is sent
  beyond what any request reveals, its address and Java's user agent. It is on by default, so the
  very first start makes it before an administrator has seen the setting.
- **Metrics** (`metrics`): anonymous statistics to bStats.org (a random server id, player count,
  online mode, server, plugin and Java versions, operating system, processor cores). Inactive
  until you register your plugin on bStats and put its id in `FoliatePlugin.BSTATS_PLUGIN_ID`.

Both settings are read at startup: a change applies after a restart. With MySQL or MariaDB the
plugin also connects to the database server the administrator configured.

Tell your users about both on your download page.

## What has not been verified

The smoke test passes on a real Folia 26.2 server, with and without the optional plugins, and on
Paper 26.2. The commands, the reload paths and both hooks were also exercised by hand from the
server console. Nothing so far has connected a player, so the following rests on unit tests only:

- the sidebar actually drawing (FastBoard does not officially support Folia) and a menu opening;
- the login, join and quit flow of the profile example on a real server;
- messages shown in a player's own language, and `<papi:...>` evaluated for a player;
- a real player logging out in the middle of a chain (the smoke test removes a marker entity).

Also not exercised: MySQL and MariaDB (only SQLite), a real configuration migration (the
mechanism is tested, but there is no migration step yet), bStats actually reporting (it has no id
here), and the update check finding a newer release (the repository has none yet; the comparison
is unit-tested). A test with a connected client is tracked
in [#29](https://github.com/pkhemae/foliate/issues/29).

## Targeting another Minecraft version

The target is set in `gradle/libs.versions.toml`. Bump `minecraft`, `folia-api` and the MockBukkit
artifact together: they are tied to the Minecraft version.

## License

MIT, see [LICENSE](LICENSE). PlaceholderAPI and zMenu are separate plugins under their own licenses
and are not bundled.
