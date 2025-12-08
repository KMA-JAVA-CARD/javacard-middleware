package sondoannam.github;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import sondoannam.github.services.CardService;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;

public class Main {
    private static CardService cardService = new CardService();
    private static Gson gson = new Gson();

    public static void main(String[] args) throws IOException {
        int port = 8081;
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        // API 1: Kiểm tra kết nối thẻ
        server.createContext("/connect", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                handleCORS(exchange); // Cho phép Electron gọi
                if ("GET".equals(exchange.getRequestMethod())) {
                    boolean success = cardService.connect();
                    sendResponse(exchange, 200, success ? "Connected" : "Failed");
                }
            }
        });

        // API 2: Gửi lệnh APDU (Electron gửi Hex -> Java gửi thẻ -> Java trả Hex)
        // Đây là API quan trọng nhất, Electron chỉ cần gọi API này là làm chủ được thẻ
        server.createContext("/apdu", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                handleCORS(exchange);
                if ("POST".equals(exchange.getRequestMethod())) {
                    // Đọc body (Hex string) từ Electron
                    String body = new String(exchange.getRequestBody().readAllBytes());
                    // Gửi xuống thẻ
                    String responseHex = cardService.sendAPDU(body.trim());
                    // Trả về cho Electron
                    sendResponse(exchange, 200, responseHex);
                }
            }
        });

        server.createContext("/upload-image", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                handleCORS(exchange);
                if ("POST".equals(exchange.getRequestMethod())) {
                    // 1. Đọc Body (Chuỗi Hex ảnh từ Electron)
                    String hexBody = new String(exchange.getRequestBody().readAllBytes()).trim();

                    // Kiểm tra sơ bộ
                    if (hexBody.isEmpty()) {
                        sendResponse(exchange, 400, "Error: Empty body");
                        return;
                    }

                    System.out.println("[INFO] Nhận yêu cầu upload ảnh. Độ dài Hex: " + hexBody.length());

                    // 2. Gọi hàm xử lý chunking
                    String result = cardService.uploadImageToCard(hexBody);

                    // 3. Trả kết quả
                    sendResponse(exchange, 200, result);
                }
            }
        });

        server.setExecutor(null);
        server.start();
        System.out.println("Java Middleware is running on port " + port);
    }

    private static void handleCORS(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
        if ("OPTIONS".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
        }
    }

    private static void sendResponse(HttpExchange exchange, int statusCode, String response) throws IOException {
        // Trả về JSON đơn giản
        Map<String, String> map = new HashMap<>();
        map.put("result", response);
        String json = gson.toJson(map);

        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, json.getBytes().length);
        OutputStream os = exchange.getResponseBody();
        os.write(json.getBytes());
        os.close();
    }
}
