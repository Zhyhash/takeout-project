-- V20261006 only created the header table. Existing users need a lockable row,
-- and its merchant binding must agree with their existing cart contents.
-- Reject ambiguous/invalid carts before changing any persistent business data.
-- Temporary CHECK constraints give actionable errors without permanent routines.
DROP TEMPORARY TABLE IF EXISTS takeout_cart_header_preflight;
CREATE TEMPORARY TABLE takeout_cart_header_preflight (
    multiple_merchant_carts BIGINT NOT NULL,
    orphan_carts BIGINT NOT NULL,
    invalid_merchant_carts BIGINT NOT NULL,
    CONSTRAINT chk_cart_header_single_merchant CHECK (multiple_merchant_carts = 0),
    CONSTRAINT chk_cart_header_user_exists CHECK (orphan_carts = 0),
    CONSTRAINT chk_cart_header_positive_merchant CHECK (invalid_merchant_carts = 0)
) ENGINE = InnoDB;

INSERT INTO takeout_cart_header_preflight
SELECT
    (SELECT COUNT(*) FROM (
        SELECT user_id
        FROM cart
        GROUP BY user_id
        HAVING COUNT(DISTINCT merchant_id) > 1
    ) AS ambiguous_carts),
    (SELECT COUNT(*)
     FROM cart c LEFT JOIN `user` u ON u.id = c.user_id
     WHERE u.id IS NULL),
    (SELECT COUNT(*) FROM cart WHERE merchant_id <= 0);

DROP TEMPORARY TABLE takeout_cart_header_preflight;

INSERT INTO cart_header (user_id, merchant_id)
SELECT u.id, MIN(c.merchant_id)
FROM `user` u
LEFT JOIN cart c ON c.user_id = u.id
LEFT JOIN cart_header h ON h.user_id = u.id
WHERE h.user_id IS NULL
GROUP BY u.id;

-- Also repair headers created with NULL/a stale merchant by earlier code.
-- Empty carts release their binding, matching CartHeaderManager.unbindMerchant.
UPDATE cart_header h
JOIN `user` u ON u.id = h.user_id
LEFT JOIN (
    SELECT user_id, MIN(merchant_id) AS merchant_id
    FROM cart
    GROUP BY user_id
) AS existing_cart ON existing_cart.user_id = h.user_id
SET h.merchant_id = existing_cart.merchant_id
WHERE NOT (h.merchant_id <=> existing_cart.merchant_id);
