package com.rarchives.ripme.tst.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.HashMap;
import java.util.Map;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.rarchives.ripme.utils.Http;
import com.sun.net.httpserver.HttpServer;

public class HttpTest {

    @Test
    public void testDefaultAcceptHeader() throws Exception {
        List<String> accepts = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            accepts.add(exchange.getRequestHeaders().getFirst("Accept"));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();
        try {
            URL url = new URL("http://localhost:" + server.getAddress().getPort() + "/");
            Http.followRedirectsWithRetry(url, 0, 1, "test-agent");
        } finally {
            server.stop(0);
        }
        assertEquals("*/*", accepts.get(0));
    }

    @Test
    public void testCustomAcceptHeader() throws Exception {
        List<String> accepts = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            accepts.add(exchange.getRequestHeaders().getFirst("Accept"));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();
        try {
            URL url = new URL("http://localhost:" + server.getAddress().getPort() + "/");
            Http.followRedirectsWithRetry(url, 0, 1, "test-agent", "application/json");
        } finally {
            server.stop(0);
        }
        assertEquals("application/json", accepts.get(0));
    }

    @Test
    public void testFollowRedirectsWithRetryCustomHeaders() throws Exception {
        List<String> referers = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            referers.add(exchange.getRequestHeaders().getFirst("Referer"));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();
        try {
            URL url = new URL("http://localhost:" + server.getAddress().getPort() + "/");
            Map<String,String> headers = new HashMap<>();
            headers.put("Referer", "https://example.com/page");
            Http.followRedirectsWithRetry(url, 0, 1, "test-agent", headers);
        } finally {
            server.stop(0);
        }
        assertEquals("https://example.com/page", referers.get(0));
    }

    @Test
    public void testGetWith429RetryDefaultHeaders() throws Exception {
        List<String> accepts = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            accepts.add(exchange.getRequestHeaders().getFirst("Accept"));
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().close();
        });
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();
        try {
            URL url = new URL("http://localhost:" + server.getAddress().getPort() + "/");
            Http.getWith429Retry(url, 0, 1, "test-agent");
        } finally {
            server.stop(0);
        }
        assertEquals("application/json", accepts.get(0));
    }

    @Test
    public void testGetWith429RetryCustomHeaders() throws Exception {
        List<String> accepts = new ArrayList<>();
        List<String> cookies = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            accepts.add(exchange.getRequestHeaders().getFirst("Accept"));
            cookies.add(exchange.getRequestHeaders().getFirst("Cookie"));
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().close();
        });
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();
        try {
            URL url = new URL("http://localhost:" + server.getAddress().getPort() + "/");
            Map<String,String> headers = new HashMap<>();
            headers.put("Accept", "text/css");
            headers.put("Cookie", "foo=bar");
            Http.getWith429Retry(url, 0, 1, "test-agent", headers);
        } finally {
            server.stop(0);
        }
        assertEquals("text/css", accepts.get(0));
        assertEquals("foo=bar", cookies.get(0));
    }

    @Test
    public void testGetWith429RetryBackoff() throws Exception {
        AtomicInteger counter = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            int attempt = counter.incrementAndGet();
            if (attempt == 1) {
                exchange.getResponseHeaders().add("Retry-After", "1");
                exchange.sendResponseHeaders(429, -1);
            } else {
                byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
            }
            exchange.close();
        });
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();
        try {
            URL url = new URL("http://localhost:" + server.getAddress().getPort() + "/");
            long start = System.currentTimeMillis();
            String body = Http.getWith429Retry(url, 1, 1, "test-agent");
            long elapsed = System.currentTimeMillis() - start;
            assertEquals("ok", body);
            assertTrue(elapsed >= 1000);
            assertEquals(2, counter.get());
        } finally {
            server.stop(0);
        }
    }
}
