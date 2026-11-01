package org.example.takeout.Payment;

import org.example.takeout.Common.Auth.AuthPathMatcher;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;

class StripePaymentAuthTest {
    @Test
    void onlyExactWebhookPostAndReadOnlyTestPageArePublic() {
        assertTrue(AuthPathMatcher.isPublicPath(new MockHttpServletRequest("POST", "/webhooks/stripe")));
        assertFalse(AuthPathMatcher.isPublicPath(new MockHttpServletRequest("GET", "/webhooks/stripe")));
        assertFalse(AuthPathMatcher.isPublicPath(new MockHttpServletRequest("POST", "/webhooks/stripe/extra")));
        assertTrue(AuthPathMatcher.isPublicPath(new MockHttpServletRequest("GET", "/stripe-test.html")));
        assertTrue(AuthPathMatcher.isPublicPath(new MockHttpServletRequest("HEAD", "/stripe-test.html")));
        assertFalse(AuthPathMatcher.isPublicPath(new MockHttpServletRequest("POST", "/stripe-test.html")));
    }

    @Test
    void paymentOperationsStillRequireUserAuthentication() {
        for (String path : new String[]{"/order/42/payment", "/order/42/payment/sync"}) {
            assertTrue(AuthPathMatcher.requiresUser(path));
            assertFalse(AuthPathMatcher.isPublicPath(new MockHttpServletRequest("GET", path)));
            assertFalse(AuthPathMatcher.isPublicPath(new MockHttpServletRequest("POST", path)));
        }
    }
}
