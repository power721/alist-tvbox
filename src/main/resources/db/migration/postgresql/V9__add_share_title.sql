-- Persist the display title for a parsed share link so TVBox history re-entry
-- (after the in-memory shareTitle cache expires or the server restarts) can still
-- recover the real drama/movie name instead of degrading to the storage folder name.
ALTER TABLE share ADD COLUMN title VARCHAR(255);
