package org.example.takeout.api;

import com.github.pagehelper.PageInfo;
import org.example.takeout.Product.DTO.CreateProductDTO;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CategoryProductApiTest extends AbstractMockMvcApiTest {

    @Test
    void listCategoriesReturnsCurrentMerchantCategories() throws Exception {
        when(categoryService.listByMerchant()).thenReturn(List.of(categoryVO(1L, "Staple")));

        mockMvc.perform(get("/category")
                        .header("Authorization", merchantBearer()))
                .andExpect(status().isOk())
                .andExpect(resultCode(SUCCESS))
                .andExpect(jsonPath("$.data[0].categoryName").value("Staple"));
    }

    @Test
    void listCategoriesRejectsMissingToken() throws Exception {
        mockMvc.perform(get("/category"))
                .andExpect(status().isOk())
                .andExpect(resultCode(UNAUTHORIZED));
    }

    @Test
    void listCategoriesRejectsUserToken() throws Exception {
        mockMvc.perform(get("/category")
                        .header("Authorization", userBearer()))
                .andExpect(status().isOk())
                .andExpect(resultCode(UNAUTHORIZED));
    }

    @Test
    void getCategoryByIdReturnsCategory() throws Exception {
        when(categoryService.getByIdAndMerchant(1L)).thenReturn(categoryVO(1L, "Staple"));

        mockMvc.perform(get("/category/1")
                        .header("Authorization", merchantBearer()))
                .andExpect(status().isOk())
                .andExpect(resultCode(SUCCESS))
                .andExpect(jsonPath("$.data.id").value(1));
    }

    @Test
    void getCategoryByIdReturnsBusinessErrorWhenMissing() throws Exception {
        when(categoryService.getByIdAndMerchant(999L)).thenThrow(businessError("category missing"));

        mockMvc.perform(get("/category/999")
                        .header("Authorization", merchantBearer()))
                .andExpect(status().isOk())
                .andExpect(resultCode(BUSINESS_ERROR));
    }

    @Test
    void createCategoryReturnsCreatedCategory() throws Exception {
        when(categoryService.createCategory("Drinks")).thenReturn(createCategoryVO(2L, "Drinks"));

        mockMvc.perform(post("/category")
                        .header("Authorization", merchantBearer())
                        .param("categoryName", "Drinks"))
                .andExpect(status().isOk())
                .andExpect(resultCode(SUCCESS))
                .andExpect(jsonPath("$.data.categoryName").value("Drinks"));
    }

    @Test
    void createCategoryRejectsMissingCategoryName() throws Exception {
        mockMvc.perform(post("/category")
                        .header("Authorization", merchantBearer()))
                .andExpect(status().isOk())
                .andExpect(resultCode(UNKNOWN_ERROR));
    }

    @Test
    void createCategoryRejectsDuplicateName() throws Exception {
        when(categoryService.createCategory("Drinks")).thenThrow(businessError("duplicate category"));

        mockMvc.perform(post("/category")
                        .header("Authorization", merchantBearer())
                        .param("categoryName", "Drinks"))
                .andExpect(status().isOk())
                .andExpect(resultCode(BUSINESS_ERROR));
    }

    @Test
    void deleteCategoryReturnsSuccess() throws Exception {
        mockMvc.perform(delete("/category/2")
                        .header("Authorization", merchantBearer()))
                .andExpect(status().isOk())
                .andExpect(resultCode(SUCCESS))
                .andExpect(jsonPath("$.data").value("删除成功"));
    }

    @Test
    void deleteCategoryReturnsBusinessErrorWhenMissing() throws Exception {
        doThrow(businessError("category missing")).when(categoryService).deleteById(999L);

        mockMvc.perform(delete("/category/999")
                        .header("Authorization", merchantBearer()))
                .andExpect(status().isOk())
                .andExpect(resultCode(BUSINESS_ERROR));
    }

    @Test
    void createProductReturnsProduct() throws Exception {
        CreateProductDTO dto = productDTO();
        when(productService.createProduct(any(CreateProductDTO.class))).thenReturn(merchantProductVO(301L, "Rice"));

        mockMvc.perform(post("/category/products")
                        .header("Authorization", merchantBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(dto)))
                .andExpect(status().isOk())
                .andExpect(resultCode(SUCCESS))
                .andExpect(jsonPath("$.data.productName").value("Rice"));
    }

    @Test
    void createProductRejectsBlankName() throws Exception {
        CreateProductDTO dto = productDTO();
        dto.setProductName("");

        mockMvc.perform(post("/category/products")
                        .header("Authorization", merchantBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(dto)))
                .andExpect(status().isOk())
                .andExpect(resultCode(PARAM_ERROR));
    }

    @Test
    void createProductRejectsIllegalPrice() throws Exception {
        CreateProductDTO dto = productDTO();
        dto.setPrice(BigDecimal.ZERO);

        mockMvc.perform(post("/category/products")
                        .header("Authorization", merchantBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(dto)))
                .andExpect(status().isOk())
                .andExpect(resultCode(PARAM_ERROR));
    }

    @Test
    void createProductRejectsMissingToken() throws Exception {
        mockMvc.perform(post("/category/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(productDTO())))
                .andExpect(status().isOk())
                .andExpect(resultCode(UNAUTHORIZED));
    }

    @Test
    void createProductRejectsInvalidToken() throws Exception {
        mockMvc.perform(post("/category/products")
                        .header("Authorization", invalidBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(productDTO())))
                .andExpect(status().isOk())
                .andExpect(resultCode(UNAUTHORIZED));
    }

    @Test
    void createProductRejectsUserToken() throws Exception {
        mockMvc.perform(post("/category/products")
                        .header("Authorization", userBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(productDTO())))
                .andExpect(status().isOk())
                .andExpect(resultCode(UNAUTHORIZED));
    }

    @Test
    void createProductReturnsBusinessErrorWhenCategoryMissing() throws Exception {
        when(productService.createProduct(any(CreateProductDTO.class))).thenThrow(businessError("category missing"));

        mockMvc.perform(post("/category/products")
                        .header("Authorization", merchantBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(productDTO())))
                .andExpect(status().isOk())
                .andExpect(resultCode(BUSINESS_ERROR));
    }

    @Test
    void listProductsReturnsPage() throws Exception {
        when(productService.listProducts(1, 10, null, null))
                .thenReturn(new PageInfo<>(List.of(merchantProductVO(301L, "Rice"))));

        mockMvc.perform(get("/category/products")
                        .header("Authorization", merchantBearer()))
                .andExpect(status().isOk())
                .andExpect(resultCode(SUCCESS))
                .andExpect(jsonPath("$.data.list[0].productName").value("Rice"));
    }

    @Test
    void listProductsRejectsIllegalPageNum() throws Exception {
        mockMvc.perform(get("/category/products")
                        .header("Authorization", merchantBearer())
                        .param("pageNum", "0"))
                .andExpect(status().isOk())
                .andExpect(resultCode(PARAM_ERROR));
    }

    @Test
    void onShelfReturnsProduct() throws Exception {
        when(productService.onShelf(301L)).thenReturn(merchantProductVO(301L, "Rice"));

        mockMvc.perform(patch("/category/products/301/on-shelf")
                        .header("Authorization", merchantBearer()))
                .andExpect(status().isOk())
                .andExpect(resultCode(SUCCESS))
                .andExpect(jsonPath("$.data.id").value(301));
    }

    @Test
    void onShelfRejectsIllegalId() throws Exception {
        mockMvc.perform(patch("/category/products/-1/on-shelf")
                        .header("Authorization", merchantBearer()))
                .andExpect(status().isOk())
                .andExpect(resultCode(PARAM_ERROR));
    }

    @Test
    void offShelfReturnsProduct() throws Exception {
        when(productService.offShelf(301L)).thenReturn(merchantProductVO(301L, "Rice"));

        mockMvc.perform(patch("/category/products/301/off-shelf")
                        .header("Authorization", merchantBearer()))
                .andExpect(status().isOk())
                .andExpect(resultCode(SUCCESS))
                .andExpect(jsonPath("$.data.id").value(301));
    }

    @Test
    void offShelfReturnsBusinessErrorWhenMissing() throws Exception {
        when(productService.offShelf(999L)).thenThrow(businessError("product missing"));

        mockMvc.perform(patch("/category/products/999/off-shelf")
                        .header("Authorization", merchantBearer()))
                .andExpect(status().isOk())
                .andExpect(resultCode(BUSINESS_ERROR));
    }

    @Test
    void restoreProductReturnsSuccess() throws Exception {
        mockMvc.perform(post("/merchant/restore/301")
                        .header("Authorization", merchantBearer()))
                .andExpect(status().isOk())
                .andExpect(resultCode(SUCCESS));
    }

    @Test
    void restoreProductReturnsBusinessErrorWhenMissing() throws Exception {
        doThrow(businessError("product missing")).when(productService).restoreProduct(999L);

        mockMvc.perform(post("/merchant/restore/999")
                        .header("Authorization", merchantBearer()))
                .andExpect(status().isOk())
                .andExpect(resultCode(BUSINESS_ERROR));
    }

    private CreateProductDTO productDTO() {
        CreateProductDTO dto = new CreateProductDTO();
        dto.setProductName("Rice");
        dto.setDescription("fresh");
        dto.setPrice(new BigDecimal("18.80"));
        dto.setStock(20);
        dto.setImageUrl("https://example.test/rice.png");
        dto.setCategoryId(1L);
        return dto;
    }
}
