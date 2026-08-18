package org.example.takeout.api;

import com.github.pagehelper.PageInfo;
import org.example.takeout.Merchant.DTO.MerchantLoginDTO;
import org.example.takeout.Merchant.DTO.MerchantRegisterDTO;
import org.example.takeout.Merchant.DTO.MerchantUpdateDTO;
import org.example.takeout.Merchant.VO.MerchantDetailCategoryVO;
import org.example.takeout.Merchant.VO.MerchantDetailVO;
import org.example.takeout.Merchant.VO.MerchantListVO;
import org.example.takeout.Product.VO.ProductVO;
import org.example.takeout.User.DTO.LoginDTO;
import org.example.takeout.User.DTO.RegisterDTO;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthAndAccountApiTest extends AbstractMockMvcApiTest {

    @Test
    void userRegisterReturnsSuccess() throws Exception {
        RegisterDTO dto = userRegisterDTO();

        mockMvc.perform(post("/user/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(dto)))
                .andExpect(status().isOk())
                .andExpect(resultCode(SUCCESS))
                .andExpect(successMessage())
                .andExpect(jsonPath("$.data").value("success"));
    }

    @Test
    void userRegisterRejectsBlankUsername() throws Exception {
        RegisterDTO dto = userRegisterDTO();
        dto.setUsername("");

        mockMvc.perform(post("/user/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(dto)))
                .andExpect(status().isOk())
                .andExpect(resultCode(PARAM_ERROR));
    }

    @Test
    void userRegisterRejectsInvalidPhone() throws Exception {
        RegisterDTO dto = userRegisterDTO();
        dto.setPhone("12345");

        mockMvc.perform(post("/user/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(dto)))
                .andExpect(status().isOk())
                .andExpect(resultCode(PARAM_ERROR));
    }

    @Test
    void userRegisterRejectsDuplicateUsername() throws Exception {
        RegisterDTO dto = userRegisterDTO();
        doThrow(businessError("username exists")).when(userService).register(any(RegisterDTO.class));

        mockMvc.perform(post("/user/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(dto)))
                .andExpect(status().isOk())
                .andExpect(resultCode(BUSINESS_ERROR));
    }

    @Test
    void userLoginReturnsToken() throws Exception {
        LoginDTO dto = userLoginDTO();
        when(userService.login(any(LoginDTO.class))).thenReturn(userLoginVO());

        mockMvc.perform(post("/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(dto)))
                .andExpect(status().isOk())
                .andExpect(resultCode(SUCCESS))
                .andExpect(jsonPath("$.data.id").value(101))
                .andExpect(jsonPath("$.data.token").value("user-token"));
    }

    @Test
    void userLoginRejectsBlankPassword() throws Exception {
        LoginDTO dto = userLoginDTO();
        dto.setPassword("");

        mockMvc.perform(post("/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(dto)))
                .andExpect(status().isOk())
                .andExpect(resultCode(PARAM_ERROR));
    }

    @Test
    void userLoginRejectsWrongCredentials() throws Exception {
        LoginDTO dto = userLoginDTO();
        when(userService.login(any(LoginDTO.class))).thenThrow(businessError("bad credentials"));

        mockMvc.perform(post("/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(dto)))
                .andExpect(status().isOk())
                .andExpect(resultCode(BUSINESS_ERROR));
    }

    @Test
    void merchantRegisterReturnsSuccess() throws Exception {
        MerchantRegisterDTO dto = merchantRegisterDTO();

        mockMvc.perform(post("/merchant/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(dto)))
                .andExpect(status().isOk())
                .andExpect(resultCode(SUCCESS))
                .andExpect(jsonPath("$.data").value("success"));
    }

    @Test
    void merchantRegisterRejectsMissingMerchantName() throws Exception {
        MerchantRegisterDTO dto = merchantRegisterDTO();
        dto.setMerchantName(null);

        mockMvc.perform(post("/merchant/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(dto)))
                .andExpect(status().isOk())
                .andExpect(resultCode(PARAM_ERROR));
    }

    @Test
    void merchantRegisterRejectsBlankMerchantName() throws Exception {
        MerchantRegisterDTO dto = merchantRegisterDTO();
        dto.setMerchantName("  ");

        mockMvc.perform(post("/merchant/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(dto)))
                .andExpect(status().isOk())
                .andExpect(resultCode(PARAM_ERROR));
    }

    @Test
    void merchantRegisterRejectsDuplicateUsername() throws Exception {
        MerchantRegisterDTO dto = merchantRegisterDTO();
        doThrow(businessError("merchant exists")).when(merchantService).register(any(MerchantRegisterDTO.class));

        mockMvc.perform(post("/merchant/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(dto)))
                .andExpect(status().isOk())
                .andExpect(resultCode(BUSINESS_ERROR));
    }

    @Test
    void merchantLoginReturnsToken() throws Exception {
        MerchantLoginDTO dto = merchantLoginDTO();
        when(merchantService.login(any(MerchantLoginDTO.class))).thenReturn(merchantLoginVO());

        mockMvc.perform(post("/merchant/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(dto)))
                .andExpect(status().isOk())
                .andExpect(resultCode(SUCCESS))
                .andExpect(jsonPath("$.data.id").value(201))
                .andExpect(jsonPath("$.data.token").value("merchant-token"));
    }

    @Test
    void merchantLoginRejectsMissingUsername() throws Exception {
        MerchantLoginDTO dto = merchantLoginDTO();
        dto.setUsername(null);

        mockMvc.perform(post("/merchant/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(dto)))
                .andExpect(status().isOk())
                .andExpect(resultCode(PARAM_ERROR));
    }

    @Test
    void merchantLoginRejectsBlankUsername() throws Exception {
        MerchantLoginDTO dto = merchantLoginDTO();
        dto.setUsername("");

        mockMvc.perform(post("/merchant/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(dto)))
                .andExpect(status().isOk())
                .andExpect(resultCode(PARAM_ERROR));
    }

    @Test
    void merchantLoginRejectsOverlongUsername() throws Exception {
        MerchantLoginDTO dto = merchantLoginDTO();
        dto.setUsername("a".repeat(51));

        mockMvc.perform(post("/merchant/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(dto)))
                .andExpect(status().isOk())
                .andExpect(resultCode(PARAM_ERROR));
    }

    @Test
    void merchantLoginRejectsWrongCredentials() throws Exception {
        MerchantLoginDTO dto = merchantLoginDTO();
        when(merchantService.login(any(MerchantLoginDTO.class))).thenThrow(businessError("bad credentials"));

        mockMvc.perform(post("/merchant/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(dto)))
                .andExpect(status().isOk())
                .andExpect(resultCode(BUSINESS_ERROR));
    }

    @Test
    void updateMerchantInfoReturnsUpdatedInfo() throws Exception {
        MerchantUpdateDTO dto = merchantUpdateDTO();
        when(merchantService.updateMerchant(any(MerchantUpdateDTO.class))).thenReturn(merchantUpdateVO());

        mockMvc.perform(put("/merchant/info")
                        .header("Authorization", merchantBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(dto)))
                .andExpect(status().isOk())
                .andExpect(resultCode(SUCCESS))
                .andExpect(jsonPath("$.data.merchantName").value("Test Shop"))
                .andExpect(jsonPath("$.data.status").value(0))
                .andExpect(jsonPath("$.data.statusDesc").value("店铺正常开启"));
    }

    @Test
    void updateMerchantInfoRejectsEmptyUpdate() throws Exception {
        assertMerchantUpdateRejected("{}");
    }

    @Test
    void updateMerchantInfoRejectsBlankMerchantName() throws Exception {
        assertMerchantUpdateRejected("{\"merchantName\":\" \\t\"}");
    }

    @Test
    void updateMerchantInfoRejectsBlankAddress() throws Exception {
        assertMerchantUpdateRejected("{\"address\":\" \\n\"}");
    }

    @Test
    void updateMerchantInfoRejectsBlankPhone() throws Exception {
        assertMerchantUpdateRejected("{\"phone\":\"   \"}");
    }

    @Test
    void updateMerchantInfoRejectsInvalidPhone() throws Exception {
        assertMerchantUpdateRejected("{\"phone\":\"12345\"}");
    }

    @Test
    void updateMerchantInfoRejectsMissingToken() throws Exception {
        mockMvc.perform(put("/merchant/info")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(merchantUpdateDTO())))
                .andExpect(status().isOk())
                .andExpect(resultCode(UNAUTHORIZED));
    }

    @Test
    void updateMerchantInfoRejectsInvalidToken() throws Exception {
        mockMvc.perform(put("/merchant/info")
                        .header("Authorization", invalidBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(merchantUpdateDTO())))
                .andExpect(status().isOk())
                .andExpect(resultCode(UNAUTHORIZED));
    }

    @Test
    void updateMerchantStatusRejectsIllegalStatus() throws Exception {
        mockMvc.perform(patch("/merchant/info")
                        .header("Authorization", merchantBearer())
                        .param("status", "2"))
                .andExpect(status().isOk())
                .andExpect(resultCode(PARAM_ERROR));
    }

    @Test
    void merchantAcceptOrderReturnsSuccess() throws Exception {
        mockMvc.perform(patch("/merchant/orders/501/accept")
                        .header("Authorization", merchantBearer()))
                .andExpect(status().isOk())
                .andExpect(resultCode(SUCCESS))
                .andExpect(jsonPath("$.data").value("success"));

        verify(merchantService).acceptOrder(501L);
    }

    @Test
    void merchantCompletePreparationReturnsSuccess() throws Exception {
        mockMvc.perform(patch("/merchant/orders/501/ready")
                        .header("Authorization", merchantBearer()))
                .andExpect(status().isOk())
                .andExpect(resultCode(SUCCESS))
                .andExpect(jsonPath("$.data").value("success"));

        verify(merchantService).completePreparation(501L);
    }

    @Test
    void merchantOrderOperationRejectsUserToken() throws Exception {
        mockMvc.perform(patch("/merchant/orders/501/accept")
                        .header("Authorization", userBearer()))
                .andExpect(status().isOk())
                .andExpect(resultCode(UNAUTHORIZED));
    }

    @Test
    void listCustomerShopsIsPublic() throws Exception {
        MerchantListVO shop = new MerchantListVO();
        shop.setId(201L);
        shop.setMerchantName("Test Shop");
        shop.setStatus(0);
        shop.setStatusDesc("店铺正常开启");
        when(merchantQueryService.listMerchants(1, 10, null, null))
                .thenReturn(new PageInfo<>(List.of(shop)));

        mockMvc.perform(get("/api/customer/shops"))
                .andExpect(status().isOk())
                .andExpect(resultCode(SUCCESS))
                .andExpect(jsonPath("$.data.list[0].merchantName").value("Test Shop"))
                .andExpect(jsonPath("$.data.list[0].status").value(0))
                .andExpect(jsonPath("$.data.list[0].statusDesc").value("店铺正常开启"));
    }

    @Test
    void listCustomerShopsRejectsZeroPageSize() throws Exception {
        mockMvc.perform(get("/api/customer/shops")
                        .param("size", "0"))
                .andExpect(status().isOk())
                .andExpect(resultCode(PARAM_ERROR));
    }

    @Test
    void getCustomerShopDetailIsPublic() throws Exception {
        MerchantDetailVO detail = new MerchantDetailVO();
        detail.setId(201L);
        detail.setMerchantName("Test Shop");
        detail.setStatus(0);
        detail.setStatusDesc("店铺正常开启");

        ProductVO product = new ProductVO();
        product.setId(301L);
        product.setProductName("Sold-out Dish");
        product.setStatus(0);
        product.setStatusDesc("正在销售");
        product.setInStock(false);
        MerchantDetailCategoryVO category = new MerchantDetailCategoryVO();
        category.setCategoryId(1L);
        category.setCategoryName("Main");
        category.setProducts(List.of(product));
        detail.setCategories(List.of(category));

        when(merchantQueryService.getMerchantDetailWithGroupedProducts(201L)).thenReturn(detail);

        mockMvc.perform(get("/api/customer/shops/201"))
                .andExpect(status().isOk())
                .andExpect(resultCode(SUCCESS))
                .andExpect(jsonPath("$.data.id").value(201))
                .andExpect(jsonPath("$.data.status").value(0))
                .andExpect(jsonPath("$.data.statusDesc").value("店铺正常开启"))
                .andExpect(jsonPath("$.data.categories[0].products[0].inStock").value(false))
                .andExpect(jsonPath("$.data.categories[0].products[0].stock").doesNotExist());
    }

    @Test
    void getCustomerShopDetailRejectsZeroId() throws Exception {
        mockMvc.perform(get("/api/customer/shops/0"))
                .andExpect(status().isOk())
                .andExpect(resultCode(PARAM_ERROR));
    }

    private RegisterDTO userRegisterDTO() {
        RegisterDTO dto = new RegisterDTO();
        dto.setUsername("tester");
        dto.setPassword("password123");
        dto.setConfirmPassword("password123");
        dto.setPhone("13800138000");
        return dto;
    }

    private LoginDTO userLoginDTO() {
        LoginDTO dto = new LoginDTO();
        dto.setUsername("tester");
        dto.setPassword("password123");
        return dto;
    }

    private MerchantRegisterDTO merchantRegisterDTO() {
        MerchantRegisterDTO dto = new MerchantRegisterDTO();
        dto.setUsername("merchant");
        dto.setPassword("password123");
        dto.setConfirmPassword("password123");
        dto.setMerchantName("Test Shop");
        dto.setPhone("13800138001");
        return dto;
    }

    private MerchantLoginDTO merchantLoginDTO() {
        MerchantLoginDTO dto = new MerchantLoginDTO();
        dto.setUsername("merchant");
        dto.setPassword("password123");
        return dto;
    }

    private MerchantUpdateDTO merchantUpdateDTO() {
        MerchantUpdateDTO dto = new MerchantUpdateDTO();
        dto.setMerchantName("Test Shop");
        dto.setAddress("No.1 Test Road");
        dto.setPhone("13800138000");
        return dto;
    }

    private void assertMerchantUpdateRejected(String content) throws Exception {
        mockMvc.perform(put("/merchant/info")
                        .header("Authorization", merchantBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(content))
                .andExpect(status().isOk())
                .andExpect(resultCode(PARAM_ERROR));

        verify(merchantService, never()).updateMerchant(any(MerchantUpdateDTO.class));
    }
}
