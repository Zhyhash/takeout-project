package org.example.takeout.Order.Support;

import org.example.takeout.Order.DTO.CreateOrderDTO;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Component
//NOTE:计算orderDTO的hash
public class OrderRequestFingerprint {
    public String calculate(CreateOrderDTO dto) {
        String raw = encode(dto.getReceiverName())
                + encode(dto.getReceiverPhone())
                + encode(dto.getReceiverAddress())
                + encode(dto.getRemark())
                + encodeCoordinate(dto.getLongitude())
                + encodeCoordinate(dto.getLatitude());
        byte[] hashBytes;
        try {
            MessageDigest instance = MessageDigest.getInstance("SHA-256");
            hashBytes = instance.digest(raw.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(
                    "SHA-256 algorithm unavailable", e
            );
        }
        return HexFormat.of().formatHex(hashBytes);
    }

    private String encode(String value) {
        if (value==null){
            return "N:";
        }
        return value.length()+":"+value;
    }

    /**
     * 收货坐标按数值规范化后参与指纹，保证 106.5 与 106.500000 这类
     * 等价表示生成相同指纹，而不同收货地点不会复用已有订单。
     */
    private String encodeCoordinate(BigDecimal value) {
        if (value == null) {
            return "N:";
        }
        String normalized = value.stripTrailingZeros().toPlainString();
        return normalized.length() + ":" + normalized;
    }
}
