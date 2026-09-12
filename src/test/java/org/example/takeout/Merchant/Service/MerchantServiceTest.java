package org.example.takeout.Merchant.Service;

import org.example.takeout.Category.Mapper.CategoryMapper;
import org.example.takeout.Common.Auth.AuthRole;
import org.example.takeout.Common.Auth.LoginAttemptLimiter;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Exception.LoginRateLimitException;
import org.example.takeout.Common.Utils.MyScurity.BCrypt;
import org.example.takeout.Common.Utils.MyScurity.JWTUtils;
import org.example.takeout.DeliveryTask.Service.DeliveryTaskService;
import org.example.takeout.Merchant.DTO.MerchantLoginDTO;
import org.example.takeout.Merchant.Entity.Merchant;
import org.example.takeout.Merchant.Mapper.MerchantConverter;
import org.example.takeout.Merchant.Mapper.MerchantMapper;
import org.example.takeout.Merchant.VO.loginVO;
import org.example.takeout.Order.Service.OrderCommandService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MerchantServiceTest {

    @Mock
    private MerchantMapper merchantMapper;
    @Mock
    private MerchantConverter merchantConverter;
    @Mock
    private JWTUtils jwtUtils;
    @Mock
    private CategoryMapper categoryMapper;
    @Mock
    private OrderCommandService orderCommandService;
    @Mock
    private DeliveryTaskService deliveryTaskService;
    @Mock
    private LoginAttemptLimiter loginAttemptLimiter;

    @InjectMocks
    private MerchantService merchantService;

    @Test
    void loginReturnsMerchantRoleTokenAndClearsFailures() {
        MerchantLoginDTO dto = loginDTO();
        Merchant merchant = merchant("password123");
        when(merchantMapper.selectOne(any())).thenReturn(merchant);
        when(jwtUtils.createToken(201L, AuthRole.MERCHANT)).thenReturn("merchant-token");

        loginVO result = merchantService.login(dto);

        assertEquals(201L, result.getId());
        assertEquals("merchant-token", result.getToken());
        verify(loginAttemptLimiter).checkAllowed(AuthRole.MERCHANT, dto.getUsername());
        verify(loginAttemptLimiter).clearFailures(AuthRole.MERCHANT, dto.getUsername());
    }

    @Test
    void loginRecordsFailureWhenMerchantDoesNotExist() {
        MerchantLoginDTO dto = loginDTO();
        when(merchantMapper.selectOne(any())).thenReturn(null);

        assertThrows(BusinessException.class, () -> merchantService.login(dto));

        verify(loginAttemptLimiter).recordFailure(AuthRole.MERCHANT, dto.getUsername());
        verify(jwtUtils, never()).createToken(anyLong(), anyString());
    }

    @Test
    void loginRecordsFailureWhenPasswordIsWrong() {
        MerchantLoginDTO dto = loginDTO();
        when(merchantMapper.selectOne(any())).thenReturn(merchant("another-password"));

        assertThrows(BusinessException.class, () -> merchantService.login(dto));

        verify(loginAttemptLimiter).recordFailure(AuthRole.MERCHANT, dto.getUsername());
        verify(jwtUtils, never()).createToken(anyLong(), anyString());
    }

    @Test
    void loginClearsCredentialFailuresEvenWhenTokenCreationFails() {
        MerchantLoginDTO dto = loginDTO();
        when(merchantMapper.selectOne(any())).thenReturn(merchant("password123"));
        when(jwtUtils.createToken(201L, AuthRole.MERCHANT))
                .thenThrow(new IllegalStateException("token signing failed"));

        assertThrows(IllegalStateException.class, () -> merchantService.login(dto));

        verify(loginAttemptLimiter).clearFailures(AuthRole.MERCHANT, dto.getUsername());
        verify(loginAttemptLimiter, never()).recordFailure(anyString(), anyString());
    }

    @Test
    void loginStopsBeforeDatabaseWhenRateLimited() {
        MerchantLoginDTO dto = loginDTO();
        doThrow(new LoginRateLimitException("请求过于频繁"))
                .when(loginAttemptLimiter)
                .checkAllowed(AuthRole.MERCHANT, dto.getUsername());

        assertThrows(LoginRateLimitException.class, () -> merchantService.login(dto));

        verifyNoInteractions(merchantMapper, jwtUtils);
        verify(loginAttemptLimiter, never()).recordFailure(anyString(), anyString());
        verify(loginAttemptLimiter, never()).clearFailures(anyString(), anyString());
    }

    private MerchantLoginDTO loginDTO() {
        MerchantLoginDTO dto = new MerchantLoginDTO();
        dto.setUsername("merchant-one");
        dto.setPassword("password123");
        return dto;
    }

    private Merchant merchant(String rawPassword) {
        Merchant merchant = new Merchant();
        merchant.setId(201L);
        merchant.setUsername("merchant-one");
        merchant.setPassword(BCrypt.encode(rawPassword));
        return merchant;
    }
}
