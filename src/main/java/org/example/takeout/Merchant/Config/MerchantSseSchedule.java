package org.example.takeout.Merchant.Config;

import lombok.RequiredArgsConstructor;
import org.example.takeout.Merchant.Service.MerchantSseService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MerchantSseSchedule {
    private final MerchantSseService merchantSseService;

    @Scheduled(fixedRate = 30000)
    public void schedule() {
        merchantSseService.heartbeat();
    }
}
