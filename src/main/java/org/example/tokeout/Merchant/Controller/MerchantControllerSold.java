package org.example.tokeout.Merchant.Controller;

import jakarta.validation.Valid;
import org.example.tokeout.Common.Result.Result;
import org.example.tokeout.Merchant.DTO.MerchantLoginDTO;
import org.example.tokeout.Merchant.DTO.MerchantUpdateDTO;
import org.example.tokeout.Merchant.Service.MerchantService;
import org.example.tokeout.Merchant.VO.MerchantUpdateVO;
import org.example.tokeout.Merchant.VO.loginVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 商家登录与信息管理控制器
 * 职责：接收HTTP请求，调用Service层处理业务，返回响应结果
 */
@RestController
@RequestMapping("/merchant")
public class MerchantControllerSold {
    
    @Autowired
    private MerchantService merchantService;

    /**
     * 商家登录
     */
    @PostMapping("/login")
    public Result<?> login(@RequestBody @Valid MerchantLoginDTO dto) {
        loginVO loginVO = merchantService.login(dto);
        return Result.success(loginVO);
    }

    /**
     * 更新商家信息
     */
    @PutMapping("/info")
    public Result<?> updateMerchantInfo(@RequestBody @Valid MerchantUpdateDTO dto) {
        MerchantUpdateVO merchantUpdateVO = merchantService.updateMerchant(dto);
        return Result.success(merchantUpdateVO);
    }
}