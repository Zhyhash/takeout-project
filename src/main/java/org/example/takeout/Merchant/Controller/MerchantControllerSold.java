package org.example.takeout.Merchant.Controller;

import jakarta.validation.Valid;
import org.example.takeout.Common.Result.Result;
import org.example.takeout.Merchant.DTO.MerchantLoginDTO;
import org.example.takeout.Merchant.DTO.MerchantRegisterDTO;
import org.example.takeout.Merchant.DTO.MerchantUpdateDTO;
import org.example.takeout.Merchant.Service.MerchantService;
import org.example.takeout.Merchant.VO.MerchantUpdateVO;
import org.example.takeout.Merchant.VO.loginVO;
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
    @PostMapping("/register")
    public Result<?> register(@Valid  @RequestBody MerchantRegisterDTO dto){
        merchantService.register(dto);
        return Result.success("success");
    }

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