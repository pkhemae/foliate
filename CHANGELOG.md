# Changelog

All notable changes to this project are listed here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- Scheduling facade over Folia's region, entity, global and async schedulers.
- Module lifecycle: enable, reload, disable, with failures isolated.
- Typed `config.yml` with comments, safe reload and migrations.
- MiniMessage messages in one `messages.yml`, in English, which the administrator edits to translate them.
- `/foliate` command with `info`, `profile`, `reload` and `menu`, one class per subcommand; a module may also give what the command does typed alone (`SubcommandProvider#bareCommand`).
- `PlayerTarget`, for a subcommand about yourself or another player (`/foliate profile [player]`): the target, the `others` permission checked when the command runs, suggestions limited to the players the sender can see, and the refusals. `OnlinePlayers`, the argument that suggests and finds the players online the sender can see.
- Optional PlaceholderAPI and zMenu integrations.
- Per-player scoreboard.
- SQL storage (SQLite, MySQL, MariaDB) with schema migrations, and per-player profiles: read ahead of other players' saves at login, saved behind in order, retried while the database is unreachable, and flushed on shutdown.
- Unit tests and a Folia smoke test run by CI on two region threads, with two distant regions busy at the same time (the server test lives in its own source set and jar, never in the plugin jar), CI jobs that join the test server with a real Minecraft client (twice as the same player, once typing a command), and profile storage tests on MySQL and MariaDB.
- `renameTemplate` task, GitHub release workflow (each jar published with its SHA-256 checksum), Renovate configuration.
- The notices of the bundled libraries, in the plugin jar's `META-INF/THIRD-PARTY-NOTICES`.
- Static checks: Error Prone and NullAway at compile time, and the layout of the code checked with ArchUnit.
- Upgrade tests: the database each version leaves is frozen as a SQL dump under `src/test/resources/upgrade/`, and the build checks that the current version starts on it and reads its data back: on SQLite, and on MySQL and MariaDB in CI.

### Upgrading from a development build

- The migrations history changed in #122: scripts are recorded by name, in `foliate_migrations`, instead of by position in `foliate_schema_history`. A database made by a development build from before that change no longer starts. No version was released with the old history, so the plugin does not upgrade it by itself, but the profiles can be kept: with the server stopped, run these statements on the database (`sqlite3 plugins/Foliate/data.db`, or the MySQL or MariaDB client). They work before the first failed start and after it.

  ```sql
  CREATE TABLE IF NOT EXISTS foliate_migrations (script VARCHAR(191) PRIMARY KEY, applied_at BIGINT NOT NULL);
  INSERT INTO foliate_migrations (script, applied_at)
      SELECT 'profile/001_create_profiles.sql', applied_at FROM foliate_schema_history WHERE version = 1;
  DROP TABLE foliate_schema_history;
  ```

  A MySQL or MariaDB database made before #52 also needs `ALTER TABLE foliate_profiles MODIFY name VARCHAR(32) NOT NULL;`, since that change widened the column inside the first script. SQLite does not enforce the width. The simple alternative is to delete `data.db` (on MySQL or MariaDB, drop `foliate_profiles` and `foliate_schema_history`) and let the plugin start from an empty database, losing the profiles.
