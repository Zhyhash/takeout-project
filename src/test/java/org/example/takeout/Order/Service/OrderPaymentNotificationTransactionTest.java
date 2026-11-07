package org.example.takeout.Order.Service;

import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Utils.Context.UserContextHolder;
import org.example.takeout.Config.MybatisPlusConfig;
import org.example.takeout.Merchant.Service.MerchantSseService;
import org.example.takeout.Order.Enums.OrderStatusEnum;
import org.example.takeout.Order.Mapper.OrderMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real H2 mapper and Spring transactions; only the external SSE notification is replaced. */
@SpringJUnitConfig(OrderPaymentNotificationTransactionTest.TestConfig.class)
class OrderPaymentNotificationTransactionTest {

    private static final long ORDER_ID = 501L;
    private static final long USER_ID = 101L;
    private static final long MERCHANT_ID = 201L;

    @Autowired private OrderService orderService;
    @Autowired private MerchantSseService merchantSseService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DataSource dataSource;
    @Autowired private PlatformTransactionManager transactionManager;

    @BeforeEach
    void resetDatabase() {
        reset(merchantSseService);
        UserContextHolder.setUserId(USER_ID);
        jdbcTemplate.execute("DROP TABLE IF EXISTS orders");
        jdbcTemplate.execute("""
                CREATE TABLE orders (
                    id BIGINT PRIMARY KEY,
                    user_id BIGINT NOT NULL,
                    merchant_id BIGINT NOT NULL,
                    delivery_fee DECIMAL(10, 2) NOT NULL DEFAULT 0.00,
                    status INT NOT NULL,
                    cancel_source INT,
                    create_time TIMESTAMP NOT NULL,
                    pay_time TIMESTAMP,
                    merchant_seen BOOLEAN NOT NULL DEFAULT FALSE
                )
                """);
        jdbcTemplate.update("""
                INSERT INTO orders (id, user_id, merchant_id, status, create_time)
                VALUES (?, ?, ?, ?, ?)
                """, ORDER_ID, USER_ID, MERCHANT_ID, OrderStatusEnum.WAIT_PAY.getCode(),
                LocalDateTime.now().minusMinutes(1));
    }

    @AfterEach
    void clearContext() {
        UserContextHolder.clear();
    }

    @Test
    void notificationWaitsForOutermostCommitAndCanReadCommittedPaidOrder() {
        doAnswer(invocation -> {
            // An independent connection sees only committed data, even inside afterCommit().
            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement();
                 ResultSet order = statement.executeQuery(
                         "SELECT status, pay_time, merchant_seen FROM orders WHERE id = " + ORDER_ID)) {
                assertTrue(order.next());
                assertEquals(OrderStatusEnum.PAID.getCode().intValue(), order.getInt("status"));
                assertNotNull(order.getTimestamp("pay_time"));
                assertFalse(order.getBoolean("merchant_seen"));
            }
            return null;
        }).when(merchantSseService).notifyNewOrder(MERCHANT_ID);

        new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
            orderService.payOrder(ORDER_ID);

            assertEquals(OrderStatusEnum.PAID.getCode(), status());
            verifyNoInteractions(merchantSseService);
            assertEquals(OrderStatusEnum.WAIT_PAY.getCode(), committedStatus());
        });

        verify(merchantSseService).notifyNewOrder(MERCHANT_ID);
        verifyNoMoreInteractions(merchantSseService);
    }

    @Test
    void directPaymentCommitsAndRepeatedPaymentDoesNotNotifyAgain() {
        orderService.payOrder(ORDER_ID);

        assertEquals(OrderStatusEnum.PAID.getCode(), committedStatus());
        assertNotNull(jdbcTemplate.queryForObject(
                "SELECT pay_time FROM orders WHERE id = ?", LocalDateTime.class, ORDER_ID));
        assertThrows(BusinessException.class, () -> orderService.payOrder(ORDER_ID));
        verify(merchantSseService).notifyNewOrder(MERCHANT_ID);
        verifyNoMoreInteractions(merchantSseService);
    }

    @Test
    void outerTransactionRollbackRestoresPaymentAndSendsNoNotification() {
        assertThrows(IllegalStateException.class, () ->
                new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
                    orderService.payOrder(ORDER_ID);
                    assertEquals(OrderStatusEnum.PAID.getCode(), status());
                    verifyNoInteractions(merchantSseService);
                    throw new IllegalStateException("downstream operation failed");
                }));

        assertUnpaidAndNotNotified();
    }

    @Test
    void databaseFailureDuringSimulatedPaymentRollsBackAndSendsNoNotification() {
        jdbcTemplate.execute("ALTER TABLE orders ADD CONSTRAINT reject_paid CHECK (status <> 2)");

        assertThrows(DataAccessException.class, () -> orderService.payOrder(ORDER_ID));

        assertUnpaidAndNotNotified();
    }

    @Test
    void expiredPaymentDoesNotChangeOrderOrNotifyMerchant() {
        jdbcTemplate.update("UPDATE orders SET create_time = ? WHERE id = ?",
                LocalDateTime.now().minusMinutes(31), ORDER_ID);

        assertThrows(BusinessException.class, () -> orderService.payOrder(ORDER_ID));

        assertUnpaidAndNotNotified();
    }

    @Test
    void paymentByAnotherUserDoesNotChangeOrderOrNotifyMerchant() {
        UserContextHolder.setUserId(USER_ID + 1);

        assertThrows(BusinessException.class, () -> orderService.payOrder(ORDER_ID));

        assertUnpaidAndNotNotified();
    }

    private Integer status() {
        return jdbcTemplate.queryForObject("SELECT status FROM orders WHERE id = ?", Integer.class, ORDER_ID);
    }

    private Integer committedStatus() {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet order = statement.executeQuery("SELECT status FROM orders WHERE id = " + ORDER_ID)) {
            assertTrue(order.next());
            return order.getInt("status");
        } catch (java.sql.SQLException failure) {
            throw new AssertionError("Could not read committed order", failure);
        }
    }

    private void assertUnpaidAndNotNotified() {
        assertEquals(OrderStatusEnum.WAIT_PAY.getCode(), committedStatus());
        assertNull(jdbcTemplate.queryForObject(
                "SELECT pay_time FROM orders WHERE id = ?", LocalDateTime.class, ORDER_ID));
        assertEquals(Boolean.FALSE, jdbcTemplate.queryForObject(
                "SELECT merchant_seen FROM orders WHERE id = ?", Boolean.class, ORDER_ID));
        verifyNoInteractions(merchantSseService);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import(MybatisPlusConfig.class)
    @MapperScan(basePackageClasses = OrderMapper.class,
            annotationClass = org.apache.ibatis.annotations.Mapper.class)
    static class TestConfig {
        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource(
                    "jdbc:h2:mem:order-payment-notification;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
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
        MerchantSseService merchantSseService() {
            return mock(MerchantSseService.class);
        }

        @Bean
        OrderService orderService(OrderMapper orderMapper, MerchantSseService merchantSseService) {
            return new OrderService(null, null, null, orderMapper, null, null, null, null,
                    merchantSseService, new org.example.takeout.Order.Domain.DeliveryFeeCalculator(), null);
        }
    }
}
