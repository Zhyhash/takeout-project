package org.example.takeout.Merchant.Service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MerchantSseServiceTest {

    private final MerchantSseService service = new MerchantSseService();

    @Test
    void connectionSendsConnectedEventAndNewOrdersReachOnlyTheirMerchant() throws Exception {
        try (MockedConstruction<SseEmitter> construction = mockConstruction(SseEmitter.class)) {
            SseEmitter first = service.connect(201L);
            SseEmitter second = service.connect(202L);

            service.notifyNewOrder(201L);
            service.notifyNewOrder(999L);

            ArgumentCaptor<SseEmitter.SseEventBuilder> firstEvents = eventCaptor();
            verify(first, times(2)).send(firstEvents.capture());
            assertEquals("event:CONNECTED\ndata:connected\n\n", wireFormat(firstEvents.getAllValues().get(0)));
            assertEquals("event:NEW_ORDER\ndata:refresh\n\n", wireFormat(firstEvents.getAllValues().get(1)));
            ArgumentCaptor<SseEmitter.SseEventBuilder> secondEvents = eventCaptor();
            verify(second).send(secondEvents.capture());
            assertEquals("event:CONNECTED\ndata:connected\n\n", wireFormat(secondEvents.getValue()));
            assertEquals(2, construction.constructed().size());
        }
    }

    @Test
    void heartbeatUsesSseCommentsForEveryConnectedMerchant() throws Exception {
        try (MockedConstruction<SseEmitter> ignored = mockConstruction(SseEmitter.class)) {
            SseEmitter first = service.connect(201L);
            SseEmitter second = service.connect(202L);

            service.heartbeat();

            for (SseEmitter emitter : new SseEmitter[]{first, second}) {
                ArgumentCaptor<SseEmitter.SseEventBuilder> events = eventCaptor();
                verify(emitter, times(2)).send(events.capture());
                assertEquals(":heartbeat\n\n", wireFormat(events.getAllValues().get(1)));
            }
        }
    }

    @Test
    void completionRemovesConnectionSoFurtherNotificationsAreIgnored() throws Exception {
        try (MockedConstruction<SseEmitter> ignored = mockConstruction(SseEmitter.class)) {
            SseEmitter emitter = service.connect(201L);

            completionCallback(emitter).run();
            service.notifyNewOrder(201L);
            service.heartbeat();

            verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
        }
    }

    @Test
    void errorCallbackRemovesConnectionSoFurtherNotificationsAreIgnored() throws Exception {
        try (MockedConstruction<SseEmitter> ignored = mockConstruction(SseEmitter.class)) {
            SseEmitter emitter = service.connect(201L);

            errorCallback(emitter).accept(new IOException("client disconnected"));
            service.notifyNewOrder(201L);
            service.heartbeat();

            verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
        }
    }

    @Test
    void reconnectCompletesOldStreamAndItsLateCallbacksPreserveReplacement() throws Exception {
        try (MockedConstruction<SseEmitter> ignored = mockConstruction(SseEmitter.class)) {
            SseEmitter old = service.connect(201L);
            SseEmitter replacement = service.connect(201L);
            assertNotSame(old, replacement);
            verify(old).complete();

            completionCallback(old).run();
            errorCallback(old).accept(new IOException("old stream closed late"));
            service.notifyNewOrder(201L);

            verify(old).send(any(SseEmitter.SseEventBuilder.class));
            verify(replacement, times(2)).send(any(SseEmitter.SseEventBuilder.class));
            verify(replacement, never()).complete();
            verify(replacement, never()).completeWithError(any());
        }
    }

    @Test
    void initialSendFailureRemovesConnectionAndCompletesItWithError() throws Exception {
        IOException failure = new IOException("initial write failed");
        try (MockedConstruction<SseEmitter> construction = mockConstruction(SseEmitter.class,
                (emitter, context) -> doThrow(failure).when(emitter).send(any(SseEmitter.SseEventBuilder.class)))) {
            SseEmitter emitter = assertDoesNotThrow(() -> service.connect(201L));

            service.notifyNewOrder(201L);
            service.heartbeat();

            assertSame(construction.constructed().get(0), emitter);
            verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
            verify(emitter).completeWithError(failure);
        }
    }

    @Test
    void newOrderSendFailureRemovesConnectionAndCompletesItWithError() throws Exception {
        try (MockedConstruction<SseEmitter> ignored = mockConstruction(SseEmitter.class)) {
            SseEmitter emitter = service.connect(201L);
            IOException failure = new IOException("notification write failed");
            doThrow(failure).when(emitter).send(any(SseEmitter.SseEventBuilder.class));

            assertDoesNotThrow(() -> service.notifyNewOrder(201L));
            service.notifyNewOrder(201L);
            service.heartbeat();

            verify(emitter, times(2)).send(any(SseEmitter.SseEventBuilder.class));
            verify(emitter).completeWithError(failure);
        }
    }

    @Test
    void heartbeatFailureRemovesOnlyFailedConnectionAndKeepsOtherMerchantsAlive() throws Exception {
        try (MockedConstruction<SseEmitter> ignored = mockConstruction(SseEmitter.class)) {
            SseEmitter failed = service.connect(201L);
            SseEmitter healthy = service.connect(202L);
            IOException failure = new IOException("heartbeat write failed");
            doThrow(failure).when(failed).send(any(SseEmitter.SseEventBuilder.class));

            assertDoesNotThrow(service::heartbeat);
            service.heartbeat();
            service.notifyNewOrder(201L);
            service.notifyNewOrder(202L);

            verify(failed, times(2)).send(any(SseEmitter.SseEventBuilder.class));
            verify(failed).completeWithError(failure);
            verify(healthy, times(4)).send(any(SseEmitter.SseEventBuilder.class));
            verify(healthy, never()).completeWithError(any());
        }
    }

    @Test
    void notificationFailureFromReplacedConnectionDoesNotRemoveNewConnection() throws Exception {
        try (MockedConstruction<SseEmitter> construction = mockConstruction(SseEmitter.class)) {
            SseEmitter old = service.connect(201L);
            IOException failure = new IOException("old stream write failed during reconnect");
            doAnswer(invocation -> {
                service.connect(201L);
                throw failure;
            }).when(old).send(any(SseEmitter.SseEventBuilder.class));

            service.notifyNewOrder(201L);
            SseEmitter replacement = construction.constructed().get(1);
            service.notifyNewOrder(201L);

            verify(old).completeWithError(failure);
            verify(replacement, times(2)).send(any(SseEmitter.SseEventBuilder.class));
            verify(replacement, never()).completeWithError(any());
        }
    }

    private static ArgumentCaptor<SseEmitter.SseEventBuilder> eventCaptor() {
        return ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
    }

    private static String wireFormat(SseEmitter.SseEventBuilder event) {
        return event.build().stream()
                .map(ResponseBodyEmitter.DataWithMediaType::getData)
                .map(Object::toString)
                .collect(Collectors.joining());
    }

    private static Runnable completionCallback(SseEmitter emitter) {
        ArgumentCaptor<Runnable> callback = ArgumentCaptor.forClass(Runnable.class);
        verify(emitter).onCompletion(callback.capture());
        return callback.getValue();
    }

    private static Consumer<Throwable> errorCallback(SseEmitter emitter) {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Consumer<Throwable>> callback = ArgumentCaptor.forClass(Consumer.class);
        verify(emitter).onError(callback.capture());
        return callback.getValue();
    }
}
