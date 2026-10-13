import jakarta.validation.Validation;
import org.example.takeout.Cart.Domain.CartAvailableResult;
import org.example.takeout.Cart.Service.CartCheckoutService;
import org.example.takeout.Common.Auth.AuthPathMatcher;
import org.example.takeout.Common.Auth.LoginAttemptLimiter;
import org.example.takeout.Common.Exception.LoginRateLimitException;
import org.example.takeout.Common.Interceptor.JwtInterceptor;
import org.example.takeout.Common.Redis.RedisLoginAttemptStore;
import org.example.takeout.Common.Utils.Context.UserContextHolder;
import org.example.takeout.Order.DTO.CreateOrderDTO;
import org.example.takeout.Order.Entity.Order;
import org.example.takeout.Order.Mapper.OrderConvertor;
import org.example.takeout.Order.Mapper.OrderMapper;
import org.example.takeout.Order.Service.OrderService;
import org.example.takeout.Order.Service.OrderTransactionExecutor;
import org.example.takeout.Order.Support.OrderRequestFingerprint;
import org.example.takeout.Product.DTO.UpdateProductDTO;
import org.mapstruct.factory.Mappers;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Standalone audit probes, not application tests or fixes.
 * Compile against the current Surefire classpath; arguments are test Redis host/port/password.
 * MySQL writes are session-local temporary tables in takeout_integration_test.
 * The replay probe models a committed concurrent request with ordered mocks,
 * rather than claiming to reproduce a real MySQL concurrency schedule.
 */
public class ReachabilityProbe {
    public static void main(String[] args) throws Exception {
        blankNameIsRejected();
        defaultImageIsPublic();
        emptyCartReplayReturnsCommittedOrder();
        mysqlBoundsAndLoginIdentity();
        distinctLoginSpellingsUseIndependentBuckets(args);
        System.out.println("PROBES_COMPLETE: 5 checks confirmed current boundaries; no application fixes applied");
    }

