-- Resolve the generated index name instead of assuming a server-specific name.
SET @legacy_statement_index = (
    SELECT INDEX_NAME
    FROM information_schema.statistics
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'statement_record'
      AND NON_UNIQUE = 0
      AND INDEX_NAME <> 'PRIMARY'
    GROUP BY INDEX_NAME
    HAVING COUNT(*) = 1 AND MAX(COLUMN_NAME) = 'statement_no'
    LIMIT 1
);
SET @drop_legacy_statement_index = IF(
    @legacy_statement_index IS NULL,
    'SELECT 1',
    CONCAT('ALTER TABLE statement_record DROP INDEX `', REPLACE(@legacy_statement_index, '`', '``'), '`')
);
PREPARE drop_legacy_statement_index FROM @drop_legacy_statement_index;
EXECUTE drop_legacy_statement_index;
DEALLOCATE PREPARE drop_legacy_statement_index;
