package org.example.takeout.api;

import org.example.takeout.Rider.DTO.RiderLoginDTO;
import org.example.takeout.Rider.DTO.RiderRegisterDTO;
import org.example.takeout.Rider.VO.RiderLoginVO;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RiderAuthApiTest extends AbstractMockMvcApiTest {

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
