package org.example.takeout.Settlement.Controller;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.example.takeout.Common.Result.Result;
import org.example.takeout.Settlement.Service.RewardQueryService;
import org.example.takeout.Settlement.VO.RewardSummaryVO;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequiredArgsConstructor
public class RewardController {
    private final RewardQueryService rewardQueryService;

    @GetMapping("/rider/rewards")
    public Result<RewardSummaryVO> getRiderRewards(
            @RequestParam(defaultValue = "1") @Min(1) Integer pageNum,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) Integer pageSize) {
        return Result.success(rewardQueryService.getRiderRewards(pageNum, pageSize));
    }

    @GetMapping("/merchant/rewards")
    public Result<RewardSummaryVO> getMerchantRewards(
            @RequestParam(defaultValue = "1") @Min(1) Integer pageNum,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) Integer pageSize) {
        return Result.success(rewardQueryService.getMerchantRewards(pageNum, pageSize));
    }
}
