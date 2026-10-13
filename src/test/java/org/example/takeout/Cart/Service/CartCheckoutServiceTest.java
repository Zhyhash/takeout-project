package org.example.takeout.Cart.Service;

import org.example.takeout.Cart.Domain.CartAvailableResult;
import org.example.takeout.Cart.Entity.CartItem;
import org.example.takeout.Cart.Mapper.CartMapper;
import org.example.takeout.CartHeader.Entity.CartHeader;
import org.example.takeout.CartHeader.Manager.CartHeaderManager;
import org.example.takeout.Common.Constants.DeleteConstant;
import org.example.takeout.Merchant.Entity.Merchant;
import org.example.takeout.Merchant.Enums.MerchantStatusEnum;
import org.example.takeout.Merchant.Service.MerchantQueryService;
import org.example.takeout.Product.Entity.Product;
import org.example.takeout.Product.Service.ProductQueryService;
import org.example.takeout.Product.StatesEnum.ProductStatusEnum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CartCheckoutServiceTest {
    @Mock private MerchantQueryService merchantQueryService;
    @Mock private CartMapper cartMapper;
    @Mock private ProductQueryService productQueryService;
    @Mock private CartHeaderManager cartHeaderManager;
    @InjectMocks private CartCheckoutService checkoutService;

    @Test
    void checkoutReturnsHeaderMerchantAndFiltersUnavailableProductsWithoutDeletingCart() {
        CartItem available = cartItem(301L);
        CartItem deleted = cartItem(302L);
        CartItem offSale = cartItem(303L);
        CartItem missing = cartItem(304L);
        List<CartItem> allItems = List.of(available, deleted, offSale, missing);
        Product onSaleProduct = product(301L, ProductStatusEnum.ON_SALE.getCode(), DeleteConstant.NOT_DELETED);
        Map<Long, Product> products = Map.of(
                301L, onSaleProduct,
                302L, product(302L, ProductStatusEnum.ON_SALE.getCode(), DeleteConstant.DELETED),
                303L, product(303L, ProductStatusEnum.OFF_SALE.getCode(), DeleteConstant.NOT_DELETED));
        Merchant merchant = merchant(MerchantStatusEnum.BUSINESS_OPEN.getCode());
        when(cartMapper.selectList(any())).thenReturn(allItems);
        when(productQueryService.selectProductsByIds(List.of(301L, 302L, 303L, 304L))).thenReturn(products);
        when(cartHeaderManager.getByUserId(101L)).thenReturn(header());
        when(merchantQueryService.selectMerchantById(201L)).thenReturn(merchant);

        CartAvailableResult result = checkoutService.prepareCheckout(101L);

        assertSame(allItems, result.getAllItems());
        assertEquals(List.of(available), result.getAvailableItems());
        assertSame(products, result.getProductMap());
        assertEquals(201L, result.getMerchantId());
        assertEquals("Test merchant", result.getMerchantName());
        verify(cartMapper).selectList(any());
        verifyNoMoreInteractions(cartMapper);
        verify(merchantQueryService).selectMerchantById(201L);
        verifyNoMoreInteractions(merchantQueryService);
        verify(cartHeaderManager).getByUserId(101L);
        verifyNoMoreInteractions(cartHeaderManager);
    }

    @Test
    void closedHeaderMerchantMakesAllItemsUnavailable() {
        CartItem item = cartItem(301L);
        when(cartMapper.selectList(any())).thenReturn(List.of(item));
        when(productQueryService.selectProductsByIds(List.of(301L))).thenReturn(Map.of(
                301L, product(301L, ProductStatusEnum.ON_SALE.getCode(), DeleteConstant.NOT_DELETED)));
        when(cartHeaderManager.getByUserId(101L)).thenReturn(header());
        when(merchantQueryService.selectMerchantById(201L)).thenReturn(
                merchant(MerchantStatusEnum.BUSINESS_CLOSED.getCode()));

        CartAvailableResult result = checkoutService.prepareCheckout(101L);

        assertEquals(List.of(item), result.getAllItems());
        assertTrue(result.getAvailableItems().isEmpty());
    }

    @Test
    void emptyCartDoesNotQueryProductsOrHeaderMerchant() {
        when(cartMapper.selectList(any())).thenReturn(List.of());

        CartAvailableResult result = checkoutService.prepareCheckout(101L);

        assertTrue(result.getAllItems().isEmpty());
        assertTrue(result.getAvailableItems().isEmpty());
        assertNull(result.getMerchantId());
        assertNull(result.getMerchantName());
        verifyNoInteractions(productQueryService, merchantQueryService, cartHeaderManager);
    }

    private CartItem cartItem(Long productId) {
        CartItem item = new CartItem();
        item.setProductId(productId);
        item.setMerchantId(201L);
        item.setQuantity(1);
        return item;
    }

    private Product product(Long id, Integer status, Integer deleted) {
        Product product = new Product();
        product.setId(id);
        product.setMerchantId(201L);
        product.setStatus(status);
        product.setIsDeleted(deleted);
        return product;
    }

    private CartHeader header() {
        CartHeader header = new CartHeader();
        header.setUserId(101L);
        header.setMerchantId(201L);
        return header;
    }

    private Merchant merchant(Integer status) {
        Merchant merchant = new Merchant();
        merchant.setId(201L);
        merchant.setMerchantName("Test merchant");
        merchant.setStatus(status);
        return merchant;
    }
}
