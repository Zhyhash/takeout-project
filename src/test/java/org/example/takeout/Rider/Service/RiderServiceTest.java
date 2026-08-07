package org.example.takeout.Rider.Service;

import org.example.takeout.Common.Auth.AuthRole;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Utils.MyScurity.BCrypt;
import org.example.takeout.Common.Utils.MyScurity.JWTUtils;
import org.example.takeout.Rider.DTO.RiderLoginDTO;
import org.example.takeout.Rider.DTO.RiderRegisterDTO;
import org.example.takeout.Rider.Entity.Rider;
import org.example.takeout.Rider.Enums.RiderStatusEnum;
import org.example.takeout.Rider.Mapper.RiderMapper;
import org.example.takeout.Rider.VO.RiderLoginVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RiderServiceTest {

    @Mock
    private RiderMapper riderMapper;

    @Mock
    private JWTUtils jwtUtils;

    @InjectMocks
    private RiderService riderService;

    @Test
    void registerEncryptsPasswordAndInitializesRider() {
        RiderRegisterDTO dto = registerDTO();
        when(riderMapper.selectOne(any())).thenReturn(null);

        riderService.register(dto);

        ArgumentCaptor<Rider> captor = ArgumentCaptor.forClass(Rider.class);
        verify(riderMapper).insert(captor.capture());
        Rider rider = captor.getValue();
        assertEquals(dto.getName(), rider.getName());
        assertEquals(dto.getPhone(), rider.getPhone());
        assertNotEquals(dto.getPassword(), rider.getPassword());
        assertTrue(BCrypt.matches(dto.getPassword(), rider.getPassword()));
        assertEquals(RiderStatusEnum.NORMAL.getCode(), rider.getStatus());
        assertEquals(0, rider.getIsDelete());
    }

    @Test
    void registerRejectsDuplicateName() {
        RiderRegisterDTO dto = registerDTO();
        when(riderMapper.selectOne(any())).thenReturn(new Rider());

        assertThrows(BusinessException.class, () -> riderService.register(dto));

        verify(riderMapper, never()).insert(any(Rider.class));
    }

    @Test
    void loginReturnsRiderRoleToken() {
        RiderLoginDTO dto = loginDTO();
        Rider rider = rider("password123");
        when(riderMapper.selectOne(any())).thenReturn(rider);
        when(jwtUtils.createToken(301L, AuthRole.RIDER)).thenReturn("rider-token");

        RiderLoginVO result = riderService.login(dto);

        assertEquals(301L, result.getId());
        assertEquals("rider-one", result.getName());
        assertEquals("rider-token", result.getToken());
        verify(jwtUtils).createToken(301L, AuthRole.RIDER);
    }

    @Test
    void loginRejectsWrongPassword() {
        RiderLoginDTO dto = loginDTO();
        when(riderMapper.selectOne(any())).thenReturn(rider("another-password"));

        assertThrows(BusinessException.class, () -> riderService.login(dto));

        verify(jwtUtils, never()).createToken(any(), any());
    }

    @Test
    void loginRejectsDisabledRider() {
        RiderLoginDTO dto = loginDTO();
        Rider rider = rider("password123");
        rider.setStatus(RiderStatusEnum.DISABLED.getCode());
        when(riderMapper.selectOne(any())).thenReturn(rider);

        assertThrows(BusinessException.class, () -> riderService.login(dto));

        verify(jwtUtils, never()).createToken(any(), any());
    }

    private RiderRegisterDTO registerDTO() {
        RiderRegisterDTO dto = new RiderRegisterDTO();
        dto.setName("rider-one");
        dto.setPhone("13800138002");
        dto.setPassword("password123");
        dto.setConfirmPassword("password123");
        return dto;
    }

    private RiderLoginDTO loginDTO() {
        RiderLoginDTO dto = new RiderLoginDTO();
        dto.setName("rider-one");
        dto.setPassword("password123");
        return dto;
    }

    private Rider rider(String rawPassword) {
        Rider rider = new Rider();
        rider.setId(301L);
        rider.setName("rider-one");
        rider.setPassword(BCrypt.encode(rawPassword));
        rider.setStatus(RiderStatusEnum.NORMAL.getCode());
        return rider;
    }
}
