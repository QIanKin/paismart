SELECT TABLE_NAME, TABLE_COLLATION FROM information_schema.TABLES
 WHERE TABLE_SCHEMA='paismart' AND TABLE_NAME IN ('creator_accounts','creators','creator_posts','xhs_cookies');

SELECT COLUMN_NAME, DATA_TYPE, CHARACTER_SET_NAME, COLLATION_NAME
 FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA='paismart' AND TABLE_NAME='creator_accounts' AND CHARACTER_SET_NAME IS NOT NULL;

SHOW VARIABLES LIKE 'character_set%';
SHOW VARIABLES LIKE 'collation%';
