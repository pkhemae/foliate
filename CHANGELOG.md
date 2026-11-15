# Changelog

All notable changes to this project are listed here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- Scheduling facade over Folia's region, entity, global and async schedulers.
- Module lifecycle: enable, reload, disable, with failures isolated.
- Typed `config.yml` with comments, safe reload and migrations.
- MiniMessage messages in per-language files, English and French included.
- `/foliate` command with `info`, `reload` and `menu`, one class per subcommand.
- Optional PlaceholderAPI and zMenu integrations.
- Per-player scoreboard sidebar.
- SQL storage (SQLite, MySQL, MariaDB) with schema migrations, and per-player profiles.
- Optional bStats metrics and update check.
- Unit tests and a Folia smoke test run by CI.
- `renameTemplate` task, GitHub release workflow, Renovate configuration.
