-- Merchant login usernames use `username`; rider login usernames use `name`.
ALTER TABLE `merchant`
    MODIFY COLUMN `username` VARCHAR(100)
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_0900_as_cs
    NOT NULL;

ALTER TABLE `rider`
    MODIFY COLUMN `name` VARCHAR(255)
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_0900_as_cs
    NOT NULL;
