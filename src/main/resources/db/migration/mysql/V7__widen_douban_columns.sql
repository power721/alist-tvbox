-- Widen douban columns to hold the full xiaoya-douban export, whose
-- description/name/path exceed the default VARCHAR(255). Mirrors the column
-- lengths in Movie/Meta entities (@Column length) so ddl-auto=validate passes.
ALTER TABLE movie MODIFY COLUMN description VARCHAR(1024);
ALTER TABLE meta MODIFY COLUMN name VARCHAR(512);
ALTER TABLE meta MODIFY COLUMN path VARCHAR(512);
