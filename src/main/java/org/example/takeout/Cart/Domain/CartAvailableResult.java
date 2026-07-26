package org.example.takeout.Cart.Domain;

import lombok.Data;
import org.example.takeout.Cart.Entity.CartItem;
import org.example.takeout.Merchant.Entity.Merchant;
import org.example.takeout.Product.Entity.Product;

import java.util.Collections;
import java.util.List;
import java.util.Map;
@Data
public class CartAvailableResult {

    private List<CartItem> availableItems= Collections.emptyList();

    private Map<Long, Product> productMap=Collections.emptyMap();

    private Map<Long, Merchant> merchantMap=Collections.emptyMap();

}
