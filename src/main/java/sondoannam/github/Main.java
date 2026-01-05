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

    static class UploadRequest {
        String hexData;
        String pin; // PIN để mã hóa ảnh
    }

    static class PinRequest {
        String pin;
    }

    static class UserInfoRequest {
        String pin;
        String fullName;
        String dob;
        String address;
        String phone;
    }

    static class ChangePinRequest {
        String oldPin;
        String newPin;
    }

    static class ChallengeRequest {
        String challenge;
    }

    static class UpdatePointsRequest {
        int points;
    }

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

        server.createContext("/register", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                handleCORS(exchange);
                if ("POST".equals(exchange.getRequestMethod())) {
                    String json = new String(exchange.getRequestBody().readAllBytes());
                    PinRequest req = gson.fromJson(json, PinRequest.class);

                    String result = cardService.registerCard(req.pin);
                    // Result đã là JSON string rồi hoặc Error message
                    // boolean isJson = result.startsWith("{");
                    sendResponse(exchange, result.startsWith("Error") ? 500 : 200, result);
                }
            }
        });

        server.createContext("/verify-pin", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                handleCORS(exchange);
                if ("POST".equals(exchange.getRequestMethod())) {
                    String json = new String(exchange.getRequestBody().readAllBytes());
                    PinRequest req = gson.fromJson(json, PinRequest.class);

                    CardService.PinResponse response = cardService.verifyPin(req.pin);

                    // Trả về JSON full object
                    String jsonRes = gson.toJson(response);
                    // Luôn trả 200 để Client nhận được JSON xử lý logic (trừ khi lỗi mạng sập)
                    sendResponse(exchange, 200, jsonRes);
                }
            }
        });

        server.createContext("/card-id", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                handleCORS(exchange);
                if ("GET".equals(exchange.getRequestMethod())) {
                    String result = cardService.getCardId();
                    sendResponse(exchange, result.startsWith("Error") ? 500 : 200, result);
                }
            }
        });

        // API KÝ CHALLENGE
        server.createContext("/sign-challenge", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                handleCORS(exchange);
                if ("POST".equals(exchange.getRequestMethod())) {
                    // Body: { "challenge": "AABBCC..." }
                    String json = new String(exchange.getRequestBody().readAllBytes());
                    // Tái sử dụng class UploadRequest vì nó cũng có 1 trường string (hoặc tạo class
                    // mới ChallengeRequest)
                    // Ở đây ta giả sử huynh tạo class ChallengeRequest { String challenge; } cho rõ
                    // ràng
                    ChallengeRequest req = gson.fromJson(json, ChallengeRequest.class);

                    String result = cardService.signChallenge(req.challenge);

                    // Xử lý kết quả trả về JSON
                    int status = result.startsWith("Error") ? 400 : 200;
                    sendResponse(exchange, status, result);
                }
            }
        });

        server.createContext("/update-info", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                handleCORS(exchange);
                if ("POST".equals(exchange.getRequestMethod())) {
                    try {
                        String jsonBody = new String(exchange.getRequestBody().readAllBytes()).trim();
                        System.out.println("[INFO] Update Info Request: " + jsonBody);

                        UserInfoRequest req = gson.fromJson(jsonBody, UserInfoRequest.class);

                        // Validate PIN
                        if (req.pin == null || req.pin.isEmpty()) {
                            sendResponse(exchange, 400, "Error: PIN is required");
                            return;
                        }

                        // 1. Ghép chuỗi (Pipe Separated)
                        // Thứ tự phải thống nhất với Applet lúc đọc ra
                        String dataString = req.fullName + "|" + req.dob + "|" + req.address + "|" + req.phone;

                        // 2. Gửi xuống thẻ
                        String result = cardService.updateUserInfo(req.pin, dataString);

                        int status = result.startsWith("Success") ? 200 : 500;
                        sendResponse(exchange, status, result);

                    } catch (Exception e) {
                        e.printStackTrace();
                        sendResponse(exchange, 400, "Error: " + e.getMessage());
                    }
                }
            }
        });

        server.createContext("/update-points", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                handleCORS(exchange);
                if ("POST".equals(exchange.getRequestMethod())) {
                    try {
                        String jsonBody = new String(exchange.getRequestBody().readAllBytes()).trim();
                        UpdatePointsRequest req = gson.fromJson(jsonBody, UpdatePointsRequest.class);

                        String result = cardService.updatePoints(req.points);

                        int status = result.startsWith("Success") ? 200 : 500;
                        sendResponse(exchange, status, result);
                    } catch (Exception e) {
                        sendResponse(exchange, 400, "Error: " + e.getMessage());
                    }
                }
            }
        });

        server.createContext("/get-info-secure", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                handleCORS(exchange);
                if ("POST".equals(exchange.getRequestMethod())) {
                    String json = new String(exchange.getRequestBody().readAllBytes());
                    PinRequest req = gson.fromJson(json, PinRequest.class);

                    String result = cardService.getSecureInfo(req.pin);
                    sendResponse(exchange, result.startsWith("Error") ? 500 : 200, result);
                }
            }
        });

        server.createContext("/upload-image", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                handleCORS(exchange);
                if ("POST".equals(exchange.getRequestMethod())) {
                    String jsonBody = new String(exchange.getRequestBody().readAllBytes()).trim();

                    System.out.println(
                            "[DEBUG] JSON Received: " + jsonBody.substring(0, Math.min(50, jsonBody.length())) + "...");

                    try {
                        // 2. Dùng Gson để bóc tách lấy giá trị "hexData"
                        UploadRequest request = gson.fromJson(jsonBody, UploadRequest.class);
                        String realHexData = request.hexData;

                        if (realHexData == null || realHexData.isEmpty()) {
                            sendResponse(exchange, 400, "Error: hexData field is missing or empty");
                            return;
                        }

                        String pin = request.pin;
                        if (pin == null || pin.isEmpty()) {
                            sendResponse(exchange, 400, "Error: pin field is missing or empty");
                            return;
                        }

                        // Log kiểm tra lại lần cuối
                        System.out.println(
                                "[INFO] Nhận yêu cầu upload ảnh (encrypted). Độ dài Hex: " + realHexData.length());

                        String result = cardService.uploadImageToCard(realHexData, pin);

                        int status = result.startsWith("Success") ? 200 : 500;
                        sendResponse(exchange, status, result);

                    } catch (Exception e) {
                        e.printStackTrace();
                        sendResponse(exchange, 400, "Error parsing JSON: " + e.getMessage());
                    }
                }
            }
        });

        server.createContext("/read-image", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                handleCORS(exchange);
                if ("POST".equals(exchange.getRequestMethod())) {
                    String jsonBody = new String(exchange.getRequestBody().readAllBytes());
                    PinRequest request = gson.fromJson(jsonBody, PinRequest.class);

                    if (request.pin == null || request.pin.isEmpty()) {
                        sendResponse(exchange, 400, "Error: pin field is missing");
                        return;
                    }

                    String result = cardService.readImageFromCard(request.pin);

                    // Nếu thành công trả về Hex, nếu lỗi trả về Error message
                    int status = result.startsWith("Error") ? 500 : 200;
                    sendResponse(exchange, status, result);
                }
            }
        });

        server.createContext("/change-pin", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                handleCORS(exchange);
                if ("POST".equals(exchange.getRequestMethod())) {
                    String json = new String(exchange.getRequestBody().readAllBytes());
                    ChangePinRequest req = gson.fromJson(json, ChangePinRequest.class);

                    if (req.oldPin == null || req.newPin == null) {
                        sendResponse(exchange, 400, "Missing PIN");
                        return;
                    }

                    CardService.PinResponse response = cardService.changePin(req.oldPin, req.newPin);
                    String jsonRes = gson.toJson(response);
                    sendResponse(exchange, 200, jsonRes);
                }
            }
        });

        server.createContext("/unblock-pin", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                handleCORS(exchange);
                if ("POST".equals(exchange.getRequestMethod())) {
                    CardService.PinResponse response = cardService.unblockPin();
                    String jsonRes = gson.toJson(response);
                    sendResponse(exchange, 200, jsonRes);
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
        String finalJson = response;
        if (!response.trim().startsWith("{")) {
            Map<String, String> map = new HashMap<>();
            map.put("result", response);
            finalJson = gson.toJson(map);
        }

        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, finalJson.getBytes().length);
        OutputStream os = exchange.getResponseBody();
        os.write(finalJson.getBytes());
        os.close();
    }
}
