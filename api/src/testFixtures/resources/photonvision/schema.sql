-- PhotonVision's settings database, photon.sqlite, as its migrations make it: DatabaseSchema in
-- photon-core (org.photonvision.common.configuration), at the commit the template's PhotonLib
-- pins. Migration 1 creates the tables, migration 2 adds otherpaths_json, and user_version counts
-- the migrations run. Transcribed for the tests, which need no PhotonVision jar; a round trip
-- against a database the pinned jar itself made is a follow-up for CI.
CREATE TABLE IF NOT EXISTS global (
 filename TINYTEXT PRIMARY KEY,
 contents mediumtext NOT NULL
);
CREATE TABLE IF NOT EXISTS cameras (
 unique_name TINYTEXT PRIMARY KEY,
 config_json text NOT NULL,
 drivermode_json text NOT NULL,
 pipeline_jsons mediumtext NOT NULL
 );
ALTER TABLE cameras ADD COLUMN otherpaths_json TEXT NOT NULL DEFAULT '[]';
PRAGMA user_version = 2;
