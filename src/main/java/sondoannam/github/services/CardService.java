package sondoannam.github.services;

import sondoannam.github.utils.HexUtils;

import javax.smartcardio.*;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class CardService {
    private Card card;
    private CardChannel channel;

    // AID chuẩn (10 bytes)
    private static final byte[] APPLET_AID = HexUtils.hexToBytes("A00000006203010A0100");
    // Kích thước tối đa của dữ liệu trong 1 lệnh APDU (Max 255, ta chọn 240)
    private static final int MAX_APDU_DATA_SIZE = 240;
    // Lệnh ghi ảnh (INS_WRITE_IMAGE)
    private static final int INS_WRITE_IMAGE_INT = 0x10;
    private static final int INS_READ_IMAGE_INT = 0x11;
    private static final int APPLET_MAX_IMAGE_SIZE = 4096;

    private static final int INS_SET_INFO = 0x21; // Lệnh Update Info
    // private static final int INS_GET_INFO = 0x22; // Lệnh Get Info
    private static final int AES_BLOCK_SIZE = 16;

    private static final int INS_REGISTER = 0x01;
    private static final int INS_VERIFY_PIN = 0x02;
    private static final int INS_GET_CARD_ID = 0x06;
    private static final int INS_GET_INFO_SECURE = 0x22;
    private static final byte INS_CHANGE_PIN = (byte) 0x04;
    private static final byte INS_UNBLOCK_PIN = (byte) 0x05;

    private static final int INS_SIGN_CHALLENGE = 0x33;

    private static final int INS_GET_POINTS = 0x40;
    private static final int INS_UPDATE_POINTS = 0x41;

    public static class PinResponse {
        public boolean success;
        public String message;
        public int remainingTries;
        public String sw;

        public PinResponse(boolean success, String message, int remainingTries, String sw) {
            this.success = success;
            this.message = message;
            this.remainingTries = remainingTries;
            this.sw = sw;
        }
    }

    public boolean connect() {
        try {
            TerminalFactory factory = TerminalFactory.getDefault();
            List<CardTerminal> terminals = factory.terminals().list();

            System.out.println("[INFO] Tìm thấy " + terminals.size() + " đầu đọc.");

            for (CardTerminal t : terminals) {
                // Chỉ ưu tiên đầu đọc ảo của JCIDE
                if (t.getName().contains("JAVACOS") || t.getName().contains("Virtual")) {
                    try {
                        if (t.isCardPresent()) {
                            System.out.println("[INFO] Kết nối vào: " + t.getName());
                            card = t.connect("*");
                            channel = card.getBasicChannel();

                            // Tự động Select Applet khi kết nối
                            return selectApplet();
                        }
                    } catch (Exception e) {
                        System.out.println("[WARN] Không thể kết nối đầu đọc này: " + e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    public boolean selectApplet() {
        if (channel == null)
            return false;
        try {
            CommandAPDU selectCmd = new CommandAPDU(0x00, 0xA4, 0x04, 0x00, APPLET_AID);
            ResponseAPDU res = channel.transmit(selectCmd);
            System.out.println("[INFO] Select Applet SW: " + Integer.toHexString(res.getSW()));
            return res.getSW() == 0x9000;
        } catch (CardException e) {
            e.printStackTrace();
            return false;
        }
    }

    public String sendAPDU(String hexAPDU) {
        if (channel == null)
            return "Error: No Connection";
        try {
            byte[] cmdBytes = HexUtils.hexToBytes(hexAPDU);
            CommandAPDU cmd = new CommandAPDU(cmdBytes);
            ResponseAPDU res = channel.transmit(cmd);

            // Trả về: Data (nếu có) + SW (2 bytes cuối)
            byte[] data = res.getData();
            int sw = res.getSW();

            String dataHex = HexUtils.bytesToHex(data);
            String swHex = String.format("%04X", sw);

            return dataHex + swHex;
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    public void disconnect() {
        try {
            if (card != null)
                card.disconnect(false);
            System.out.println("[INFO] Đã ngắt kết nối thẻ.");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // --- ĐĂNG KÝ THẺ (REGISTER) ---
    // Input: PIN
    // Output: JSON String { "cardId": "...", "publicKey": "..." }
    public String registerCard(String pin) {
        if (channel == null)
            return "Error: Card not connected";
        try {
            byte[] pinBytes = pin.getBytes();
            int pinLen = pinBytes.length;

            // Payload: [PIN_LEN] [PIN]
            byte[] payload = new byte[1 + pinLen];
            payload[0] = (byte) pinLen;
            System.arraycopy(pinBytes, 0, payload, 1, pinLen);

            // Gửi lệnh
            CommandAPDU cmd = new CommandAPDU(0xA0, INS_REGISTER, 0x00, 0x00, payload);
            ResponseAPDU res = channel.transmit(cmd);

            if (res.getSW() == 0x9000) {
                byte[] data = res.getData();
                // Parse dữ liệu trả về: [CardID(8)] [LenMod(2)] [Modulus] [LenExp(2)]
                // [Exponent]

                // 1. Lấy CardID
                byte[] idBytes = new byte[8];
                System.arraycopy(data, 0, idBytes, 0, 8);
                String cardId = HexUtils.bytesToHex(idBytes);

                // 2. Lấy Modulus
                int modLenIdx = 8;
                int modLen = ((data[modLenIdx] & 0xFF) << 8) | (data[modLenIdx + 1] & 0xFF);
                byte[] modBytes = new byte[modLen];
                System.arraycopy(data, modLenIdx + 2, modBytes, 0, modLen);
                String modulus = HexUtils.bytesToHex(modBytes);

                // 3. Lấy Exponent
                int expLenIdx = modLenIdx + 2 + modLen;
                int expLen = ((data[expLenIdx] & 0xFF) << 8) | (data[expLenIdx + 1] & 0xFF);
                byte[] expBytes = new byte[expLen];
                System.arraycopy(data, expLenIdx + 2, expBytes, 0, expLen);
                String exponent = HexUtils.bytesToHex(expBytes);

                // Trả về JSON
                return String.format("{\"cardId\":\"%s\", \"modulus\":\"%s\", \"exponent\":\"%s\"}",
                        cardId, modulus, exponent);
            } else {
                return "Error: SW=" + Integer.toHexString(res.getSW());
            }
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    // --- XÁC THỰC PIN (VERIFY) ---
    public PinResponse verifyPin(String pin) {
        if (channel == null)
            return new PinResponse(false, "Card not connected", -1, "");

        try {
            byte[] pinBytes = pin.getBytes();
            CommandAPDU cmd = new CommandAPDU(0xA0, INS_VERIFY_PIN, 0x00, 0x00, pinBytes);
            ResponseAPDU res = channel.transmit(cmd);

            int sw = res.getSW();

            // 1. Thành công
            if (sw == 0x9000) {
                return new PinResponse(true, "Success", 3, "9000");
            }

            // 2. Bị khóa (0x6983 - File Invalid / Blocked)
            if (sw == 0x6983) {
                return new PinResponse(false, "Locked", 0, "6983");
            }

            // 3. Sai PIN (0x63Cx)
            if ((sw & 0xFFF0) == 0x63C0) {
                int tries = sw & 0x0F;
                return new PinResponse(false, "Wrong PIN", tries, Integer.toHexString(sw));
            }

            // Các lỗi khác
            return new PinResponse(false, "Error SW=" + Integer.toHexString(sw), -1, Integer.toHexString(sw));

        } catch (Exception e) {
            return new PinResponse(false, "Exception: " + e.getMessage(), -1, "");
        }
    }

    // --- LẤY CARD ID (PUBLIC) ---
    public String getCardId() {
        if (channel == null)
            return "Error: Card not connected";
        try {
            // Le = 9 (8 ID + 1 Status)
            CommandAPDU cmd = new CommandAPDU(0xA0, INS_GET_CARD_ID, 0x00, 0x00, 9);
            ResponseAPDU res = channel.transmit(cmd);

            if (res.getSW() == 0x9000) {
                byte[] data = res.getData();
                if (data.length < 9)
                    return "Error: Invalid Response Length";

                // 8 Byte đầu là ID
                byte[] idBytes = new byte[8];
                System.arraycopy(data, 0, idBytes, 0, 8);
                String cardId = HexUtils.bytesToHex(idBytes);

                // Byte cuối là Status
                byte status = data[8];

                if (status == 0x01) {
                    return cardId + ".BLOCKED"; // Chiều theo ý huynh!
                } else {
                    return cardId;
                }
            }
            return "Error: SW=" + Integer.toHexString(res.getSW());
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    /**
     * Gửi Challenge xuống thẻ để ký
     *
     * @param challengeHex Chuỗi ngẫu nhiên (Hex) từ Server
     * @return Chữ ký (Signature Hex) hoặc Lỗi
     */
    public String signChallenge(String challengeHex) {
        if (channel == null)
            return "Error: Card not connected";

        try {
            byte[] challengeBytes = HexUtils.hexToBytes(challengeHex);

            // Lệnh SIGN: CLA=A0, INS=33, P1=0, P2=0, Data=Challenge
            CommandAPDU cmd = new CommandAPDU(0xA0, INS_SIGN_CHALLENGE, 0x00, 0x00, challengeBytes);
            ResponseAPDU res = channel.transmit(cmd);

            if (res.getSW() == 0x9000) {
                // Trả về chữ ký (Hex)
                return HexUtils.bytesToHex(res.getData());
            } else if (res.getSW() == 0x6982) { // SW_SECURITY_STATUS_NOT_SATISFIED
                return "Error: PIN Required"; // Chưa nhập PIN mà đòi ký
            } else {
                return "Error: Sign Failed SW=" + Integer.toHexString(res.getSW());
            }

        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    /**
     * Hàm nhận chuỗi Hex ảnh, cắt nhỏ và ghi log ra file
     *
     * @param hexImage Chuỗi Hex dài của ảnh (4096 bytes ~ 8192 ký tự hex)
     * @param pin      PIN để mã hóa ảnh trên thẻ
     * @return Thông báo kết quả
     */
    public String uploadImageToCard(String hexImage, String pin) {
        if (channel == null)
            return "Error: Card not connected";

        byte[] imageData;
        try {
            imageData = HexUtils.hexToBytes(hexImage);
        } catch (IllegalArgumentException e) {
            return "Error: Invalid Hex String";
        }

        int totalBytes = imageData.length;

        if (totalBytes > APPLET_MAX_IMAGE_SIZE) {
            System.out.println("[WARN] Ảnh quá lớn (" + totalBytes + "), đang cắt xuống " + APPLET_MAX_IMAGE_SIZE);
            byte[] trimmedData = new byte[APPLET_MAX_IMAGE_SIZE];
            System.arraycopy(imageData, 0, trimmedData, 0, APPLET_MAX_IMAGE_SIZE);
            imageData = trimmedData;
            totalBytes = APPLET_MAX_IMAGE_SIZE;
        }

        byte[] pinBytes = pin.getBytes();

        String logFileName = "debug_image_chunks.txt";

        try (PrintWriter writer = new PrintWriter(new BufferedWriter(new FileWriter(logFileName, true)))) {
            String timeStamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            writer.println("\n=== REAL UPLOAD TO CARD (ENCRYPTED) - " + timeStamp + " ===");

            int offset = 0;
            int chunkIndex = 0;

            while (offset < totalBytes) {
                // 1. Cắt gói - đảm bảo chia hết cho 16 (AES block)
                int len = Math.min(MAX_APDU_DATA_SIZE - 1 - pinBytes.length, totalBytes - offset);
                // Làm tròn xuống bội của 16
                len = (len / 16) * 16;
                if (len <= 0)
                    len = 16;

                byte[] chunkData = new byte[len];
                System.arraycopy(imageData, offset, chunkData, 0, Math.min(len, totalBytes - offset));

                // 2. Tạo payload: [PIN_LEN][PIN][PADDED_DATA]
                byte[] payload = new byte[1 + pinBytes.length + len];
                payload[0] = (byte) pinBytes.length;
                System.arraycopy(pinBytes, 0, payload, 1, pinBytes.length);
                System.arraycopy(chunkData, 0, payload, 1 + pinBytes.length, len);

                // 3. Tính P1, P2
                int p1 = (offset >> 8) & 0xFF;
                int p2 = offset & 0xFF;

                // 4. Ghi log debug
                writer.printf("Packet #%d (Offset %d): Len=%d (padded)\n", chunkIndex, offset, len);

                // 5. GỬI LỆNH XUỐNG THẺ THẬT
                // CLA=A0, INS=10, P1, P2, Payload
                CommandAPDU cmd = new CommandAPDU(0xA0, INS_WRITE_IMAGE_INT, p1, p2, payload);

                long startTime = System.currentTimeMillis();
                ResponseAPDU res = channel.transmit(cmd);
                long duration = System.currentTimeMillis() - startTime;

                // 6. Kiểm tra phản hồi
                if (res.getSW() != 0x9000) {
                    String errorMsg = "Upload Failed at offset " + offset + " SW=" + Integer.toHexString(res.getSW());
                    writer.println(">> ERROR: " + errorMsg);
                    System.out.println(errorMsg);
                    return errorMsg;
                }

                writer.println(">> Success (Time: " + duration + "ms)");

                offset += len;
                chunkIndex++;
            }

            writer.println("=== UPLOAD COMPLETE (ENCRYPTED) ===");
            return "Success: Image uploaded to card (encrypted, " + totalBytes + " bytes)";

        } catch (Exception e) {
            e.printStackTrace();
            return "Error: " + e.getMessage();
        }
    }

    /**
     * Đọc ảnh từ thẻ (Ghép chunk) - VỚI GIẢI MÃ AES
     *
     * @param pin PIN để giải mã ảnh
     * @return Chuỗi Hex dài chứa toàn bộ dữ liệu ảnh
     */
    public String readImageFromCard(String pin) {
        if (channel == null)
            return "Error: Card not connected";

        byte[] pinBytes = pin.getBytes();
        StringBuilder fullImageHex = new StringBuilder();
        int offset = 0;
        int chunkSize = 240; // Đọc tối đa mỗi lần (Le), chia hết cho 16

        try {
            while (offset < APPLET_MAX_IMAGE_SIZE) {
                // 1. Tính P1, P2 (Offset)
                int p1 = (offset >> 8) & 0xFF;
                int p2 = offset & 0xFF;

                // 2. Tạo payload chứa PIN: [PIN_LEN][PIN]
                byte[] payload = new byte[1 + pinBytes.length];
                payload[0] = (byte) pinBytes.length;
                System.arraycopy(pinBytes, 0, payload, 1, pinBytes.length);

                // 3. Gửi lệnh READ với PIN, Le = chunkSize
                // CLA=A0, INS=11, P1, P2, Data, Le
                CommandAPDU cmd = new CommandAPDU(0xA0, INS_READ_IMAGE_INT, p1, p2, payload, chunkSize);
                ResponseAPDU res = channel.transmit(cmd);

                // 4. Kiểm tra phản hồi
                if (res.getSW() == 0x9000) {
                    byte[] data = res.getData();
                    if (data.length == 0)
                        break; // Không còn dữ liệu

                    // Append vào kết quả (đã được giải mã trên thẻ)
                    fullImageHex.append(HexUtils.bytesToHex(data));

                    offset += data.length;

                    // Nếu đọc được ít hơn chunkSize nghĩa là đã đến cuối ảnh
                    if (data.length < chunkSize)
                        break;

                } else if (res.getSW() == 0x6700) {
                    // 0x6700 (Wrong Length) = Applet báo hiệu hết ảnh
                    break;
                } else if (res.getSW() == 0x6982) {
                    return "Error: PIN not verified";
                } else {
                    return "Error: Read Failed SW=" + Integer.toHexString(res.getSW());
                }
            }

            return fullImageHex.toString();

        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    private int getPointsInternal() throws CardException {
        // Le = 2 để yêu cầu card trả về 2 bytes (points là short)
        CommandAPDU cmd = new CommandAPDU(0xA0, INS_GET_POINTS, 0x00, 0x00, 2);
        ResponseAPDU resp = channel.transmit(cmd);

        if (resp.getSW() == 0x9000) {
            byte[] data = resp.getData();
            // Convert 2 bytes -> int
            // data[0] là High byte, data[1] là Low byte
            return ((data[0] & 0xFF) << 8) | (data[1] & 0xFF);
        }
        return -1; // Lỗi
    }

    /**
     * Hàm Padding: Thêm byte 0x00 vào cuối để độ dài chia hết cho 16
     */
    private byte[] padData(byte[] data) {
        int dataLen = data.length;
        int paddingLen = AES_BLOCK_SIZE - (dataLen % AES_BLOCK_SIZE);
        // Nếu vừa khít (paddingLen == 16) thì có thể không cần pad,
        // nhưng chuẩn PKCS5/7 thường pad thêm 1 block.
        // Ở đây ta dùng Zero Padding đơn giản cho tiết kiệm bộ nhớ thẻ:
        // Nếu thiếu thì bù, nếu đủ thì thôi (paddingLen = 0 hoặc 16 -> 0).
        if (paddingLen == AES_BLOCK_SIZE)
            paddingLen = 0;

        int totalLen = dataLen + paddingLen;
        byte[] paddedData = new byte[totalLen];

        System.arraycopy(data, 0, paddedData, 0, dataLen);
        // Các byte còn lại mặc định là 0x00 (trong Java new byte[] đã là 0)

        return paddedData;
    }

    /**
     * Gửi thông tin User xuống thẻ (Có PIN để mã hóa)
     */
    public String updateUserInfo(String pin, String userInfoString) {
        if (channel == null)
            return "Error: Card not connected";

        try {
            // 1. Chuẩn bị PIN
            byte[] pinBytes = pin.getBytes();
            int pinLen = pinBytes.length;

            // 2. Chuẩn bị Data (Padding)
            // Lưu ý: Java Card thường hỗ trợ ASCII tốt nhất. Nếu dùng UTF-8 tiếng Việt có
            // dấu
            // có thể tốn nhiều byte hơn dự kiến. Tạm thời dùng getBytes() mặc định hoặc
            // UTF-8.
            byte[] rawData = userInfoString.getBytes("UTF-8");
            byte[] paddedData = padData(rawData);

            // 3. Ghép Payload: [PIN_LEN] + [PIN] + [PADDED_DATA]
            int totalLen = 1 + pinLen + paddedData.length;

            // Kiểm tra kích thước APDU (Max 255)
            // Nếu tổng lớn hơn 255, ta phải chia gói (Chunking) giống hệt Upload ảnh.
            // Nhưng để đơn giản cho Demo Lần 1, ta giả sử info ngắn (<240 bytes).
            if (totalLen > MAX_APDU_DATA_SIZE) {
                return "Error: Data too long (" + totalLen + "). Chunking needed.";
            }

            byte[] payload = new byte[totalLen];
            int offset = 0;

            payload[offset++] = (byte) pinLen; // Byte đầu: Độ dài PIN
            System.arraycopy(pinBytes, 0, payload, offset, pinLen); // Copy PIN
            offset += pinLen;

            System.arraycopy(paddedData, 0, payload, offset, paddedData.length); // Copy Data

            // 4. Gửi lệnh
            CommandAPDU cmd = new CommandAPDU(0xA0, INS_SET_INFO, 0x00, 0x00, payload);
            ResponseAPDU res = channel.transmit(cmd);

            if (res.getSW() == 0x9000) {
                return "Success";
            } else {
                return "Error: SW=" + Integer.toHexString(res.getSW());
            }

        } catch (Exception e) {
            e.printStackTrace();
            return "Error: " + e.getMessage();
        }
    }

    // --- LẤY THÔNG TIN BẢO MẬT (SECURE GET INFO) ---
    // Cần PIN để giải mã AES
    public String getSecureInfo(String pin) {
        if (channel == null)
            return "Error: Card not connected";
        try {
            byte[] pinBytes = pin.getBytes();
            int pinLen = pinBytes.length;

            // Payload: [PIN_LEN] [PIN]
            byte[] payload = new byte[1 + pinLen];
            payload[0] = (byte) pinLen;
            System.arraycopy(pinBytes, 0, payload, 1, pinLen);

            CommandAPDU cmd = new CommandAPDU(0xA0, INS_GET_INFO_SECURE, 0x00, 0x00, payload);
            ResponseAPDU res = channel.transmit(cmd);

            if (res.getSW() != 0x9000) {
                return "Error: SW=" + Integer.toHexString(res.getSW());
            }

            // Dữ liệu nhận về là Plaintext (đã giải mã) nhưng có thể còn padding 0x00
            String infoString = new String(res.getData(), StandardCharsets.UTF_8).trim();

            int points = getPointsInternal();

            if (points == -1)
                points = 0;

            return infoString + '|' + points;
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    public String updatePoints(int newPoints) {
        if (channel == null)
            return "Error: Card not connected";
        try {
            // Chuyển int -> 2 bytes array
            byte[] data = new byte[2];
            data[0] = (byte) ((newPoints >> 8) & 0xFF); // High byte
            data[1] = (byte) (newPoints & 0xFF); // Low byte

            // APDU: [CLA] [INS] [P1] [P2] [Lc] [DATA]
            CommandAPDU cmd = new CommandAPDU(0xA0, INS_UPDATE_POINTS, 0x00, 0x00, data);
            ResponseAPDU res = channel.transmit(cmd);

            if (res.getSW() == 0x9000) {
                return "Success";
            } else if (res.getSW() == 0x6982) { // Security Status Not Satisfied
                return "Error: Need Verify PIN first";
            } else {
                return "Error: Update Failed SW=" + Integer.toHexString(res.getSW());
            }

        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    /**
     * CHANGE PIN
     * Logic Applet: Yêu cầu 'isValidated' -> Phải verify PIN cũ trước, sau đó mới
     * gửi lệnh đổi PIN.
     */
    public PinResponse changePin(String oldPin, String newPin) {
        if (channel == null)
            return new PinResponse(false, "Card not connected", -1, "");

        try {
            byte[] oldPinBytes = oldPin.getBytes();
            byte[] newPinBytes = newPin.getBytes();

            // Payload: [OldLen] [OldPin] [NewLen] [NewPin]
            int payloadLen = 1 + oldPinBytes.length + 1 + newPinBytes.length;
            byte[] payload = new byte[payloadLen];

            int offset = 0;
            payload[offset++] = (byte) oldPinBytes.length;
            System.arraycopy(oldPinBytes, 0, payload, offset, oldPinBytes.length);
            offset += oldPinBytes.length;

            payload[offset++] = (byte) newPinBytes.length;
            System.arraycopy(newPinBytes, 0, payload, offset, newPinBytes.length);

            CommandAPDU cmd = new CommandAPDU(0xA0, INS_CHANGE_PIN, 0x00, 0x00, payload);
            ResponseAPDU res = channel.transmit(cmd);

            int sw = res.getSW();
            if (sw == 0x9000) {
                return new PinResponse(true, "PIN Changed & Data Re-encrypted", 3, "9000");
            }

            // Xử lý lỗi sai PIN cũ (63Cx) trả về từ Applet
            if ((sw & 0xFFF0) == 0x63C0) {
                int tries = sw & 0x0F;
                return new PinResponse(false, "Old PIN Incorrect", tries, Integer.toHexString(sw));
            }

            if (sw == 0x6983)
                return new PinResponse(false, "Card Locked", 0, "6983");

            return new PinResponse(false, "Change Failed SW=" + Integer.toHexString(sw), -1, Integer.toHexString(sw));
        } catch (Exception e) {
            return new PinResponse(false, "Exception: " + e.getMessage(), -1, "");
        }
    }

    /**
     * UNBLOCK PIN (Reset)
     * Applet Logic: Reset về 123456
     */
    public PinResponse unblockPin() {
        if (channel == null)
            return new PinResponse(false, "Card not connected", -1, "");

        try {
            // Lệnh này thường cần quyền Admin hoặc Secure Channel,
            // nhưng trong Demo Applet thì đang mở (public) nên gọi là được.
            CommandAPDU cmd = new CommandAPDU(0xA0, INS_UNBLOCK_PIN, 0x00, 0x00);
            ResponseAPDU res = channel.transmit(cmd);

            if (res.getSW() == 0x9000) {
                return new PinResponse(true, "PIN Reset to Default (123456)", 3, "9000");
            } else {
                return new PinResponse(false, "Unblock Failed SW=" + Integer.toHexString(res.getSW()), -1,
                        Integer.toHexString(res.getSW()));
            }

        } catch (Exception e) {
            return new PinResponse(false, "Exception: " + e.getMessage(), -1, "");
        }
    }
}
