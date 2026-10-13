-- Restore the existing login-name comparison policy. The six register/login
-- DTOs accept only ASCII letters, digits, underscore and hyphen (1-50 chars).
-- V20261013 has been executed; preserve its content and Flyway checksum.
-- Check all three unique-name domains before any ALTER, because names created
-- under the binary collation may compare equal under utf8mb4_0900_as_cs.
CREATE TEMPORARY TABLE login_name_as_cs_preflight (
    user_collision TINYINT NOT NULL,
    merchant_collision TINYINT NOT NULL,
    rider_collision TINYINT NOT NULL,
    CONSTRAINT chk_user_login_name_as_cs_unique CHECK (user_collision = 0),
    CONSTRAINT chk_merchant_login_name_as_cs_unique CHECK (merchant_collision = 0),
    CONSTRAINT chk_rider_login_name_as_cs_unique CHECK (rider_collision = 0)
);

INSERT INTO login_name_as_cs_preflight (user_collision, merchant_collision, rider_collision)
SELECT
    EXISTS (SELECT 1 FROM `user`
            GROUP BY username COLLATE utf8mb4_0900_as_cs HAVING COUNT(*) > 1),
    EXISTS (SELECT 1 FROM merchant
            GROUP BY username COLLATE utf8mb4_0900_as_cs HAVING COUNT(*) > 1),
    EXISTS (SELECT 1 FROM rider
            GROUP BY name COLLATE utf8mb4_0900_as_cs HAVING COUNT(*) > 1);

DROP TEMPORARY TABLE login_name_as_cs_preflight;

ALTER TABLE `user`
    MODIFY COLUMN `username` VARCHAR(50)
    CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL;

ALTER TABLE `merchant`
    MODIFY COLUMN `username` VARCHAR(100)
    CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL;

ALTER TABLE `rider`
    MODIFY COLUMN `name` VARCHAR(255)
    CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL;
