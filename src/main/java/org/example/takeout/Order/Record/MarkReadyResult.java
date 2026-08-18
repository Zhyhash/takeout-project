package org.example.takeout.Order.Record;

import org.example.takeout.Order.Entity.Order;

public record MarkReadyResult(boolean changed, Order order) {


}
