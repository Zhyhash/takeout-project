package org.example.tokeout.Common.Utils.MyScurity;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

public class BCrypt {
    public static BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public static String encode(CharSequence rawPassword) {
        return encoder.encode(rawPassword);
    }

    public static boolean matches(CharSequence rawPassword, String encodedPassword) {
        return encoder.matches(rawPassword, encodedPassword);
    }
}
