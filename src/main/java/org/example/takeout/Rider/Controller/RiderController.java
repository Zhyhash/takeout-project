package org.example.takeout.Rider.Controller;

import jakarta.validation.Valid;
import org.example.takeout.Common.Result.Result;
import org.example.takeout.Rider.DTO.RiderLoginDTO;
import org.example.takeout.Rider.DTO.RiderRegisterDTO;
import org.example.takeout.Rider.Service.RiderService;
import org.example.takeout.Rider.VO.RiderLoginVO;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/rider")
public class RiderController {

    private final RiderService riderService;

    public RiderController(RiderService riderService) {
        this.riderService = riderService;
    }

    @PostMapping("/register")
    public Result<?> register(@RequestBody @Valid RiderRegisterDTO dto) {
        riderService.register(dto);
        return Result.success("success");
    }

    @PostMapping("/login")
    public Result<?> login(@RequestBody @Valid RiderLoginDTO dto) {
        RiderLoginVO loginVO = riderService.login(dto);
        return Result.success(loginVO);
    }
}
