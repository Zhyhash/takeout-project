package org.example.takeout.User.Service;

import org.example.takeout.CartHeader.Manager.CartHeaderManager;
import org.example.takeout.Common.Auth.AuthRole;
import org.example.takeout.Common.Auth.LoginAttemptLimiter;
import org.example.takeout.Common.Exception.AuthException;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Exception.LoginRateLimitException;
import org.example.takeout.Common.Utils.Context.UserContextHolder;
import org.example.takeout.Common.Utils.MyScurity.BCrypt;
import org.example.takeout.Common.Utils.MyScurity.JWTUtils;
import org.example.takeout.User.DTO.LoginDTO;
import org.example.takeout.User.DTO.RegisterDTO;
import org.example.takeout.User.Entity.User;
import org.example.takeout.User.Mapper.UserMapper;
import org.example.takeout.User.StatusEnum.UserStatusEnum;
import org.example.takeout.User.VO.LoginVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserMapper userMapper;
    @Mock
    private JWTUtils jwtUtils;
    @Mock
    private LoginAttemptLimiter loginAttemptLimiter;
    @Mock
    private CartHeaderManager cartHeaderManager;
    @InjectMocks
    private UserService userService;

    @AfterEach
    void clearContext() {
        UserContextHolder.clear();
    }

    @Test
    void registerInitializesAccountAndCreatesCartHeaderWithGeneratedUserId() {
        RegisterDTO dto = registerDTO();
        when(userMapper.selectList(any())).thenReturn(List.of());
        when(userMapper.insert(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(21L);
            return 1;
        });

        userService.register(dto);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        var insertionOrder = inOrder(userMapper, cartHeaderManager);
        insertionOrder.verify(userMapper).insert(userCaptor.capture());
        insertionOrder.verify(cartHeaderManager).insertCartHeader(21L);
        User user = userCaptor.getValue();
        assertEquals(dto.getUsername(), user.getUsername());
        assertEquals(dto.getPhone(), user.getPhone());
        assertEquals(UserStatusEnum.NORMAL.getCode(), user.getStatus());
        assertTrue(BCrypt.matches(dto.getPassword(), user.getPassword()));
    }

    @Test
    void registerRejectsDuplicateUsernameBeforeCreatingAccountOrCartHeader() {
        RegisterDTO dto = registerDTO();
        User conflict = new User();
        conflict.setUsername(dto.getUsername());
        conflict.setPhone("13800138009");
        when(userMapper.selectList(any())).thenReturn(List.of(conflict));

        BusinessException exception = assertThrows(BusinessException.class, () -> userService.register(dto));

        assertEquals("用户名已经存在", exception.getMessage());
        verify(userMapper, never()).insert(any(User.class));
        verifyNoInteractions(cartHeaderManager);
    }

    @Test
    void registerRejectsDuplicatePhoneBeforeCreatingAccountOrCartHeader() {
        RegisterDTO dto = registerDTO();
        User conflict = new User();
        conflict.setUsername("another-user");
        conflict.setPhone(dto.getPhone());
        when(userMapper.selectList(any())).thenReturn(List.of(conflict));

        BusinessException exception = assertThrows(BusinessException.class, () -> userService.register(dto));

        assertEquals("手机号已经存在", exception.getMessage());
        verify(userMapper, never()).insert(any(User.class));
        verifyNoInteractions(cartHeaderManager);
    }

    @Test
    void requireActiveUserIdRejectsMissingIdentityBeforeQueryingDatabase() {
        assertThrows(AuthException.class, () -> userService.requireActiveUserId());
        verifyNoInteractions(userMapper);
    }

    @Test
    void requireActiveUserIdRejectsDeletedAccountUsingOldToken() {
        UserContextHolder.setUserId(8L);
        when(userMapper.selectById(8L)).thenReturn(null);

        assertThrows(AuthException.class, () -> userService.requireActiveUserId());
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
        verify(loginAttemptLimiter).clearFailures(AuthRole.USER, dto.getUsername());
        verify(loginAttemptLimiter, never()).recordFailure(anyString(), anyString());
    }

    @Test
    void loginReturnsTokenAndClearsCredentialFailures() {
        LoginDTO dto = loginDTO();
        User user = loginUser(dto, UserStatusEnum.NORMAL.getCode());
        when(userMapper.selectOne(org.mockito.ArgumentMatchers.any())).thenReturn(user);
        when(jwtUtils.createToken(7L, AuthRole.USER)).thenReturn("user-token");

        LoginVO result = userService.login(dto);

        assertEquals(7L, result.getId());
        assertEquals("user-token", result.getToken());
        verify(loginAttemptLimiter).checkAllowed(AuthRole.USER, dto.getUsername());
        verify(loginAttemptLimiter).clearFailures(AuthRole.USER, dto.getUsername());
        verify(loginAttemptLimiter, never()).recordFailure(anyString(), anyString());
    }

    @Test
    void loginRecordsFailureWhenUserDoesNotExist() {
        LoginDTO dto = loginDTO();
        when(userMapper.selectOne(org.mockito.ArgumentMatchers.any())).thenReturn(null);

        assertThrows(BusinessException.class, () -> userService.login(dto));

        verify(loginAttemptLimiter).recordFailure(AuthRole.USER, dto.getUsername());
        verify(loginAttemptLimiter, never()).clearFailures(anyString(), anyString());
        verifyNoInteractions(jwtUtils);
    }

    @Test
    void loginRecordsFailureWhenPasswordIsWrong() {
        LoginDTO dto = loginDTO();
        User user = loginUser(dto, UserStatusEnum.NORMAL.getCode());
        user.setPassword(BCrypt.encode("another-password"));
        when(userMapper.selectOne(org.mockito.ArgumentMatchers.any())).thenReturn(user);

        assertThrows(BusinessException.class, () -> userService.login(dto));

        verify(loginAttemptLimiter).recordFailure(AuthRole.USER, dto.getUsername());
        verify(loginAttemptLimiter, never()).clearFailures(anyString(), anyString());
        verifyNoInteractions(jwtUtils);
    }

    @Test
    void loginClearsCredentialFailuresEvenWhenTokenCreationFails() {
        LoginDTO dto = loginDTO();
        User user = loginUser(dto, UserStatusEnum.NORMAL.getCode());
        when(userMapper.selectOne(org.mockito.ArgumentMatchers.any())).thenReturn(user);
        when(jwtUtils.createToken(7L, AuthRole.USER))
                .thenThrow(new IllegalStateException("token signing failed"));

        assertThrows(IllegalStateException.class, () -> userService.login(dto));

        verify(loginAttemptLimiter).clearFailures(AuthRole.USER, dto.getUsername());
        verify(loginAttemptLimiter, never()).recordFailure(anyString(), anyString());
    }

    @Test
    void loginStopsBeforeDatabaseWhenRateLimited() {
        LoginDTO dto = loginDTO();
        doThrow(new LoginRateLimitException("请求过于频繁"))
                .when(loginAttemptLimiter)
                .checkAllowed(AuthRole.USER, dto.getUsername());

        assertThrows(LoginRateLimitException.class, () -> userService.login(dto));

        verifyNoInteractions(userMapper, jwtUtils);
        verify(loginAttemptLimiter, never()).recordFailure(anyString(), anyString());
        verify(loginAttemptLimiter, never()).clearFailures(anyString(), anyString());
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

    private LoginDTO loginDTO() {
        LoginDTO dto = new LoginDTO();
        dto.setUsername("user-one");
        dto.setPassword("password123");
        return dto;
    }

    private RegisterDTO registerDTO() {
        RegisterDTO dto = new RegisterDTO();
        dto.setUsername("user-one");
        dto.setPhone("13800138003");
        dto.setPassword("password123");
        dto.setConfirmPassword("password123");
        return dto;
    }

    private User loginUser(LoginDTO dto, Integer status) {
        User user = user(7L, status);
        user.setUsername(dto.getUsername());
        user.setPassword(BCrypt.encode(dto.getPassword()));
        user.setNickname("user-one");
        return user;
    }
}
