ALTER TABLE `user`
    MODIFY COLUMN username VARCHAR(50)
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_0900_as_cs
    NOT NULL;

CREATE INDEX idx_order_status_create_time_id
    ON orders (status, create_time, id);
