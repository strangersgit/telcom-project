-- =====================================================================
-- TSATMS - 00 - Schema and application account bootstrap
-- ---------------------------------------------------------------------
-- Runs as the MySQL administrator, because neither the schema nor the
-- application account exists at this point.
--
-- The ${...} tokens are substituted by DatabaseBootstrap from
-- application.properties, so the credentials have a single source of
-- truth. To run this file by hand in MySQL Workbench, replace them with
-- the matching values from that file.
-- =====================================================================

-- utf8mb4_0900_ai_ci is the MySQL 8 default. Matching it, rather than
-- overriding with the older utf8mb4_unicode_ci, keeps the schema, the
-- stored routines and the client connection on one collation. Mixing
-- them makes the server reject comparisons between a routine's return
-- value and a string literal.
CREATE DATABASE IF NOT EXISTS `${db.schema}`
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_0900_ai_ci;

-- Applied unconditionally so that re-running against a schema created
-- with a different collation corrects it.
ALTER DATABASE `${db.schema}`
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_0900_ai_ci;

-- The application connects as its own account, never as root.
CREATE USER IF NOT EXISTS '${db.username}'@'localhost' IDENTIFIED BY '${db.password}';
CREATE USER IF NOT EXISTS '${db.username}'@'%' IDENTIFIED BY '${db.password}';

-- Least privilege: data manipulation and routine execution only. The
-- application is deliberately unable to alter or drop its own schema.
GRANT SELECT, INSERT, UPDATE, DELETE, EXECUTE, SHOW VIEW
    ON `${db.schema}`.* TO '${db.username}'@'localhost';

GRANT SELECT, INSERT, UPDATE, DELETE, EXECUTE, SHOW VIEW
    ON `${db.schema}`.* TO '${db.username}'@'%';

FLUSH PRIVILEGES;
