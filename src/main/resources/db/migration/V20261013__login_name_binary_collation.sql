-- Login lookup must use the same exact spelling as the Redis failure counter.
-- utf8mb4_0900_bin distinguishes Unicode sequences and trailing spaces (NO PAD).
-- Keep VARCHAR lengths, stored values and existing unique indexes unchanged.
ALTER TABLE `user`
    MODIFY COLUMN `username` VARCHAR(50)
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_0900_bin
    NOT NULL;

ALTER TABLE `merchant`
    MODIFY COLUMN `username` VARCHAR(100)
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_0900_bin
    NOT NULL;

ALTER TABLE `rider`
    MODIFY COLUMN `name` VARCHAR(255)
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_0900_bin
    NOT NULL;
