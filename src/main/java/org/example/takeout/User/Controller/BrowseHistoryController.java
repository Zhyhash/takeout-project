package org.example.takeout.User.Controller;

import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.example.takeout.Common.Result.Result;
import org.example.takeout.Common.Utils.Context.UserContextHolder;
import org.example.takeout.Product.VO.ProductVO;
import org.example.takeout.User.Service.BrowseHistoryService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RequiredArgsConstructor
@RestController
@Validated
@RequestMapping("/user/browse-history")
public class BrowseHistoryController {
    private final BrowseHistoryService browseHistoryService;

    @PostMapping("/{productId}")
    public Result<ProductVO> recordView(@PathVariable @Positive Long productId) {
        return Result.success(browseHistoryService.recordView(UserContextHolder.getUserId(), productId));
    }

    @GetMapping
    public Result<List<ProductVO>> getHistory() {
        return Result.success(browseHistoryService.getHistory(UserContextHolder.getUserId()));
    }

    @DeleteMapping
    public Result<String> clearHistory() {
        browseHistoryService.clearHistory(UserContextHolder.getUserId());
        return Result.success("清空成功");
    }
}
