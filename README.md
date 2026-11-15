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
| Player data | A worked example of loading, caching and saving per-player data without blocking a tick. |
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
./gradlew runFolia -PselfTest
```

runs the smoke test: the plugin tests itself on the real server, then stops it, and the task fails
if anything went wrong.

## The rules Folia imposes

There is no main thread. Each region of loaded chunks ticks on its own thread, and so do your event
handlers and commands. Three rules follow, and the code in this repository is built around them.

**Touch a thing only from the thread that owns it.** Go through `Schedulers` and pick the context
that owns what you touch:

```java
schedulers.entity(player).execute(() -> player.setHealth(20));          // a player or entity
schedulers.region(location).execute(() -> location.getBlock().setType(Material.STONE)); // blocks
schedulers.global().execute(() -> world.setStorm(true));                // weather, time, game rules
schedulers.async().execute(() -> Files.writeString(path, text));        // blocking I/O, never the world
```

To chain work across contexts, use `run` and `supply`, whose futures always complete:

```java
schedulers.async().supply(() -> repository.load(id))
        .thenCompose(data -> schedulers.entity(player).run(() -> apply(player, data)));
```

If the player logs out in between, the chain fails with `EntityRetiredException` instead of hanging.
Teleport with `schedulers.teleport(entity, location)`; `Entity#teleport` is broken on Folia.

**Shared state must be thread-safe.** Anything a module keeps in a field is read by several threads
at once. Use immutable snapshots replaced as a whole (the configuration, the messages) or
concurrent collections (the profile cache). Never a plain `HashMap`.

**Nothing can be scheduled while the plugin shuts down.** Region threads are already stopped when
`onDisable` runs, and the server refuses new tasks. Whatever is left, such as saving data, has to
run synchronously in `disable()`, and must not touch the world.

Paper and unit tests run everything on one thread, so they cannot catch a violation of these rules.
Only a Folia server can, which is why the smoke test exists.

## How the code is organised

```
dev.foliate
├── FoliatePlugin        the entry point: everything is created and wired here, by hand
├── core/                infrastructure you keep
│   ├── scheduler/       Schedulers, TaskContext
│   ├── module/          Module, ModuleManager
│   ├── config/          PluginConfig (the content of config.yml), ConfigService
│   ├── message/         MessageService
│   ├── storage/         Database, Migrations
│   ├── placeholder/     PlaceholderRegistry
│   └── menu/            Menus
├── command/             /foliate and its permissions
├── hook/                optional integrations: placeholderapi, zmenu
├── sidebar/             the FastBoard sidebar
├── profile/             the per-player data example
└── selftest/            what the smoke test runs
```

Dependencies are passed through constructors. There are no static singletons and no injection
framework: `FoliatePlugin#onEnable` is the one place that shows what depends on what.

## Making it yours

1. Rename the project: `rootProject.name` in `settings.gradle.kts`, `group` and `description` in
   `gradle.properties`, and the `bukkitPluginYaml` block of `build.gradle.kts` (name, main class,
   authors, website, permissions).
2. Rename the `dev.foliate` package and the `FoliatePlugin` class with your IDE, and the relocation
   prefix `dev.foliate.libs` in `build.gradle.kts`.
3. Rename the command in `FoliateCommand`, the permission nodes in `Permissions`, the placeholder
   identifier in `FoliateExpansion`, and the prefix in `lang/*.yml`.
4. Delete what you do not need. Each feature is one package plus its line in `FoliatePlugin`:
   `sidebar`, `profile`, `hook/zmenu`, `hook/placeholderapi`.

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
same node in the `permissions` block of `build.gradle.kts`. A test fails if the two lists differ. A
handler runs on the sender's region: to act on another player or location, go through `Schedulers`.

**Integrate another plugin.** Follow `PlaceholderApiHook`: a `Module` created through
`FoliatePlugin#hook`, so it only exists when the other plugin is installed. Add the plugin to
`softDepend` and its API as `compileOnly`. Keep classes that extend the other plugin's types behind
the hook class.

**Add a menu.** Put the zMenu file in `src/main/resources/inventories/` and its name in
`ZMenuHook.MENUS`, then call `menus.open(player, "name")`.

**Change the database schema.** Add a script to `src/main/resources/db/migration/` and its name to
`Migrations.SCRIPTS`. Never edit a script that has shipped.

**Test a module that uses timers.** Use `ManualSchedulers` from the test sources: nothing runs
until the test calls `tick()`. MockBukkit cannot cancel scheduled tasks.

## What has not been verified

Everything above was exercised on a real Folia 26.2 server through the console and the smoke test.
What needs a connected player was not: the sidebar actually drawing (FastBoard does not officially
support Folia), a menu opening, and the login, join and quit flow of the profile example. MySQL and
MariaDB were not exercised either, only SQLite.

## Targeting another Minecraft version

The target is set in `gradle/libs.versions.toml`. Bump `minecraft`, `folia-api` and the MockBukkit
artifact together: they are tied to the Minecraft version.

## License

MIT, see [LICENSE](LICENSE). PlaceholderAPI and zMenu are separate plugins under their own licenses
and are not bundled.
