package com.rarchives.ripme.tst.ripper.rippers;

import com.rarchives.ripme.ripper.rippers.RedditRipper;
import org.json.JSONArray;
import org.junit.jupiter.api.Test;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Method;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RedditRipper429Test {
    private static class TestRedditRipper extends RedditRipper {
        public TestRedditRipper(URL url) throws IOException { super(url); }
        @Override public boolean canRip(URL url) { return true; }
        @Override public URL sanitizeURL(URL url) { return url; }
        @Override public void rip() {}
        @Override public String getHost() { return "test"; }
        @Override public String getGID(URL url) { return "test"; }
    }

    @Test
    public void testBackoffOn429() throws Exception {
        AtomicInteger counter = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            int attempt = counter.incrementAndGet();
            if (attempt == 1) {
                exchange.getResponseHeaders().add("Retry-After", "1");
                exchange.sendResponseHeaders(429, -1);
            } else {
                byte[] body = "[{}]".getBytes(StandardCharsets.UTF_8);
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
            TestRedditRipper ripper = new TestRedditRipper(url);
            Method m = RedditRipper.class.getDeclaredMethod("getJsonArrayFromURL", URL.class);
            m.setAccessible(true);
            long start = System.currentTimeMillis();
            JSONArray arr = (JSONArray) m.invoke(ripper, url);
            long elapsed = System.currentTimeMillis() - start;
            assertEquals(2, counter.get());
            assertTrue(elapsed >= 1000);
            assertEquals(1, arr.length());
        } finally {
            server.stop(0);
        }
    }
}
