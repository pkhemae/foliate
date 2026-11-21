PRAGMA foreign_keys=OFF;
BEGIN TRANSACTION;
CREATE TABLE foliate_migrations (script VARCHAR(191) PRIMARY KEY, applied_at BIGINT NOT NULL);
INSERT INTO foliate_migrations VALUES('profile/001_create_profiles.sql',1795287114079);
CREATE TABLE foliate_profiles (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(32) NOT NULL,
    first_seen BIGINT NOT NULL,
    last_seen BIGINT NOT NULL,
    joins INTEGER NOT NULL
);
INSERT INTO foliate_profiles VALUES('3c0f1d4e-8a52-4b7e-9f10-2d6b5a7c9e01','Alex',1731000000000,1763700000000,214);
INSERT INTO foliate_profiles VALUES('9b2e6a71-0c3d-4f58-a1e9-5f7d2c8b4a36','Steve_The_Miner1',1763000000000,1763000000000,1);
INSERT INTO foliate_profiles VALUES('d41f8c09-6e2b-47a3-8c5d-0b9e3f1a7d24','Notch',1700000000000,1763650000000,3);
COMMIT;
