package com.pantrypilot.desktop;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DashboardClientTest {
    @Test
    void dashboardReadsEveryViewForSelectedMemberAndPropagatesDeniedAccess() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var paths = new ArrayList<String>();
        server.createContext("/", exchange -> {
            synchronized (paths) { paths.add(exchange.getRequestURI().getPath()); }
            boolean permitted = exchange.getRequestURI().getQuery().contains("userId=17");
            byte[] body = (exchange.getRequestURI().getPath().endsWith("analytics") ? "{}" : "[]")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(permitted ? 200 : 403, body.length);
            try (var os = exchange.getResponseBody()) { os.write(body); }
        });
        server.start();
        try {
            var api = new DashboardClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
            var data = api.load(8, 17);
            assertTrue(data.analytics().isObject());
            assertTrue(data.shopping().isArray());
            assertTrue(paths.containsAll(List.of("/api/insights/households/8/analytics",
                    "/api/insights/households/8/shopping-list", "/api/core/households/8/expiring",
                    "/api/insights/households/8/history", "/api/insights/households/8/cook-now")));
            assertThrows(java.io.IOException.class, () -> api.load(8, 19));
        } finally {
            server.stop(0);
        }
    }
}
