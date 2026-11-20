CREATE TABLE foliate_profiles (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(32) NOT NULL,
    first_seen BIGINT NOT NULL,
    last_seen BIGINT NOT NULL,
    joins INTEGER NOT NULL
);
