-- Rename columns to avoid SQL reserved keywords and improve readability
-- H2 syntax: ALTER TABLE table RENAME COLUMN oldName TO newName

-- History table: key -> item_key
ALTER TABLE history RENAME COLUMN `key` TO item_key;

-- Navigation table: value -> nav_value
ALTER TABLE navigation RENAME COLUMN `value` TO nav_value;

-- Movie table: year -> release_year
ALTER TABLE movie RENAME COLUMN `year` TO release_year;

-- Tmdb table: year -> release_year
ALTER TABLE tmdb RENAME COLUMN `year` TO release_year;

-- Meta table: year -> release_year
ALTER TABLE meta RENAME COLUMN `year` TO release_year;

-- Tmdb_meta table: year -> release_year
ALTER TABLE tmdb_meta RENAME COLUMN `year` TO release_year;

-- Plugin table: extend -> extension, version -> plugin_version
ALTER TABLE plugin RENAME COLUMN `extend` TO extension;
ALTER TABLE plugin RENAME COLUMN `version` TO plugin_version;

-- Plugin_filter table: extend -> extension, version -> plugin_version
ALTER TABLE plugin_filter RENAME COLUMN `extend` TO extension;
ALTER TABLE plugin_filter RENAME COLUMN `version` TO plugin_version;

-- Setting table: svalue -> setting_value
ALTER TABLE setting RENAME COLUMN svalue TO setting_value;
