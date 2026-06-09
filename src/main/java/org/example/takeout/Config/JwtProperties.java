package org.example.takeout.Config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "jwt")
public class JwtProperties {

    /**
     * HS256 签名密钥，建议至少 32 个字符。
     */
    private String secret;

    /**
     * Token 有效天数。
     */
    private int expireDays = 7;
}
