/* Rebuilds the parents' table, as SQLite needs to change a column. */
CREATE TABLE parents_new (id INTEGER PRIMARY KEY, name VARCHAR(40) NOT NULL);
INSERT INTO parents_new (id, name) SELECT id, name FROM parents;
DROP TABLE parents;
ALTER TABLE parents_new RENAME TO parents;
