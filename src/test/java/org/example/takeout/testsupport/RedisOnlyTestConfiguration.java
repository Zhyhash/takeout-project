package org.example.takeout.testsupport;

import org.example.takeout.Common.Redis.RedisLoginAttemptStore;
import org.example.takeout.Order.Limited.OrderCreateRateLimiter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

/** Minimal real-Redis context for integration tests that do not need the application. */
@TestConfiguration(proxyBeanMethods = false)
@Import({RedisLoginAttemptStore.class, OrderCreateRateLimiter.class})
public class RedisOnlyTestConfiguration {

    @Bean
    LettuceConnectionFactory redisConnectionFactory(
            @Value("${spring.data.redis.host:127.0.0.1}") String host,
            @Value("${spring.data.redis.port:6379}") int port,
            @Value("${spring.data.redis.database:0}") int database,
            @Value("${spring.data.redis.password:}") String password) {
        RedisStandaloneConfiguration server = new RedisStandaloneConfiguration(host, port);
        server.setDatabase(database);
        if (!password.isEmpty()) {
            server.setPassword(password);
        }
        LettuceClientConfiguration client = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofSeconds(2))
                .shutdownTimeout(Duration.ZERO)
                .build();
        return new LettuceConnectionFactory(server, client);
    }

    @Bean
    StringRedisTemplate stringRedisTemplate(LettuceConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }
}
