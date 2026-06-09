package org.example.takeout.Common.Auth;

/**
 * JWT 载荷中的角色标识，与 {@link org.example.takeout.Common.Utils.MyScurity.JWTUtils#createToken} 一致。
 */
public final class AuthRole {

    public static final String USER = "user";
    public static final String MERCHANT = "merchant";

    private AuthRole() {
    }
}
