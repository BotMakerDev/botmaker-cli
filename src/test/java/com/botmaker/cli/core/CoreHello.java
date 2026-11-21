// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.cli.core;

import com.botmaker.cli.mcp.McpBridge;
import com.sun.net.httpserver.HttpServer;

import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/** A fake core's {@code /mcp-hello}: the proof that it holds {@code token}, which the cli asks before sending it. */
public final class CoreHello {

    private CoreHello() {
    }

    public static void serve(HttpServer server, String token) {
        server.createContext("/mcp-hello", exchange -> {
            String query = URI.create(exchange.getRequestURI().toString()).getQuery();
            String nonce = query == null ? "" : query.replaceFirst("^nonce=", "");
            byte[] proof = McpBridge.proof(token, exchange.getLocalAddress().getPort(), nonce)
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, proof.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(proof);
            }
        });
    }
}
