package org.example.takeout.User.Controller;

import jakarta.validation.Valid;
import org.example.takeout.Common.Result.Result;
import org.example.takeout.User.DTO.LoginDTO;
import org.example.takeout.User.DTO.RegisterDTO;
import org.example.takeout.User.Service.UserService;
import org.example.takeout.User.VO.LoginVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/user")
public class UserController {
    @Autowired
    private UserService userService;
    @PostMapping("/register")
    public Result<?> register(@RequestBody @Valid RegisterDTO registerDTO){
        userService.register(registerDTO);
        return Result.success("success");
    }

    @PostMapping("/login")
    public Result<?> login(@RequestBody @Valid LoginDTO loginDTO){
        LoginVO login = userService.login(loginDTO);
        return Result.success(login);
    }
}
