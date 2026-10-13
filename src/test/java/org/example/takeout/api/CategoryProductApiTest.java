package org.example.takeout.api;

import com.github.pagehelper.PageInfo;
import jakarta.servlet.Filter;
import org.example.takeout.Common.Utils.MyScurity.JWTUtils;
import org.example.takeout.Product.Controller.MerchantProductController;
import org.example.takeout.Product.DTO.CreateProductDTO;
import org.example.takeout.Product.DTO.UpdateProductDTO;
import org.example.takeout.Product.VO.MerchantProductVO;
import org.example.takeout.Product.VO.ProductVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.HandlerMapping;

import java.math.BigDecimal;
import java.net.URI;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.handler;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

class CategoryProductApiTest extends AbstractMockMvcApiTest {

    private final WebApplicationContext webApplicationContext;

    @Autowired
    CategoryProductApiTest(JWTUtils jwtUtils, WebApplicationContext webApplicationContext) {
        super(jwtUtils, webApplicationContext);
        this.webApplicationContext = webApplicationContext;
    }

    @ParameterizedTest
    @ValueSource(strings = {"/category/products/image", "/cat%65gory/products/image"})
    void uploadImageRejectsUserTokenForPlainAndEncodedPaths(String path) throws Exception {
        uploadMockMvcWithSecurity().perform(multipart(URI.create(path))
                        .file(uploadFile())
                        .header("Authorization", userBearer()))
                .andExpect(status().isOk())
                .andExpect(resultCode(UNAUTHORIZED))
                .andExpect(jsonPath("$.message").value("无权访问该资源，请使用商家账号登录"))
                .andExpect(handler().handlerType(MerchantProductController.class))
                .andExpect(handler().methodName("uploadImage"))
                .andExpect(request().attribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,
                        "/category/products/image"));

