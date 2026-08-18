package org.example.takeout.User.Service;

import org.example.takeout.Common.Exception.AuthException;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Utils.Context.UserContextHolder;
import org.example.takeout.Common.Utils.MyScurity.BCrypt;
import org.example.takeout.Common.Utils.MyScurity.JWTUtils;
import org.example.takeout.User.DTO.LoginDTO;
import org.example.takeout.User.Entity.User;
import org.example.takeout.User.Mapper.UserMapper;
import org.example.takeout.User.StatusEnum.UserStatusEnum;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserMapper userMapper;
    @Mock
    private JWTUtils jwtUtils;
    @InjectMocks
    private UserService userService;

    @AfterEach
    void clearContext() {
        UserContextHolder.clear();
    }

    @Test
    void loginRejectsDisabledUser() {
        LoginDTO dto = new LoginDTO();
        dto.setUsername("disabled-user");
        dto.setPassword("password123");

        User user = user(7L, UserStatusEnum.DISABLED.getCode());
        user.setUsername(dto.getUsername());
        user.setPassword(BCrypt.encode(dto.getPassword()));
        when(userMapper.selectOne(org.mockito.ArgumentMatchers.any())).thenReturn(user);

        assertThrows(BusinessException.class, () -> userService.login(dto));
        verify(jwtUtils, never()).createToken(anyLong(), anyString());
    }

    @Test
    void requireActiveUserIdRejectsDisabledAccountUsingOldToken() {
        UserContextHolder.setUserId(8L);
        when(userMapper.selectById(8L)).thenReturn(user(8L, UserStatusEnum.DISABLED.getCode()));

        assertThrows(AuthException.class, () -> userService.requireActiveUserId());
    }

    @Test
    void requireActiveUserIdReturnsNormalAccountId() {
        UserContextHolder.setUserId(9L);
        when(userMapper.selectById(9L)).thenReturn(user(9L, UserStatusEnum.NORMAL.getCode()));

        assertEquals(9L, userService.requireActiveUserId());
    }

    private User user(Long id, Integer status) {
        User user = new User();
        user.setId(id);
        user.setStatus(status);
        return user;
    }
}
