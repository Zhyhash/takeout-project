package org.example.takeout.Merchant.Service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class MerchantSseService {

    private final Map<Long, SseEmitter> connections =
            new ConcurrentHashMap<>();

    public SseEmitter connect(Long merchantId) {
        SseEmitter emitter = new SseEmitter(0L);

        SseEmitter old = connections.put(merchantId, emitter);
        if (old != null) {
            old.complete();
        }

        emitter.onCompletion(() ->
                connections.remove(merchantId, emitter));

        emitter.onError(e ->
                connections.remove(merchantId, emitter));

        try {
            emitter.send(
                    SseEmitter.event()
                            .name("CONNECTED")
                            .data("connected")
            );
        } catch (Exception e) {
            connections.remove(merchantId, emitter);
            emitter.completeWithError(e);
        }

        return emitter;
    }

    public void notifyNewOrder(Long merchantId) {
        SseEmitter emitter = connections.get(merchantId);

        if (emitter == null) {
            return;
        }

        try {
            emitter.send(
                    SseEmitter.event().
                            name("NEW_ORDER").
                            data("refresh")
            );
        } catch (Exception e) {
            connections.remove(merchantId, emitter);
            emitter.completeWithError(e);
            log.warn(
                    "商家新订单SSE通知失败，merchantId={}",
                    merchantId, e
            );
        }
    }
    public void heartbeat() {
        connections.forEach((merchantId, emitter) -> {
            try {
                emitter.send(
                        SseEmitter.event()
                                .comment("heartbeat")
                );
            } catch (Exception e) {
                connections.remove(merchantId, emitter);
                emitter.completeWithError(e);
            }
        });
    }
}
