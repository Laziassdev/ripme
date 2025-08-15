package com.rarchives.ripme.tst.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.InetSocketAddress;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

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
}
