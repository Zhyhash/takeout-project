package org.example.takeout.Order.Support;

import org.example.takeout.Order.DTO.CreateOrderDTO;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Component
public class OrderRequestFingerprint {
    public String calculate(CreateOrderDTO dto) {
        String raw = encode(dto.getReceiverName())
                + encode(dto.getReceiverPhone())
                + encode(dto.getReceiverAddress())
                + encode(dto.getRemark());
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
}
