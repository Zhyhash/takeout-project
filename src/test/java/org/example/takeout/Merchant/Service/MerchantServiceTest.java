package org.example.takeout.Merchant.Service;

import org.example.takeout.Category.Service.CategoryCommandService;
import org.example.takeout.Common.Auth.AuthRole;
import org.example.takeout.Common.Auth.LoginAttemptLimiter;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Exception.LoginRateLimitException;
import org.example.takeout.Common.Result.ResultCodeEnum;
import org.example.takeout.Common.Utils.MyScurity.BCrypt;
import org.example.takeout.Common.Utils.MyScurity.JWTUtils;
import org.example.takeout.DeliveryTask.Service.DeliveryTaskService;
import org.example.takeout.Merchant.DTO.MerchantLoginDTO;
import org.example.takeout.Merchant.DTO.MerchantRegisterDTO;
import org.example.takeout.Merchant.Entity.Merchant;
import org.example.takeout.Merchant.Enums.MerchantStatusEnum;
import org.example.takeout.Merchant.Mapper.MerchantConverter;
import org.example.takeout.Merchant.Mapper.MerchantMapper;
import org.example.takeout.Merchant.VO.loginVO;
import org.example.takeout.Order.Service.OrderCommandService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MerchantServiceTest {

    @Mock
    private MerchantMapper merchantMapper;
    @Mock
    private MerchantConverter merchantConverter;
    @Mock
    private JWTUtils jwtUtils;
    @Mock
    private CategoryCommandService categoryCommandService;
    @Mock
    private OrderCommandService orderCommandService;
    @Mock
    private DeliveryTaskService deliveryTaskService;
    @Mock
    private LoginAttemptLimiter loginAttemptLimiter;

    @InjectMocks
    private MerchantService merchantService;

    @Test
    void registerInitializesMerchantAndCreatesDefaultCategoryAfterInsert() {
        MerchantRegisterDTO dto = registerDTO();
        Merchant merchant = new Merchant();
        when(merchantMapper.selectList(any())).thenReturn(List.of());
        when(merchantConverter.toMerchant(dto)).thenReturn(merchant);
        when(merchantMapper.insert(merchant)).thenAnswer(invocation -> {
            merchant.setId(201L);
            return 1;
        });

        merchantService.register(dto);

        assertTrue(BCrypt.matches(dto.getPassword(), merchant.getPassword()));
        assertEquals(MerchantStatusEnum.BUSINESS_CLOSED.getCode(), merchant.getStatus());
        var order = inOrder(merchantMapper, categoryCommandService);
        order.verify(merchantMapper).insert(merchant);
        order.verify(categoryCommandService).createDefaultCategory(201L);
    }

    @Test
    void registerRejectsDuplicateUsernameBeforeCreatingMerchantOrCategory() {
        MerchantRegisterDTO dto = registerDTO();
        Merchant conflict = new Merchant();
        conflict.setUsername(dto.getUsername());
        conflict.setPhone("13800138009");
        when(merchantMapper.selectList(any())).thenReturn(List.of(conflict));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> merchantService.register(dto));

        assertEquals("用户名已经存在", exception.getMessage());
        assertEquals(ResultCodeEnum.BUSINESS_ERROR, exception.getCodeEnum());
        verify(merchantMapper, never()).insert(any(Merchant.class));
        verifyNoInteractions(merchantConverter, categoryCommandService);
    }

    @Test
    void registerRejectsDuplicatePhoneBeforeCreatingMerchantOrCategory() {
        MerchantRegisterDTO dto = registerDTO();
        Merchant conflict = new Merchant();
        conflict.setUsername("another-merchant");
        conflict.setPhone(dto.getPhone());
        when(merchantMapper.selectList(any())).thenReturn(List.of(conflict));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> merchantService.register(dto));

        assertEquals("手机号已经存在", exception.getMessage());
        assertEquals(ResultCodeEnum.BUSINESS_ERROR, exception.getCodeEnum());
        verify(merchantMapper, never()).insert(any(Merchant.class));
        verifyNoInteractions(merchantConverter, categoryCommandService);
    }

    @Test
    void registerTranslatesConcurrentDuplicateAndDoesNotCreateDefaultCategory() {
        MerchantRegisterDTO dto = registerDTO();
        Merchant merchant = new Merchant();
        when(merchantMapper.selectList(any())).thenReturn(List.of());
        when(merchantConverter.toMerchant(dto)).thenReturn(merchant);
        when(merchantMapper.insert(merchant)).thenThrow(new DuplicateKeyException("duplicate phone"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> merchantService.register(dto));

        assertEquals("用户名或手机号已经存在", exception.getMessage());
        assertEquals(ResultCodeEnum.BUSINESS_ERROR, exception.getCodeEnum());
        verifyNoInteractions(categoryCommandService);
    }

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

    private MerchantRegisterDTO registerDTO() {
        MerchantRegisterDTO dto = new MerchantRegisterDTO();
        dto.setUsername("merchant-one");
        dto.setMerchantName("测试店铺");
        dto.setPhone("13800138001");
        dto.setPassword("password123");
        dto.setConfirmPassword("password123");
        return dto;
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
