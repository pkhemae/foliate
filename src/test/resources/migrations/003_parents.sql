-- Tests of foreign keys during migrations.
CREATE TABLE parents (id INTEGER PRIMARY KEY, name VARCHAR(20) NOT NULL);
CREATE TABLE children (id INTEGER PRIMARY KEY, parent INTEGER NOT NULL REFERENCES parents (id) ON DELETE CASCADE);
