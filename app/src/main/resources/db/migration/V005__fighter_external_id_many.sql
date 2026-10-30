-- A source may know one fighter under several ids (admin assigns a second id to an existing fighter, merged
-- duplicates); (source, external_id) stays unique, so every id still points to exactly one fighter.
ALTER TABLE fighter_external_id DROP CONSTRAINT fighter_external_id_pkey;
CREATE INDEX fighter_external_id_fighter_idx ON fighter_external_id (fighter_id, source);