        verify(productService, never()).uploadImage(any(MultipartFile.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/category/products/image", "/cat%65gory/products/image"})
    void uploadImageRejectsRiderTokenForPlainAndEncodedPaths(String path) throws Exception {
        uploadMockMvcWithSecurity().perform(multipart(URI.create(path))
                        .file(uploadFile())
                        .header("Authorization", riderBearer()))
                .andExpect(status().isOk())
                .andExpect(resultCode(UNAUTHORIZED))
                .andExpect(jsonPath("$.message").value("无权访问该资源，请使用商家账号登录"))
                .andExpect(handler().handlerType(MerchantProductController.class))
                .andExpect(handler().methodName("uploadImage"))
                .andExpect(request().attribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,
                        "/category/products/image"));

        verify(productService, never()).uploadImage(any(MultipartFile.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/category/products/image", "/cat%65gory/products/image"})
    void uploadImageAllowsMerchantTokenForPlainAndEncodedPaths(String path) throws Exception {
        MockMultipartFile file = uploadFile();
        String imageUrl = "/uploads/products/mock-upload.png";
        when(productService.uploadImage(any(MultipartFile.class))).thenReturn(imageUrl);

        uploadMockMvcWithSecurity().perform(multipart(URI.create(path))
                        .file(file)
                        .header("Authorization", merchantBearer()))
                .andExpect(status().isOk())
                .andExpect(resultCode(SUCCESS))
                .andExpect(jsonPath("$.data").value(imageUrl))
                .andExpect(handler().handlerType(MerchantProductController.class))
                .andExpect(handler().methodName("uploadImage"))
                .andExpect(request().attribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,
                        "/category/products/image"));

        verify(productService).uploadImage(file);
        verify(merchantService).requireActiveMerchantId();
    }

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
    void getCategoryByIdRejectsNonPositiveId() throws Exception {
        mockMvc.perform(get("/category/0")
                        .header("Authorization", merchantBearer()))
                .andExpect(status().isOk())
                .andExpect(resultCode(PARAM_ERROR));
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
                .andExpect(resultCode(PARAM_ERROR));
    }

    @Test
    void createCategoryRejectsOverlongCategoryName() throws Exception {
        mockMvc.perform(post("/category")
                        .header("Authorization", merchantBearer())
                        .param("categoryName", "1234567890123456"))
                .andExpect(status().isOk())
                .andExpect(resultCode(PARAM_ERROR));
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
        MerchantProductVO createdProduct = merchantProductVO(301L, "Rice");
        createdProduct.setStatus(1);
        createdProduct.setStatusDesc("已经下架");
        createdProduct.setVersion(0);
        when(productService.createProduct(any(CreateProductDTO.class))).thenReturn(createdProduct);

        mockMvc.perform(post("/category/products")
                        .header("Authorization", merchantBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(dto)))
                .andExpect(status().isOk())
                .andExpect(resultCode(SUCCESS))
                .andExpect(jsonPath("$.data.productName").value("Rice"))
                .andExpect(jsonPath("$.data.status").value(1))
                .andExpect(jsonPath("$.data.statusDesc").value("已经下架"))
                .andExpect(jsonPath("$.data.version").value(0));
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
    void createProductRejectsPriceWithTooManyDecimalPlaces() throws Exception {
        CreateProductDTO dto = productDTO();
        dto.setPrice(new BigDecimal("18.801"));

        mockMvc.perform(post("/category/products")
                        .header("Authorization", merchantBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(dto)))
                .andExpect(status().isOk())
                .andExpect(resultCode(PARAM_ERROR));
    }

    @Test
    void createProductRejectsOverlongImageUrl() throws Exception {
        CreateProductDTO dto = productDTO();
        dto.setImageUrl("x".repeat(256));

        mockMvc.perform(post("/category/products")
                        .header("Authorization", merchantBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(dto)))
                .andExpect(status().isOk())
                .andExpect(resultCode(PARAM_ERROR));
    }

    @Test
    void createProductRejectsNonPositiveCategoryId() throws Exception {
        CreateProductDTO dto = productDTO();
        dto.setCategoryId(0L);

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
        MerchantProductVO listedProduct = merchantProductVO(301L, "Rice");
        listedProduct.setVersion(2);
        when(productService.listProducts(1, 10, null, null))
                .thenReturn(new PageInfo<>(List.of(listedProduct)));

        mockMvc.perform(get("/category/products")
                        .header("Authorization", merchantBearer()))
                .andExpect(status().isOk())
                .andExpect(resultCode(SUCCESS))
                .andExpect(jsonPath("$.data.list[0].productName").value("Rice"))
                .andExpect(jsonPath("$.data.list[0].status").value(0))
                .andExpect(jsonPath("$.data.list[0].statusDesc").value("正在销售"))
                .andExpect(jsonPath("$.data.list[0].version").value(2));
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
    void listProductsRejectsIllegalPageSize() throws Exception {
        mockMvc.perform(get("/category/products")
                        .header("Authorization", merchantBearer())
                        .param("pageSize", "0"))
                .andExpect(status().isOk())
                .andExpect(resultCode(PARAM_ERROR));
    }

    @Test
    void listProductsRejectsNonPositiveCategoryId() throws Exception {
        mockMvc.perform(get("/category/products")
                        .header("Authorization", merchantBearer())
                        .param("categoryId", "0"))
                .andExpect(status().isOk())
                .andExpect(resultCode(PARAM_ERROR));
    }

    @Test
    void getProductDetailReturnsProduct() throws Exception {
        ProductVO productVO = new ProductVO();
        productVO.setId(301L);
        productVO.setProductName("Rice");
        productVO.setStatus(0);
        productVO.setStatusDesc("正在销售");
        when(productService.getProductDetail(301L)).thenReturn(productVO);

        mockMvc.perform(get("/category/products/301")
                        .header("Authorization", merchantBearer()))
                .andExpect(status().isOk())
                .andExpect(resultCode(SUCCESS))
                .andExpect(jsonPath("$.data.id").value(301))
                .andExpect(jsonPath("$.data.productName").value("Rice"))
                .andExpect(jsonPath("$.data.status").value(0))
                .andExpect(jsonPath("$.data.statusDesc").value("正在销售"));
    }

    @Test
    void getEditDetailReturnsCurrentVersion() throws Exception {
        MerchantProductVO productVO = merchantProductVO(301L, "Rice");
        productVO.setVersion(4);
        when(productService.getMerchantProductDetail(301L)).thenReturn(productVO);

        mockMvc.perform(get("/category/products/301/edit")
                        .header("Authorization", merchantBearer()))
                .andExpect(status().isOk())
                .andExpect(resultCode(SUCCESS))
                .andExpect(jsonPath("$.data.productName").value("Rice"))
                .andExpect(jsonPath("$.data.version").value(4));
    }

    @Test
    void updateProductReturnsUpdatedProduct() throws Exception {
        UpdateProductDTO dto = updateProductDTO();
        MerchantProductVO updatedProduct = merchantProductVO(301L, "Updated Rice");
        updatedProduct.setVersion(1);
        when(productService.updateProduct(any(Long.class), any(UpdateProductDTO.class)))
                .thenReturn(updatedProduct);

        mockMvc.perform(put("/category/products/301")
                        .header("Authorization", merchantBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(dto)))
                .andExpect(status().isOk())
                .andExpect(resultCode(SUCCESS))
                .andExpect(jsonPath("$.data.productName").value("Updated Rice"))
                .andExpect(jsonPath("$.data.version").value(1));
    }

    @Test
    void updateProductRejectsMissingVersion() throws Exception {
        UpdateProductDTO dto = updateProductDTO();
        dto.setVersion(null);

        mockMvc.perform(put("/category/products/301")
                        .header("Authorization", merchantBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(dto)))
                .andExpect(status().isOk())
                .andExpect(resultCode(PARAM_ERROR));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    void updateProductRequiresNonblankName(String name) throws Exception {
        UpdateProductDTO dto = updateProductDTO();
        dto.setProductName(name);

        mockMvc.perform(put("/category/products/301")
                        .header("Authorization", merchantBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(dto)))
                .andExpect(status().isOk())
                .andExpect(resultCode(PARAM_ERROR));

        verifyNoInteractions(productService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "18.805", "99999.99"})
    void updateProductAcceptsIntentionalPriceRules(String price) throws Exception {
        UpdateProductDTO dto = updateProductDTO();
        dto.setPrice(new BigDecimal(price));
        when(productService.updateProduct(any(Long.class), any(UpdateProductDTO.class)))
                .thenReturn(merchantProductVO(301L, "Updated Rice"));

        mockMvc.perform(put("/category/products/301")
                        .header("Authorization", merchantBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(dto)))
                .andExpect(status().isOk())
                .andExpect(resultCode(SUCCESS));

        verify(productService).updateProduct(eq(301L), argThat(update ->
                update.getPrice().compareTo(new BigDecimal(price)) == 0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"-0.001", "100000"})
    void updateProductRejectsPriceOutsideBusinessBounds(String price) throws Exception {
        UpdateProductDTO dto = updateProductDTO();
        dto.setPrice(new BigDecimal(price));

        mockMvc.perform(put("/category/products/301")
                        .header("Authorization", merchantBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(dto)))
                .andExpect(status().isOk())
                .andExpect(resultCode(PARAM_ERROR));

        verifyNoInteractions(productService);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 10000})
    void updateProductAcceptsStockBounds(int stock) throws Exception {
        UpdateProductDTO dto = updateProductDTO();
        dto.setStock(stock);
        when(productService.updateProduct(any(Long.class), any(UpdateProductDTO.class)))
                .thenReturn(merchantProductVO(301L, "Updated Rice"));

        mockMvc.perform(put("/category/products/301")
                        .header("Authorization", merchantBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(dto)))
                .andExpect(status().isOk())
                .andExpect(resultCode(SUCCESS));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 10001})
    void updateProductRejectsStockOutsideBusinessBounds(int stock) throws Exception {
        UpdateProductDTO dto = updateProductDTO();
        dto.setStock(stock);

        mockMvc.perform(put("/category/products/301")
                        .header("Authorization", merchantBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(dto)))
                .andExpect(status().isOk())
                .andExpect(resultCode(PARAM_ERROR));

        verifyNoInteractions(productService);
    }

    @Test
    void defaultProductImageIsServedAnonymouslyAsSvg() throws Exception {
        mockMvc.perform(get("/images/default-product.svg"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("image/svg+xml"));
    }

    @Test
    void deleteProductReturnsSuccess() throws Exception {
        mockMvc.perform(delete("/category/products/301")
                        .header("Authorization", merchantBearer()))
                .andExpect(status().isOk())
                .andExpect(resultCode(SUCCESS))
                .andExpect(jsonPath("$.data").value("删除成功"));

        verify(productService).deleteProduct(301L);
    }

    @Test
    void deleteProductReturnsBusinessErrorWhenMissing() throws Exception {
        doThrow(businessError("product missing")).when(productService).deleteProduct(999L);

        mockMvc.perform(delete("/category/products/999")
                        .header("Authorization", merchantBearer()))
                .andExpect(status().isOk())
                .andExpect(resultCode(BUSINESS_ERROR));
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
        mockMvc.perform(patch("/category/products/0/on-shelf")
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

        verify(productService).restoreProduct(301L);
    }

    @Test
    void restoreProductReturnsBusinessErrorWhenMissing() throws Exception {
        doThrow(businessError("product missing")).when(productService).restoreProduct(999L);

        mockMvc.perform(post("/merchant/restore/999")
                        .header("Authorization", merchantBearer()))
                .andExpect(status().isOk())
                .andExpect(resultCode(BUSINESS_ERROR));
    }

    @Test
    void restoreProductReturnsNameConflictMessage() throws Exception {
        doThrow(businessError("当前店铺已存在同名商品"))
                .when(productService).restoreProduct(301L);

        mockMvc.perform(post("/merchant/restore/301")
                        .header("Authorization", merchantBearer()))
                .andExpect(status().isOk())
                .andExpect(resultCode(BUSINESS_ERROR))
                .andExpect(jsonPath("$.message").value("当前店铺已存在同名商品"));
    }

    @Test
    void restoreProductRejectsNonPositiveId() throws Exception {
        mockMvc.perform(post("/merchant/restore/0")
                        .header("Authorization", merchantBearer()))
                .andExpect(status().isOk())
                .andExpect(resultCode(PARAM_ERROR));
    }

    @Test
    void restoreProductRejectsMissingToken() throws Exception {
        mockMvc.perform(post("/merchant/restore/301"))
                .andExpect(status().isOk())
                .andExpect(resultCode(UNAUTHORIZED));
    }

    @Test
    void restoreProductRejectsUserToken() throws Exception {
        mockMvc.perform(post("/merchant/restore/301")
                        .header("Authorization", userBearer()))
                .andExpect(status().isOk())
                .andExpect(resultCode(UNAUTHORIZED));
    }

    private MockMvc uploadMockMvcWithSecurity() {
        // Exercise the real security filter chain and MVC mapping; upload stays mocked.
        return MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(webApplicationContext.getBean("springSecurityFilterChain", Filter.class))
                .build();
    }

    private MockMultipartFile uploadFile() {
        return new MockMultipartFile("file", "test.png", "image/png", new byte[]{1, 2, 3});
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

    private UpdateProductDTO updateProductDTO() {
        UpdateProductDTO dto = new UpdateProductDTO();
        dto.setProductName("Updated Rice");
        dto.setPrice(new BigDecimal("20.80"));
        dto.setVersion(0);
        return dto;
    }
}
