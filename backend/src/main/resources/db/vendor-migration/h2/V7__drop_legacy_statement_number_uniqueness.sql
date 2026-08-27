-- H2 names the unnamed V2 column-level unique constraint differently from MySQL.
ALTER TABLE statement_record DROP CONSTRAINT IF EXISTS CONSTRAINT_62;