    private static void blankNameIsRejected() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var dto = new UpdateProductDTO();
            dto.setProductName("");
            dto.setVersion(0);
            check(!factory.getValidator().validate(dto).isEmpty(), "blank name unexpectedly accepted");
            dto.setProductName(null);
            check(!factory.getValidator().validate(dto).isEmpty(), "missing required name unexpectedly accepted");
            System.out.println("P_NAME_FIXED: blank or missing update name rejected by current Bean Validation");
        }
    }

    private static void defaultImageIsPublic() throws Exception {
        var request = new MockHttpServletRequest("GET", "/images/default-product.svg");
        check(AuthPathMatcher.isPublicPath(request), "default image unexpectedly protected");
        // Public paths do not need account dependencies or a token.
        var interceptor = new JwtInterceptor(null, null, null, null);
        check(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()),
                "default image rejected anonymously");
        System.out.println("P_IMAGE_FIXED: anonymous GET accepted by actual JwtInterceptor");
    }

    private static void emptyCartReplayReturnsCommittedOrder() {
        var mapper = mock(OrderMapper.class);
        var checkout = mock(CartCheckoutService.class);
        var executor = mock(OrderTransactionExecutor.class);
        var fingerprint = new OrderRequestFingerprint();
        var dto = new CreateOrderDTO();
        dto.setRequestId("risk-review-same-key");
        dto.setReceiverName("receiver");
        dto.setReceiverPhone("13800000000");
        dto.setReceiverAddress("address");
        var committed = new Order();
        committed.setId(123L);
        committed.setRequestHash(fingerprint.calculate(dto));
        var committedDuringPreparation = new AtomicBoolean(false);
        var queries = new AtomicInteger();
        when(mapper.selectOne(any())).thenAnswer(invocation -> {
            queries.incrementAndGet();
            return committedDuringPreparation.get() ? committed : null;
        });
        when(checkout.prepareCheckout(7L)).thenAnswer(invocation -> {
            // T1 commits and consumes its cart after T2's first SELECT.
            committedDuringPreparation.set(true);
            return new CartAvailableResult();
        });
        var service = new OrderService(executor, null, null, mapper, null,
                Mappers.getMapper(OrderConvertor.class), checkout, fingerprint);
        UserContextHolder.setUserId(7L);
        try {
            check(service.createOrder(dto).getOrderId().equals(123L), "modeled replay returned a different order");
            check(queries.get() == 2, "empty-cart failure did not recheck the request key");
            verifyNoInteractions(executor);
            System.out.println("P_REPLAY_FIXED: empty-cart replay returns modeled committed order; key SELECT count=2");
        } finally {
            UserContextHolder.clear();
        }
    }

    private static void mysqlBoundsAndLoginIdentity() throws Exception {
        String url = "jdbc:mysql://localhost:3306/takeout_integration_test?serverTimezone=GMT%2B8&useSSL=false&allowPublicKeyRetrieval=true";
        try (var connection = DriverManager.getConnection(url, "root", "root");
             var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("SELECT @@SESSION.sql_mode")) {
                rows.next();
                check(rows.getString(1).contains("STRICT"), "probe requires strict MySQL mode");
            }
            statement.execute("CREATE TEMPORARY TABLE risk_review_bounds (amount DECIMAL(10,2), stock INT)");
            boolean amountRejected = false;
            try {
                statement.executeUpdate("INSERT INTO risk_review_bounds(amount,stock) VALUES (199999999.98,2147483647)");
            } catch (SQLException expected) {
                amountRejected = expected.getErrorCode() == 1264;
            }
            check(amountRejected, "DECIMAL overflow unexpectedly accepted");
            statement.executeUpdate("INSERT INTO risk_review_bounds(amount,stock) VALUES (1.00,2147483647)");
            boolean stockRejected = false;
            try {
                statement.executeUpdate("UPDATE risk_review_bounds SET stock=stock+1");
            } catch (SQLException expected) {
                stockRejected = expected.getErrorCode() == 1264;
            }
            check(stockRejected, "INT overflow unexpectedly accepted");
            try (var rows = statement.executeQuery("SELECT CONVERT(0x43616665 USING utf8mb4) COLLATE utf8mb4_0900_as_cs = CONVERT(0x43616665E2808B USING utf8mb4) COLLATE utf8mb4_0900_as_cs")) {
                rows.next();
                check(rows.getBoolean(1), "historical as_cs comparison changed");
            }
            try (var rows = statement.executeQuery("SELECT CONVERT(0x43616665 USING utf8mb4) COLLATE utf8mb4_0900_bin = CONVERT(0x43616665E2808B USING utf8mb4) COLLATE utf8mb4_0900_bin")) {
                rows.next();
                check(!rows.getBoolean(1), "binary login collation unexpectedly merges different spellings");
            }
            System.out.println("P_DB_BOUNDARY: strict MySQL rejects manual temporary-table DECIMAL/INT overflow (1264), not an API reachability proof; current binary login comparison distinguishes zero-width variants");
        }
    }

    private static void distinctLoginSpellingsUseIndependentBuckets(String[] args) {
        check(args.length == 3, "expected test Redis host/port/password");
        var config = new RedisStandaloneConfiguration(args[0], Integer.parseInt(args[1]));
        config.setPassword(RedisPassword.of(args[2]));
        var factory = new LettuceConnectionFactory(config);
        factory.afterPropertiesSet();
        factory.start();
        var template = new StringRedisTemplate(factory);
        var store = new RedisLoginAttemptStore(template);
        var limiter = new LoginAttemptLimiter(store);
        String name = "RiskProbe" + UUID.randomUUID().toString().replace("-", "");
        String variant = name + "\u200B";
        try {
            for (int i = 0; i < 5; i++) limiter.recordFailure("merchant", name);
            boolean blocked = false;
            try { limiter.checkAllowed("merchant", name); }
            catch (LoginRateLimitException expected) { blocked = true; }
            check(blocked, "original spelling unexpectedly unrestricted");
            limiter.checkAllowed("merchant", variant);
            check(store.getFailureCount("merchant", variant) == 0, "variant unexpectedly shares failure count");
            System.out.println("P_LIMIT_FIXED: original spelling blocked after 5 failures; different spelling has a separate Redis bucket and no longer matches the same account under utf8mb4_0900_bin");
        } finally {
            store.clear("merchant", name);
            store.clear("merchant", variant);
            factory.destroy();
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
