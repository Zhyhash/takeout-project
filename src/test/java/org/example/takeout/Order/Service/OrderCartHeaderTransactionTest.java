package org.example.takeout.Order.Service;

import org.example.takeout.Cart.Entity.CartItem;
import org.example.takeout.Cart.Mapper.CartMapper;
import org.example.takeout.CartHeader.Manager.CartHeaderManager;
import org.example.takeout.CartHeader.Mapper.CartHeaderMapper;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Result.ResultCodeEnum;
import org.example.takeout.Config.MybatisPlusConfig;
import org.example.takeout.Merchant.Entity.Merchant;
import org.example.takeout.Order.DTO.CreateOrderDTO;
import org.example.takeout.Order.Domain.OrderDataContext;
import org.example.takeout.Order.Entity.Order;
import org.example.takeout.Order.Mapper.OrderConvertor;
import org.example.takeout.Order.Mapper.OrderMapper;
import org.example.takeout.Order.Support.OrderRequestFingerprint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 订单、购物车和总表使用真实 Mapper 与 Spring 事务；仅隔离库存和订单明细服务。
 * H2 验证 SQL 绑定和提交/回滚，MySQL 的并发行为由 OrderServiceIntegrationTest 覆盖。
 */
@SpringJUnitConfig(OrderCartHeaderTransactionTest.TestConfig.class)
class OrderCartHeaderTransactionTest {
    private static final long USER_ID = 101L;
    private static final long MERCHANT_ID = 201L;
    private static final long ITEM_ID = 401L;

    @Autowired private OrderTransactionExecutor executor;
    @Autowired private CartMapper cartMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private OrderItemService orderItemService;

    private CreateOrderDTO request;
    private String requestHash;

