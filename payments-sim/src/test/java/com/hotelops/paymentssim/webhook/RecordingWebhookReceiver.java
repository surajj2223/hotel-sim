package com.hotelops.paymentssim.webhook;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Minimal in-process stand-in for {@code core-api}'s {@code /webhooks/psp} receiver,
 * built on the JDK {@link HttpServer} (no extra test dependency). Records every
 * delivery (raw body + {@code X-PSP-Signature}) and returns a configurable status so
 * tests can assert delivery, signature validity, and no-retry behaviour.
 */
public final class RecordingWebhookReceiver implements AutoCloseable {

    public record Received(String body, String signature) {}

    private final HttpServer server;
    public final List<Received> received = new CopyOnWriteArrayList<>();
    public final AtomicInteger requestCount = new AtomicInteger();
    private volatile int responseStatus;

    public RecordingWebhookReceiver(int responseStatus) throws IOException {
        this.responseStatus = responseStatus;
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/webhooks/psp", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            String sig = exchange.getRequestHeaders().getFirst(HmacSigner.HEADER_NAME);
            received.add(new Received(new String(body, StandardCharsets.UTF_8), sig));
            requestCount.incrementAndGet();
            exchange.sendResponseHeaders(responseStatus, -1);
            exchange.close();
        });
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/webhooks/psp";
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
