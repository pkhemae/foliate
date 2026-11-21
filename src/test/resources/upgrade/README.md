# Databases of earlier versions

Each folder here holds the database that one version of the plugin leaves, frozen as a SQL dump.
`DatabaseUpgradeTest` loads each one into a fresh database, starts the current `Database` on it
with the plugin's migrations, and checks that it starts, that every migration ends up recorded,
that the rows come back through the real repositories, and that a save still works. It does so on
SQLite in every build, and on MySQL and MariaDB in the CI job "Storage on ...". Every other test
starts from an empty database: this is what catches a release that breaks the servers updating to it.

A folder is named after the version that wrote it:

- `v1.2.0` for a release, after its tag;
- `unreleased-<commit>` for the schema of `main` before the first release, at that commit.

## Adding a snapshot at each release

Do it for every release, at the commit that is tagged, before the tag is pushed.

1. Every table of the plugin must get a few rows. `UpgradeSnapshotWriter` (in the tests of the
   profile module) stores them through the plugin's own code: when a module brought a table since
   the last snapshot, add rows for it there, saved through that module's repository.
2. Write the database that this version leaves:

   ```sh
   FOLIATE_UPGRADE_SNAPSHOT=build/upgrade-snapshot ./gradlew test --tests '*UpgradeSnapshotWriter' --rerun
   ```

   `--rerun` is needed: Gradle would otherwise reuse an earlier run of the tests, made without the
   variable, and write nothing.
3. Dump it into a new folder, with the `sqlite3` command-line tool:

   ```sh
   mkdir src/test/resources/upgrade/v1.2.0
   sqlite3 build/upgrade-snapshot/data.db .dump > src/test/resources/upgrade/v1.2.0/dump.sql
   ```

   Keep the dump as `sqlite3` writes it: the tables (`foliate_migrations` included) as the
   migrations created them, then their rows. The test skips the `PRAGMA`, `BEGIN TRANSACTION` and
   `COMMIT` lines, which MySQL does not understand, so the same dump serves SQLite, MySQL and
   MariaDB. The `CREATE TABLE` statements are the migrations' own text, portable by rule.
4. Add the folder to `DatabaseUpgradeTest.SNAPSHOTS`, with the rows the writer stored, written out
   as values: not a reference to the writer's list, which later versions will change.
   `everySnapshotIsChecked` fails while a folder and the list disagree.
5. Run `./gradlew build` and commit the folder with the release.

## Rules

- Never edit a snapshot once it is committed: it is what the servers on that version have.
- When the test fails on a snapshot, the release would break the servers that update from that
  version. Fix the plugin, or add a migration that brings the old schema to the new one: do not
  change the snapshot to make the test pass.
- When an upgrade test changes the expected rows, it is because this version reads the old data
  differently, for example a new field with a default value. That is a decision about the servers
  that update: make it on purpose.
- Removing a snapshot means that the plugin no longer starts on that version's database. Only do it
  on purpose, and say in the changelog what the servers on that version must do.
