package org.example.takeout.Common.Utils.MyScurity;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.example.takeout.Config.JwtProperties;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * 单秘钥 JWT：用户与商家共用同一 secret，通过 claim {@code role} 区分身份。
 */
@Component
public class JWTUtils {

    private final SecretKey signingKey;
    private final long expireMillis;

    public JWTUtils(JwtProperties properties) {
        String secret = properties.getSecret();
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("jwt.secret 未配置或长度不足 32 字符，请在 application.yaml 中设置");
        }
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expireMillis = properties.getExpireDays() * 24L * 60 * 60 * 1000;
    }

    public String createToken(Long id, String role) {
        return Jwts.builder()
                .claim("id", id)
                .claim("role", role)
                .setExpiration(new Date(System.currentTimeMillis() + expireMillis))
                .signWith(signingKey)
                .compact();
    }

    public Claims parseToken(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(signingKey)
                .build()
                .parseClaimsJws(token)
                .getBody();
    }
}
