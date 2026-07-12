package org.example.takeout.api;

import com.github.pagehelper.PageInfo;
import org.example.takeout.Cart.DTO.AddCartDTO;
import org.example.takeout.Cart.DTO.UpdateCartDTO;
import org.example.takeout.Order.DTO.CreateOrderDTO;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CartOrderApiTest extends AbstractMockMvcApiTest {

    @Test
    void addCartItemReturnsCartItem() throws Exception {
        AddCartDTO dto = addCartDTO();
        when(cartService.add(any(AddCartDTO.class))).thenReturn(cartVO(401L));

        mockMvc.perform(post("/cart/items")
                        .header("Authorization", userBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(dto)))
                .andExpect(status().isOk())
                .andExpect(resultCode(SUCCESS))
                .andExpect(jsonPath("$.data.id").value(401));
    }

    @Test
    void addCartItemRejectsMissingProductId() throws Exception {
        AddCartDTO dto = new AddCartDTO();

        mockMvc.perform(post("/cart/items")
                        .header("Authorization", userBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(dto)))
                .andExpect(status().isOk())
                .andExpect(resultCode(PARAM_ERROR));
    }

    @Test
    void addCartItemRejectsMissingToken() throws Exception {
        mockMvc.perform(post("/cart/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(addCartDTO())))
                .andExpect(status().isOk())
                .andExpect(resultCode(UNAUTHORIZED));
    }

    @Test
    void addCartItemRejectsInvalidToken() throws Exception {
        mockMvc.perform(post("/cart/items")
                        .header("Authorization", invalidBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(addCartDTO())))
                .andExpect(status().isOk())
                .andExpect(resultCode(UNAUTHORIZED));
    }

    @Test
    void addCartItemRejectsMerchantToken() throws Exception {
        mockMvc.perform(post("/cart/items")
                        .header("Authorization", merchantBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(addCartDTO())))
                .andExpect(status().isOk())
                .andExpect(resultCode(UNAUTHORIZED));
    }

    @Test
    void addCartItemReturnsBusinessErrorWhenProductMissing() throws Exception {
        when(cartService.add(any(AddCartDTO.class))).thenThrow(businessError("product missing"));

        mockMvc.perform(post("/cart/items")
                        .header("Authorization", userBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(addCartDTO())))
                .andExpect(status().isOk())
                .andExpect(resultCode(BUSINESS_ERROR));
    }

    @Test
    void addCartItemRejectsRepeatedOverStockAdd() throws Exception {
        when(cartService.add(any(AddCartDTO.class))).thenThrow(businessError("over stock"));

        mockMvc.perform(post("/cart/items")
                        .header("Authorization", userBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(addCartDTO())))
                .andExpect(status().isOk())
                .andExpect(resultCode(BUSINESS_ERROR));
    }

    @Test
    void listCartItemsReturnsCart() throws Exception {
        when(cartService.list()).thenReturn(cartListVO());

        mockMvc.perform(get("/cart/items")
                        .header("Authorization", userBearer()))
                .andExpect(status().isOk())
                .andExpect(resultCode(SUCCESS))
                .andExpect(jsonPath("$.data.items[0].id").value(401))
                .andExpect(jsonPath("$.data.totalAmount").value(25.00));
    }

    @Test
    void updateCartQuantityReturnsUpdatedCartItem() throws Exception {
        UpdateCartDTO dto = updateCartDTO();
        when(cartService.update(any(UpdateCartDTO.class))).thenReturn(cartVO(401L));

        mockMvc.perform(patch("/cart/items")
                        .header("Authorization", userBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(dto)))
                .andExpect(status().isOk())
                .andExpect(resultCode(SUCCESS))
                .andExpect(jsonPath("$.data.quantity").value(2));
    }

    @Test
    void updateCartQuantityRejectsIllegalQuantityChange() throws Exception {
        UpdateCartDTO dto = updateCartDTO();
        dto.setQuantityChange(2);

        mockMvc.perform(patch("/cart/items")
                        .header("Authorization", userBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(dto)))
                .andExpect(status().isOk())
                .andExpect(resultCode(PARAM_ERROR));
    }

    @Test
    void updateCartQuantityReturnsBusinessErrorWhenItemMissing() throws Exception {
        when(cartService.update(any(UpdateCartDTO.class))).thenThrow(businessError("cart item missing"));

        mockMvc.perform(patch("/cart/items")
                        .header("Authorization", userBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(updateCartDTO())))
                .andExpect(status().isOk())
                .andExpect(resultCode(BUSINESS_ERROR));
    }

    @Test
    void deleteCartItemsReturnsSuccess() throws Exception {
        mockMvc.perform(delete("/cart/items")
                        .header("Authorization", userBearer())
                        .param("ids", "401", "402"))
                .andExpect(status().isOk())
                .andExpect(resultCode(SUCCESS))
                .andExpect(jsonPath("$.data").value("success"));
    }

    @Test
    void deleteCartItemsRejectsMissingIds() throws Exception {
        mockMvc.perform(delete("/cart/items")
                        .header("Authorization", userBearer()))
                .andExpect(status().isOk())
                .andExpect(resultCode(UNKNOWN_ERROR));
    }

    @Test
    void clearCartReturnsSuccess() throws Exception {
        mockMvc.perform(delete("/cart/items/all")
                        .header("Authorization", userBearer()))
                .andExpect(status().isOk())
                .andExpect(resultCode(SUCCESS));
    }

    @Test
    void createOrderReturnsOrderNo() throws Exception {
        when(orderService.createOrder(any(CreateOrderDTO.class))).thenReturn(createOrderVO());

        mockMvc.perform(post("/order")
                        .header("Authorization", userBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(orderDTO())))
                .andExpect(status().isOk())
                .andExpect(resultCode(SUCCESS))
                .andExpect(jsonPath("$.data.orderId").value(501))
                .andExpect(jsonPath("$.data.orderNo").value("ORD-TEST-001"));
    }

    @Test
    void createOrderRejectsBlankReceiverName() throws Exception {
        CreateOrderDTO dto = orderDTO();
        dto.setReceiverName("");

        mockMvc.perform(post("/order")
                        .header("Authorization", userBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(dto)))
                .andExpect(status().isOk())
                .andExpect(resultCode(PARAM_ERROR));
    }

    @Test
    void createOrderRejectsInvalidPhone() throws Exception {
        CreateOrderDTO dto = orderDTO();
        dto.setReceiverPhone("12345");

        mockMvc.perform(post("/order")
                        .header("Authorization", userBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(dto)))
                .andExpect(status().isOk())
                .andExpect(resultCode(PARAM_ERROR));
    }

    @Test
    void createOrderRejectsMissingToken() throws Exception {
        mockMvc.perform(post("/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(orderDTO())))
                .andExpect(status().isOk())
                .andExpect(resultCode(UNAUTHORIZED));
    }

    @Test
    void createOrderReturnsBusinessErrorWhenCartEmpty() throws Exception {
        when(orderService.createOrder(any(CreateOrderDTO.class))).thenThrow(businessError("cart empty"));

        mockMvc.perform(post("/order")
                        .header("Authorization", userBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(orderDTO())))
                .andExpect(status().isOk())
                .andExpect(resultCode(BUSINESS_ERROR));
    }

    @Test
    void listOrdersReturnsPage() throws Exception {
        when(orderService.listOrders(1, 10)).thenReturn(new PageInfo<>(List.of(orderVO(501L))));

        mockMvc.perform(get("/order")
                        .header("Authorization", userBearer()))
                .andExpect(status().isOk())
                .andExpect(resultCode(SUCCESS))
                .andExpect(jsonPath("$.data.list[0].id").value(501));
    }

    @Test
    void listOrdersRejectsIllegalPageSize() throws Exception {
        mockMvc.perform(get("/order")
                        .header("Authorization", userBearer())
                        .param("pageSize", "101"))
                .andExpect(status().isOk())
                .andExpect(resultCode(UNKNOWN_ERROR));
    }

    @Test
    void getOrderDetailReturnsDetail() throws Exception {
        when(orderService.searchOrderDetailById(501L)).thenReturn(orderDetailVO(501L));

        mockMvc.perform(get("/order/501")
                        .header("Authorization", userBearer()))
                .andExpect(status().isOk())
                .andExpect(resultCode(SUCCESS))
                .andExpect(jsonPath("$.data.id").value(501));
    }

    @Test
    void getOrderDetailReturnsBusinessErrorWhenMissing() throws Exception {
        when(orderService.searchOrderDetailById(999L)).thenThrow(businessError("order missing"));

        mockMvc.perform(get("/order/999")
                        .header("Authorization", userBearer()))
                .andExpect(status().isOk())
                .andExpect(resultCode(BUSINESS_ERROR));
    }

    @Test
    void cancelOrderReturnsSuccess() throws Exception {
        mockMvc.perform(patch("/order/501/cancel")
                        .header("Authorization", userBearer()))
                .andExpect(status().isOk())
                .andExpect(resultCode(SUCCESS));
    }

    @Test
    void cancelOrderRejectsRepeatedSubmit() throws Exception {
        doThrow(businessError("status mismatch")).when(orderService).cancelOrder(501L);

        mockMvc.perform(patch("/order/501/cancel")
                        .header("Authorization", userBearer()))
                .andExpect(status().isOk())
                .andExpect(resultCode(BUSINESS_ERROR));
    }

    @Test
    void payOrderReturnsSuccess() throws Exception {
        mockMvc.perform(patch("/order/501/pay")
                        .header("Authorization", userBearer()))
                .andExpect(status().isOk())
                .andExpect(resultCode(SUCCESS));
    }

    @Test
    void payOrderRejectsRepeatedSubmit() throws Exception {
        doThrow(businessError("already paid")).when(orderService).payOrder(501L);

        mockMvc.perform(patch("/order/501/pay")
                        .header("Authorization", userBearer()))
                .andExpect(status().isOk())
                .andExpect(resultCode(BUSINESS_ERROR));
    }

    @Test
    void confirmOrderReturnsSuccess() throws Exception {
        mockMvc.perform(patch("/order/501/confirm")
                        .header("Authorization", userBearer()))
                .andExpect(status().isOk())
                .andExpect(resultCode(SUCCESS));
    }

    @Test
    void confirmOrderRejectsRepeatedSubmit() throws Exception {
        doThrow(businessError("status mismatch")).when(orderService).CheckedOrder(501L);

        mockMvc.perform(patch("/order/501/confirm")
                        .header("Authorization", userBearer()))
                .andExpect(status().isOk())
                .andExpect(resultCode(BUSINESS_ERROR));
    }

    private AddCartDTO addCartDTO() {
        AddCartDTO dto = new AddCartDTO();
        dto.setProductId(301L);
        return dto;
    }

    private UpdateCartDTO updateCartDTO() {
        UpdateCartDTO dto = new UpdateCartDTO();
        dto.setCartItemId(401L);
        dto.setQuantityChange(1);
        return dto;
    }

    private CreateOrderDTO orderDTO() {
        CreateOrderDTO dto = new CreateOrderDTO();
        dto.setReceiverName("Tester");
        dto.setReceiverPhone("13800138000");
        dto.setReceiverAddress("No.1 Test Road");
        dto.setRemark("no spice");
        return dto;
    }
}
