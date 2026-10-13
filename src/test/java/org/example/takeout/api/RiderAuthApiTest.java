package org.example.takeout.api;

import org.example.takeout.Common.Utils.MyScurity.JWTUtils;
import org.example.takeout.Rider.DTO.RiderLoginDTO;
import org.example.takeout.Rider.DTO.RiderRegisterDTO;
import org.example.takeout.Rider.VO.RiderLoginVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.context.WebApplicationContext;

import java.util.stream.Stream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RiderAuthApiTest extends AbstractMockMvcApiTest {

    @Autowired
    RiderAuthApiTest(JWTUtils jwtUtils, WebApplicationContext webApplicationContext) {
        super(jwtUtils, webApplicationContext);
    }

    @ParameterizedTest
    @MethodSource("invalidLoginNames")
    void riderRegisterRejectsIllegalLoginNameBeforeCallingServices(String loginName) throws Exception {
        RiderRegisterDTO dto = riderRegisterDTO();
        dto.setName(loginName);

        assertLoginNameRejected("/rider/register", dto);
    }

    @ParameterizedTest
    @MethodSource("invalidLoginNames")
    void riderLoginRejectsIllegalLoginNameBeforeCallingServices(String loginName) throws Exception {
        RiderLoginDTO dto = riderLoginDTO();
        dto.setName(loginName);

        assertLoginNameRejected("/rider/login", dto);
    }

    @ParameterizedTest
    @MethodSource("legalLoginNames")
    void riderRegisterPreservesLegalLoginName(String loginName) throws Exception {
        RiderRegisterDTO dto = riderRegisterDTO();
        dto.setName(loginName);

        mockMvc.perform(post("/rider/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(dto)))
                .andExpect(status().isOk())
                .andExpect(resultCode(SUCCESS))
                .andExpect(jsonPath("$.data").value("success"));

        verify(riderService).register(argThat(actual -> loginName.equals(actual.getName())));
        verifyNoInteractions(userService, merchantService);
    }

    @ParameterizedTest
    @MethodSource("legalLoginNames")
    void riderLoginPreservesLegalLoginName(String loginName) throws Exception {
        RiderLoginDTO dto = riderLoginDTO();
        dto.setName(loginName);
        RiderLoginVO loginVO = new RiderLoginVO();
        loginVO.setId(301L);
        loginVO.setName(loginName);
        loginVO.setToken("rider-token");
        when(riderService.login(any(RiderLoginDTO.class))).thenReturn(loginVO);

        mockMvc.perform(post("/rider/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(dto)))
                .andExpect(status().isOk())
                .andExpect(resultCode(SUCCESS))
                .andExpect(jsonPath("$.data.id").value(301))
                .andExpect(jsonPath("$.data.name").value(loginName))
                .andExpect(jsonPath("$.data.token").value("rider-token"));

        verify(riderService).login(argThat(actual -> loginName.equals(actual.getName())));
        verifyNoInteractions(userService, merchantService);
    }

    @Test
    void riderRegisterIsPublicAndReturnsSuccess() throws Exception {
        RiderRegisterDTO dto = riderRegisterDTO();

        mockMvc.perform(post("/rider/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(dto)))
                .andExpect(status().isOk())
                .andExpect(resultCode(SUCCESS))
                .andExpect(successMessage())
                .andExpect(jsonPath("$.data").value("success"));

        verify(riderService).register(any(RiderRegisterDTO.class));
    }

    @Test
    void riderRegisterRejectsBlankName() throws Exception {
        RiderRegisterDTO dto = riderRegisterDTO();
        dto.setName("");

        mockMvc.perform(post("/rider/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(dto)))
                .andExpect(status().isOk())
                .andExpect(resultCode(PARAM_ERROR));
    }

    @Test
    void riderRegisterRejectsMismatchedPasswords() throws Exception {
        RiderRegisterDTO dto = riderRegisterDTO();
        dto.setConfirmPassword("different123");

        mockMvc.perform(post("/rider/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(dto)))
                .andExpect(status().isOk())
                .andExpect(resultCode(PARAM_ERROR));
    }

    @Test
    void riderRegisterReturnsBusinessErrorForDuplicateName() throws Exception {
        RiderRegisterDTO dto = riderRegisterDTO();
        doThrow(businessError("rider name exists"))
                .when(riderService).register(any(RiderRegisterDTO.class));

        mockMvc.perform(post("/rider/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(dto)))
                .andExpect(status().isOk())
                .andExpect(resultCode(BUSINESS_ERROR));
    }

    @Test
    void riderLoginIsPublicAndReturnsRiderToken() throws Exception {
        RiderLoginDTO dto = riderLoginDTO();
        RiderLoginVO loginVO = new RiderLoginVO();
        loginVO.setId(301L);
        loginVO.setName("rider-one");
        loginVO.setToken("rider-token");
        when(riderService.login(any(RiderLoginDTO.class))).thenReturn(loginVO);

        mockMvc.perform(post("/rider/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(dto)))
                .andExpect(status().isOk())
                .andExpect(resultCode(SUCCESS))
                .andExpect(jsonPath("$.data.id").value(301))
                .andExpect(jsonPath("$.data.name").value("rider-one"))
                .andExpect(jsonPath("$.data.token").value("rider-token"));
    }

    @Test
    void riderLoginRejectsBlankPassword() throws Exception {
        RiderLoginDTO dto = riderLoginDTO();
        dto.setPassword("");

        mockMvc.perform(post("/rider/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(dto)))
                .andExpect(status().isOk())
                .andExpect(resultCode(PARAM_ERROR));
    }

    @Test
    void riderLoginRejectsWrongCredentials() throws Exception {
        RiderLoginDTO dto = riderLoginDTO();
        when(riderService.login(any(RiderLoginDTO.class)))
                .thenThrow(businessError("bad credentials"));

        mockMvc.perform(post("/rider/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(dto)))
                .andExpect(status().isOk())
                .andExpect(resultCode(BUSINESS_ERROR));
    }

    private static Stream<String> invalidLoginNames() {
        return Stream.of("user\u200B", "Caf\u00E9", "Cafe\u0301", "\u212A", "\uFF2B",
                "\u4E2D\u6587", "user\uD83D\uDE00", "user name", "user.", "user:name",
                "user\n", "user\t", "user\0", "a".repeat(51));
    }

    private static Stream<String> legalLoginNames() {
        return Stream.of("A", "z", "0", "_", "-", "AaZz09_-", "AaZz09_-".repeat(6) + "A0");
    }

    private void assertLoginNameRejected(String path, Object dto) throws Exception {
        mockMvc.perform(post(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(dto)))
                .andExpect(status().isOk())
                .andExpect(resultCode(PARAM_ERROR));

        verifyNoInteractions(userService, merchantService, riderService);
    }

    private RiderRegisterDTO riderRegisterDTO() {
        RiderRegisterDTO dto = new RiderRegisterDTO();
        dto.setName("rider-one");
        dto.setPhone("13800138002");
        dto.setPassword("password123");
        dto.setConfirmPassword("password123");
        return dto;
    }

    private RiderLoginDTO riderLoginDTO() {
        RiderLoginDTO dto = new RiderLoginDTO();
        dto.setName("rider-one");
        dto.setPassword("password123");
        return dto;
    }
}
