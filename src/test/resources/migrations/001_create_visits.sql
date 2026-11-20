CREATE TABLE test_visits (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(32) NOT NULL,
    first_seen BIGINT NOT NULL,
    last_seen BIGINT NOT NULL,
    visits INTEGER NOT NULL
);
