package org.example.tokeout.Common.Utils.MyScurity;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import java.security.Key;
import java.util.Date;

public class JWTUtils {

    // 普通用户密钥
    private static final String SECRET = "your-very-secure-and-very-long-secret-key-here";
    private static final Key key = Keys.hmacShaKeyFor(SECRET.getBytes());

    // 商家端密钥
    private static final String SECRET_MERCHANT = "this-is-a-super-long-and-very-secret-jwt-signing-key-for-merchant-backend-system";
    private static final Key merchantkey = Keys.hmacShaKeyFor(SECRET_MERCHANT.getBytes());

    private static final long EXPIRE_TIME = 1000 * 60 * 60 * 24 * 7;

    // --- 普通用户相关方法 ---
    public static String generateToken(Long userId) {
        return Jwts.builder().claim("userId", userId).
                setExpiration(new Date(System.currentTimeMillis() + EXPIRE_TIME)).
                signWith(key).
                compact();
    }

    public static Long parseToken(String token) {
        Claims body = Jwts.parserBuilder().
                setSigningKey(key).
                build().
                parseClaimsJws(token).
                getBody();
        return body.get("userId", Long.class);
    }



    // --- 商家端相关方法 ---
    public static String generateMerchantToken(Long merchantId) {
        return Jwts.builder().claim("merchantId", merchantId).
                setExpiration(new Date(System.currentTimeMillis() + EXPIRE_TIME)).
                signWith(merchantkey).
                compact();
    }

    public static Long parseMerchantToken(String token) {
        Claims body = Jwts.parserBuilder().
                setSigningKey(merchantkey).
                build().
                parseClaimsJws(token).
                getBody();
        return body.get("merchantId", Long.class);
    }

}