<div align="center">

# Foliate

**A production-ready starting point for Minecraft plugins on Folia.**

[![Build](https://github.com/pkhemae/foliate/actions/workflows/build.yml/badge.svg)](https://github.com/pkhemae/foliate/actions/workflows/build.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
![Minecraft 26.2](https://img.shields.io/badge/Minecraft-26.2-green.svg)
![Java 25](https://img.shields.io/badge/Java-25-orange.svg)

[Documentation](https://github.com/pkhemae/foliate/wiki) · [Getting started](https://github.com/pkhemae/foliate/wiki/Getting-started) · [Issues](https://github.com/pkhemae/foliate/issues)

</div>

[Folia](https://github.com/PaperMC/Folia) ticks regions of the world in parallel. There is no main
thread any more, which breaks most of what plugin developers take for granted. Foliate gives you
the parts every plugin needs, already written the way Folia requires, so you start from working
code instead of a list of rules. The same jar runs on Paper.

## Features

| | |
| --- | --- |
| **Scheduling** | One facade over Folia's region, entity, global and async schedulers |
| **Modules** | Features with an enable, reload and disable lifecycle, isolated from each other |
| **Configuration** | A typed `config.yml`, generated with comments, reloaded safely, migrated between versions |
| **Messages** | MiniMessage text in per-language files, shown in each player's language |
| **Commands** | One class per subcommand, on Paper's native command API, with declared permissions |
| **Storage** | SQLite out of the box, MySQL or MariaDB by configuration, with schema migrations |
| **Player data** | Loaded before login, cached, saved in order, flushed on shutdown |
| **Sidebar** | A per-player scoreboard that works on Folia |
| **Integrations** | PlaceholderAPI and zMenu, both optional |
| **Tests** | Unit tests, and a smoke test that boots a real Folia server in CI |
| **Tooling** | One command to rename the template, GitHub releases on tag, Renovate, update check |

## Quick start

Use this repository as a template, then make it yours:

```bash
./gradlew renameTemplate -PnewName=Skylands -PnewGroup=com.example.skylands -PnewRepository=you/skylands
```

Build it:

```bash
./gradlew build
```

Run it on a local Folia server, with PlaceholderAPI and zMenu installed for you:

```bash
./gradlew runFolia
```

The server asks you to accept the Minecraft EULA in `run/eula.txt` on first start. The jar to
install elsewhere is the one in `build/libs/` without `-plain` in its name.

## Documentation

The [wiki](https://github.com/pkhemae/foliate/wiki) has one page per topic.

| Start here | Guides |
| --- | --- |
| [Getting started](https://github.com/pkhemae/foliate/wiki/Getting-started) | [Modules](https://github.com/pkhemae/foliate/wiki/Modules) · [Configuration](https://github.com/pkhemae/foliate/wiki/Configuration) · [Messages](https://github.com/pkhemae/foliate/wiki/Messages) · [Commands](https://github.com/pkhemae/foliate/wiki/Commands) |
| [Folia threading](https://github.com/pkhemae/foliate/wiki/Folia-threading) | [Placeholders](https://github.com/pkhemae/foliate/wiki/Placeholders) · [Menus](https://github.com/pkhemae/foliate/wiki/Menus) · [Scoreboard sidebar](https://github.com/pkhemae/foliate/wiki/Scoreboard-sidebar) · [Storage and player data](https://github.com/pkhemae/foliate/wiki/Storage-and-player-data) |
| [Making it yours](https://github.com/pkhemae/foliate/wiki/Making-it-yours) | [Optional integrations](https://github.com/pkhemae/foliate/wiki/Optional-integrations) · [Testing](https://github.com/pkhemae/foliate/wiki/Testing) · [Releasing](https://github.com/pkhemae/foliate/wiki/Releasing) |

## Requirements

| | |
| --- | --- |
| Server | Folia or Paper 26.2 |
| Java | 25, downloaded by Gradle if missing |
| Optional plugins | PlaceholderAPI 2.12.3+, zMenu 1.1.2.1+ |

## Status

Foliate is verified by its unit tests and by a smoke test that runs on a real Folia 26.2 server
and on Paper 26.2. What needs a connected player has not been verified yet: the sidebar drawing,
a menu opening, and the login and quit flow of the player data. MySQL and MariaDB have not been
exercised, only SQLite. See [#29](https://github.com/pkhemae/foliate/issues/29).

## Contributing

Contributions are welcome: see [CONTRIBUTING.md](CONTRIBUTING.md). Changes are listed in the
[changelog](CHANGELOG.md).

## License

[MIT](LICENSE). PlaceholderAPI and zMenu are separate plugins under their own licenses and are not
bundled.
