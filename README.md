<div align="center">

<img src="assets/logo.svg" alt="Foliate logo" width="128" height="128">

# Foliate

**A starting point for Minecraft plugins on Folia, built the way Folia requires.**

[![Build](https://github.com/pkhemae/foliate/actions/workflows/build.yml/badge.svg)](https://github.com/pkhemae/foliate/actions/workflows/build.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
![Minecraft 26.2](https://img.shields.io/badge/Minecraft-26.2-green.svg)
![Java 25](https://img.shields.io/badge/Java-25-orange.svg)

[Documentation](https://github.com/pkhemae/foliate/wiki) · [Getting started](https://github.com/pkhemae/foliate/wiki/Getting-started) · [Issues](https://github.com/pkhemae/foliate/issues)

</div>

Foliate is a template for plugins that run on [Folia](https://github.com/PaperMC/Folia). It gives
you the parts every plugin needs, already written for Folia's threading model, so you start from
working code. New to Folia? Read its [overview](https://docs.papermc.io/folia/reference/overview/)
first. The same jar runs on Paper.

## Features

| Area | What you get |
| --- | --- |
| **Scheduling** | One facade over Folia's region, entity, global and async schedulers |
| **Modules** | Features with an enable, reload and disable lifecycle, isolated from each other |
| **Configuration** | A typed `config.yml`, generated with comments, reloaded safely, migrated between versions |
| **Messages** | MiniMessage text in per-language files, shown in each player's language |
| **Commands** | One class per subcommand, on Paper's native command API, with declared permissions |
| **Storage** | SQLite out of the box, MySQL or MariaDB by configuration, with schema migrations |
| **Player data** | Loaded before login, cached, saved in order, flushed on shutdown |
| **Sidebar** | A per-player scoreboard drawn with packets, since Bukkit scoreboards do not work on Folia |
| **Integrations** | PlaceholderAPI and zMenu, both optional |
| **Tests** | Unit tests, and a smoke test that boots a real Folia server in CI |
| **Tooling** | One command to rename the template, GitHub releases on tag, Renovate, update check |

## Quick start

Clone or fork this repository, then make it yours:

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
| [Scheduling](https://github.com/pkhemae/foliate/wiki/Scheduling) | [Placeholders](https://github.com/pkhemae/foliate/wiki/Placeholders) · [Menus](https://github.com/pkhemae/foliate/wiki/Menus) · [Scoreboard sidebar](https://github.com/pkhemae/foliate/wiki/Scoreboard-sidebar) · [Storage and player data](https://github.com/pkhemae/foliate/wiki/Storage-and-player-data) |
| [Making it yours](https://github.com/pkhemae/foliate/wiki/Making-it-yours) | [Optional integrations](https://github.com/pkhemae/foliate/wiki/Optional-integrations) · [Testing](https://github.com/pkhemae/foliate/wiki/Testing) · [Releasing](https://github.com/pkhemae/foliate/wiki/Releasing) |

## Requirements

| Requirement | Version |
| --- | --- |
| Server | Folia or Paper 26.2 |
| Java | 25, downloaded by Gradle if missing |
| Optional plugins | PlaceholderAPI 2.12.3+, zMenu 1.1.2.1+ |

## Status

Foliate is at version 0.1.0-SNAPSHOT and has no release yet. It is verified by its unit tests and
by a smoke test that CI runs on a real Folia 26.2 server; the same smoke test was run by hand on
Paper 26.2. A second CI job joins that server with a real Minecraft client, run headlessly: the
sidebar is created and refreshed without error, the profile is loaded and saved, the example menu
opens and the connection holds. A headless client draws nothing, so what the screen shows is not
checked, and commands are run as the player rather than typed by one. MySQL and MariaDB have not
been exercised, only SQLite.

## Contributing

Contributions are welcome: see [CONTRIBUTING.md](CONTRIBUTING.md). Changes are listed in the
[changelog](CHANGELOG.md).

## License

[MIT](LICENSE). PlaceholderAPI and zMenu are separate plugins under their own licenses and are not
bundled.

The leaf in the logo is the "leaf" glyph of [Material Design Icons](https://pictogrammers.com/library/mdi/)
(Apache 2.0), the icon papermc.io uses for Folia. Foliate is an independent template, not
affiliated with or endorsed by PaperMC. If you build your own plugin from it, replace
`assets/logo.svg` with your own.
