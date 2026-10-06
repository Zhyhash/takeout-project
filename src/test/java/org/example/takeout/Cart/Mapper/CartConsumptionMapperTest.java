package org.example.takeout.Cart.Mapper;

import org.example.takeout.Config.MybatisPlusConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import javax.sql.DataSource;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringJUnitConfig(CartConsumptionMapperTest.TestConfig.class)
class CartConsumptionMapperTest {

    private static final long USER_ID = 101L;
    private static final long OTHER_USER_ID = 202L;
    private static final long ITEM_ID = 1L;
    private static final LocalDateTime ORIGINAL_UPDATE_TIME =
            LocalDateTime.of(2020, 1, 1, 0, 0);

    @Autowired
    private CartMapper cartMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetCart() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS cart (
                    id BIGINT PRIMARY KEY,
                    user_id BIGINT NOT NULL,
                    product_id BIGINT,
                    merchant_id BIGINT,
                    quantity INT NOT NULL,
                    product_name VARCHAR(255),
                    product_image VARCHAR(255),
                    price DECIMAL(10, 2),
                    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    version INT NOT NULL DEFAULT 0
                )
                """);
        jdbcTemplate.update("DELETE FROM cart");
    }

    @Test
    void consumeQuantityBindsUserIdAndKeepsQuantityAddedAfterSnapshot() {
        insertItem(ITEM_ID, USER_ID, 2, 4);
        int snapshotQuantity = quantity(ITEM_ID);

        // Simulate a committed add after the order has read its cart snapshot.
        jdbcTemplate.update("""
                UPDATE cart
                SET quantity = quantity + 3, version = version + 1
                WHERE id = ? AND user_id = ?
                """, ITEM_ID, USER_ID);

        // Invoke the real mapper proxy: a missing userId binding fails here.
        assertEquals(1, cartMapper.consumeQuantity(USER_ID, ITEM_ID, snapshotQuantity));
        assertEquals(3, quantity(ITEM_ID));
        assertEquals(6, version(ITEM_ID));
        assertTrue(updateTime(ITEM_ID).isAfter(ORIGINAL_UPDATE_TIME));

        cartMapper.deleteIfEmpty(USER_ID, ITEM_ID);

        assertEquals(1, itemCount(ITEM_ID));
        assertEquals(3, quantity(ITEM_ID));
    }

    @Test
    void exactConsumptionLeavesZeroUntilDeleteIfEmptyRemovesOwnedItem() {
        insertItem(ITEM_ID, USER_ID, 3, 4);

        assertEquals(1, cartMapper.consumeQuantity(USER_ID, ITEM_ID, 3));
        assertEquals(1, itemCount(ITEM_ID));
        assertEquals(0, quantity(ITEM_ID));
        assertEquals(5, version(ITEM_ID));

        cartMapper.deleteIfEmpty(USER_ID, ITEM_ID);

        assertEquals(0, itemCount(ITEM_ID));
    }

    @Test
    void insufficientQuantityReturnsZeroWithoutChangingItem() {
        insertItem(ITEM_ID, USER_ID, 2, 4);

        assertEquals(0, cartMapper.consumeQuantity(USER_ID, ITEM_ID, 3));

        assertEquals(2, quantity(ITEM_ID));
        assertEquals(4, version(ITEM_ID));
        assertEquals(ORIGINAL_UPDATE_TIME, updateTime(ITEM_ID));
    }

    @Test
    void consumptionAfterCartReductionRejectsOutdatedSnapshot() {
        insertItem(ITEM_ID, USER_ID, 4, 4);
        int snapshotQuantity = quantity(ITEM_ID);
        jdbcTemplate.update("""
                UPDATE cart
                SET quantity = 1, version = version + 1
                WHERE id = ? AND user_id = ?
                """, ITEM_ID, USER_ID);

        assertEquals(0, cartMapper.consumeQuantity(USER_ID, ITEM_ID, snapshotQuantity));

        assertEquals(1, quantity(ITEM_ID));
        assertEquals(5, version(ITEM_ID));
    }

    @Test
    void missingItemReturnsZeroAndDeletionIsHarmless() {
        assertEquals(0, cartMapper.consumeQuantity(USER_ID, ITEM_ID, 1));
        assertDoesNotThrow(() -> cartMapper.deleteIfEmpty(USER_ID, ITEM_ID));
        assertEquals(0, itemCount(ITEM_ID));
    }

    @Test
    void anotherUserCannotConsumeItem() {
        insertItem(ITEM_ID, USER_ID, 3, 4);

        assertEquals(0, cartMapper.consumeQuantity(OTHER_USER_ID, ITEM_ID, 2));

        assertEquals(3, quantity(ITEM_ID));
        assertEquals(4, version(ITEM_ID));
        assertEquals(ORIGINAL_UPDATE_TIME, updateTime(ITEM_ID));
    }

    @Test
    void anotherUserCannotDeleteEmptyItem() {
        insertItem(ITEM_ID, USER_ID, 0, 4);

        cartMapper.deleteIfEmpty(OTHER_USER_ID, ITEM_ID);

        assertEquals(1, itemCount(ITEM_ID));
        assertEquals(0, quantity(ITEM_ID));
        assertEquals(4, version(ITEM_ID));
    }

    @Test
    void deleteIfEmptyKeepsNonEmptyItemAndOtherRows() {
        insertItem(ITEM_ID, USER_ID, 2, 4);
        insertItem(2L, USER_ID, 0, 8);
        insertItem(3L, OTHER_USER_ID, 0, 9);

        cartMapper.deleteIfEmpty(USER_ID, ITEM_ID);

        assertEquals(1, itemCount(ITEM_ID));
        assertEquals(2, quantity(ITEM_ID));
        assertEquals(4, version(ITEM_ID));
        assertEquals(1, itemCount(2L));
        assertEquals(1, itemCount(3L));

        cartMapper.deleteIfEmpty(USER_ID, 2L);

        assertEquals(0, itemCount(2L));
        assertEquals(1, itemCount(ITEM_ID));
        assertEquals(1, itemCount(3L));
    }

    @Test
    void existsByUserIdReturnsFalseForEmptyCart() {
        assertFalse(cartMapper.existsByUserId(USER_ID));
    }

    @Test
    void existsByUserIdReturnsTrueForOwnedItem() {
        insertItem(ITEM_ID, USER_ID, 2, 0);

        assertTrue(cartMapper.existsByUserId(USER_ID));
    }

    @Test
    void existsByUserIdDoesNotIncludeAnotherUsersItems() {
        insertItem(ITEM_ID, OTHER_USER_ID, 2, 0);

        assertFalse(cartMapper.existsByUserId(USER_ID));
        assertTrue(cartMapper.existsByUserId(OTHER_USER_ID));
    }

    @Test
    void existsByUserIdReturnsFalseAfterOwnedItemIsConsumedAndDeleted() {
        insertItem(ITEM_ID, USER_ID, 3, 0);
        insertItem(2L, OTHER_USER_ID, 1, 0);
        assertTrue(cartMapper.existsByUserId(USER_ID));

        assertEquals(1, cartMapper.consumeQuantity(USER_ID, ITEM_ID, 3));
        cartMapper.deleteIfEmpty(USER_ID, ITEM_ID);

        assertFalse(cartMapper.existsByUserId(USER_ID));
        assertTrue(cartMapper.existsByUserId(OTHER_USER_ID));
    }

    private void insertItem(long id, long userId, int quantity, int version) {
        jdbcTemplate.update("""
                INSERT INTO cart (id, user_id, quantity, version, update_time)
                VALUES (?, ?, ?, ?, ?)
                """, id, userId, quantity, version, ORIGINAL_UPDATE_TIME);
    }

    private int quantity(long id) {
        return jdbcTemplate.queryForObject(
                "SELECT quantity FROM cart WHERE id = ?", Integer.class, id);
    }

    private int version(long id) {
        return jdbcTemplate.queryForObject(
                "SELECT version FROM cart WHERE id = ?", Integer.class, id);
    }

    private LocalDateTime updateTime(long id) {
        return jdbcTemplate.queryForObject(
                "SELECT update_time FROM cart WHERE id = ?", LocalDateTime.class, id);
    }

    private int itemCount(long id) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM cart WHERE id = ?", Integer.class, id);
    }

    @Configuration(proxyBeanMethods = false)
    @Import(MybatisPlusConfig.class)
    @MapperScan(basePackageClasses = CartMapper.class)
    static class TestConfig {

        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource(
                    "jdbc:h2:mem:cart-consumption-mapper-test;MODE=MySQL;DB_CLOSE_DELAY=-1",
                    "sa", "");
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }
    }
}