    @BeforeEach
    void resetDatabase() {
        reset(orderItemService);
        when(orderItemService.buildOrderItems(any(), anyList(), anyMap())).thenReturn(List.of());
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS cart_header (
                    user_id BIGINT PRIMARY KEY,
                    merchant_id BIGINT
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS cart (
                    id BIGINT PRIMARY KEY,
                    user_id BIGINT NOT NULL,
                    product_id BIGINT NOT NULL,
                    merchant_id BIGINT NOT NULL,
                    quantity INT NOT NULL,
                    product_name VARCHAR(255),
                    product_image VARCHAR(255),
                    price DECIMAL(10, 2),
                    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    version INT NOT NULL DEFAULT 0
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS orders (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    order_no VARCHAR(64) NOT NULL UNIQUE,
                    user_id BIGINT NOT NULL,
                    request_id VARCHAR(64) NOT NULL,
                    request_hash VARCHAR(64),
                    merchant_id BIGINT,
                    merchant_name VARCHAR(255),
                    total_amount DECIMAL(10, 2),
                    original_amount DECIMAL(10, 2),
                    discount_amount DECIMAL(10, 2),
                    status INT,
                    receiver_name VARCHAR(20),
                    receiver_phone VARCHAR(20),
                    receiver_address VARCHAR(255),
                    remark VARCHAR(200),
                    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    finish_time TIMESTAMP,
                    pay_time TIMESTAMP,
                    UNIQUE (user_id, request_id)
                )
                """);
        jdbcTemplate.update("DELETE FROM orders");
        jdbcTemplate.update("DELETE FROM cart");
        jdbcTemplate.update("DELETE FROM cart_header");
        jdbcTemplate.update("INSERT INTO cart_header (user_id, merchant_id) VALUES (?, ?)",
                USER_ID, MERCHANT_ID);
        insertItem(ITEM_ID, 301L, 5);

        request = new CreateOrderDTO();
        request.setRequestId("cart-header-transaction-request");
        request.setReceiverName("Tester");
        request.setReceiverPhone("13800138000");
        request.setReceiverAddress("Test Road");
        requestHash = new OrderRequestFingerprint().calculate(request);
    }

    @Test
    void completeConsumptionCommitsOrderAndUnbindsEmptyCart() {
        Order order = create(snapshot(ITEM_ID));

        assertNotNull(order.getId());
        assertEquals(1, orderCount());
        assertNull(cartMapper.selectById(ITEM_ID));
        assertFalse(cartMapper.existsByUserId(USER_ID));
        assertNull(boundMerchant());
        assertEquals(requestHash, jdbcTemplate.queryForObject(
                "SELECT request_hash FROM orders WHERE id = ?", String.class, order.getId()));
    }

    @Test
    void sameProductAddedAfterSnapshotKeepsQuantityAndBindingOnIdempotentRetry() {
        OrderDataContext snapshot = snapshot(ITEM_ID);
        jdbcTemplate.update("UPDATE cart SET quantity = quantity + 1, version = version + 1 WHERE id = ?", ITEM_ID);

        Order first = create(snapshot);
        assertEquals(1, cartMapper.selectById(ITEM_ID).getQuantity());
        assertEquals(6, cartMapper.selectById(ITEM_ID).getVersion());
        assertEquals(MERCHANT_ID, boundMerchant());

        Order replay = create(snapshot);
        assertEquals(first.getId(), replay.getId());
        assertEquals(1, orderCount());
        assertEquals(1, cartMapper.selectById(ITEM_ID).getQuantity());
        assertEquals(6, cartMapper.selectById(ITEM_ID).getVersion());
        assertEquals(MERCHANT_ID, boundMerchant());
        verify(orderItemService, times(1)).decreaseStocksOrderedByProductId(anyList());
        verify(orderItemService, times(1)).saveBatch(anyList());
    }

    @Test
    void differentProductAddedAfterSnapshotKeepsItemAndMerchantBinding() {
        OrderDataContext snapshot = snapshot(ITEM_ID);
        insertItem(402L, 302L, 2);

        create(snapshot);

        assertNull(cartMapper.selectById(ITEM_ID));
        assertEquals(2, cartMapper.selectById(402L).getQuantity());
        assertTrue(cartMapper.existsByUserId(USER_ID));
        assertEquals(MERCHANT_ID, boundMerchant());
        assertEquals(1, orderCount());
    }

    @Test
    void stockFailureRestoresDeletedCartAndUnboundMerchant() {
        doThrow(new BusinessException(ResultCodeEnum.BUSINESS_ERROR, "库存不足"))
                .when(orderItemService).decreaseStocksOrderedByProductId(anyList());

        assertThrows(BusinessException.class, () -> create(snapshot(ITEM_ID)));

        assertEquals(0, orderCount());
        assertEquals(5, cartMapper.selectById(ITEM_ID).getQuantity());
        assertEquals(4, cartMapper.selectById(ITEM_ID).getVersion());
        assertEquals(MERCHANT_ID, boundMerchant());
        verify(orderItemService, never()).saveBatch(anyList());
    }

    @Test
    void detailFailureAlsoRestoresOrderCartAndMerchantBinding() {
        doThrow(new BusinessException(ResultCodeEnum.DATABASE_ERROR, "明细保存失败"))
                .when(orderItemService).saveBatch(anyList());

        assertThrows(BusinessException.class, () -> create(snapshot(ITEM_ID)));

        assertEquals(0, orderCount());
        assertEquals(5, cartMapper.selectById(ITEM_ID).getQuantity());
        assertEquals(4, cartMapper.selectById(ITEM_ID).getVersion());
        assertEquals(MERCHANT_ID, boundMerchant());
    }

    @Test
    void outdatedSecondItemRollsBackEarlierConsumptionAndOrderInsert() {
        insertItem(402L, 302L, 2);
        OrderDataContext snapshot = snapshot(ITEM_ID, 402L);
        jdbcTemplate.update("UPDATE cart SET quantity = 1, version = version + 1 WHERE id = ?", 402L);

        BusinessException failure = assertThrows(BusinessException.class, () -> create(snapshot));

        assertEquals(ResultCodeEnum.BUSINESS_ERROR, failure.getCodeEnum());
        assertEquals(0, orderCount());
        assertEquals(5, cartMapper.selectById(ITEM_ID).getQuantity());
        assertEquals(4, cartMapper.selectById(ITEM_ID).getVersion());
        assertEquals(1, cartMapper.selectById(402L).getQuantity());
        assertEquals(5, cartMapper.selectById(402L).getVersion());
        assertEquals(MERCHANT_ID, boundMerchant());
        verifyNoInteractions(orderItemService);
    }

    @Test
    void duplicateRequestWithChangedParametersDoesNotConsumeNewCartItems() {
        OrderDataContext snapshot = snapshot(ITEM_ID);
        create(snapshot);
        insertItem(402L, 302L, 2);
        jdbcTemplate.update("UPDATE cart_header SET merchant_id = ? WHERE user_id = ?", MERCHANT_ID, USER_ID);
        clearInvocations(orderItemService);
        request.setRemark("changed");
        String changedHash = new OrderRequestFingerprint().calculate(request);

        BusinessException failure = assertThrows(BusinessException.class,
                () -> executor.executeOrderCreation(snapshot(402L), request, USER_ID, changedHash));

        assertEquals(ResultCodeEnum.PARAM_ERROR, failure.getCodeEnum());
        assertEquals(1, orderCount());
        assertEquals(2, cartMapper.selectById(402L).getQuantity());
        assertEquals(4, cartMapper.selectById(402L).getVersion());
        assertEquals(MERCHANT_ID, boundMerchant());
        verifyNoInteractions(orderItemService);
    }

    private Order create(OrderDataContext context) {
        return executor.executeOrderCreation(context, request, USER_ID, requestHash);
    }

    private OrderDataContext snapshot(Long... itemIds) {
        OrderDataContext context = new OrderDataContext();
        context.setAvailableItems(List.of(itemIds).stream().map(cartMapper::selectById).toList());
        context.setProductMap(Map.of());
        Merchant merchant = new Merchant();
        merchant.setId(MERCHANT_ID);
        merchant.setMerchantName("Test merchant");
        context.setMerchant(merchant);
        context.setTotalAmount(BigDecimal.TEN);
        return context;
    }

    private void insertItem(long id, long productId, int quantity) {
        jdbcTemplate.update("""
                INSERT INTO cart (id, user_id, product_id, merchant_id, quantity, price, version)
                VALUES (?, ?, ?, ?, ?, 2.00, 4)
                """, id, USER_ID, productId, MERCHANT_ID, quantity);
    }

    private Long boundMerchant() {
        return jdbcTemplate.queryForObject("SELECT merchant_id FROM cart_header WHERE user_id = ?",
                Long.class, USER_ID);
    }

    private int orderCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM orders", Integer.class);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import({MybatisPlusConfig.class, CartHeaderManager.class, OrderTransactionExecutor.class})
    @MapperScan(basePackageClasses = {CartMapper.class, CartHeaderMapper.class, OrderMapper.class},
            annotationClass = org.apache.ibatis.annotations.Mapper.class)
    static class TestConfig {
        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource(
                    "jdbc:h2:mem:order-cart-header-transaction;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        OrderDomainService orderDomainService(OrderMapper orderMapper) {
            return new OrderDomainService(orderMapper);
        }

        @Bean
        OrderConvertor orderConvertor() {
            return Mappers.getMapper(OrderConvertor.class);
        }

        @Bean
        OrderItemService orderItemService() {
            return mock(OrderItemService.class);
        }
    }
}
