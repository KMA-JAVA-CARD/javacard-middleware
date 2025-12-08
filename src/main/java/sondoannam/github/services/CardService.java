package sondoannam.github.services;

import sondoannam.github.utils.HexUtils;

import javax.smartcardio.*;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class CardService {
    private Card card;
    private CardChannel channel;

    // AID chuẩn (10 bytes)
    private static final byte[] APPLET_AID = HexUtils.hexToBytes("A00000006203010A0100");

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
        if (channel == null) return false;
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
        if (channel == null) return "Error: No Connection";
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
            if (card != null) card.disconnect(false);
            System.out.println("[INFO] Đã ngắt kết nối thẻ.");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Kích thước tối đa của dữ liệu trong 1 lệnh APDU (Max 255, ta chọn 240)
    private static final int MAX_APDU_DATA_SIZE = 240;

    // Lệnh ghi ảnh (INS_WRITE_IMAGE)
    private static final int INS_WRITE_IMAGE_INT = 0x10;

    /**
     * Hàm nhận chuỗi Hex ảnh, cắt nhỏ và ghi log ra file
     * @param hexImage Chuỗi Hex dài của ảnh (4096 bytes ~ 8192 ký tự hex)
     * @return Thông báo kết quả
     */
    public String uploadImageToCard(String hexImage) {
        if (channel == null) return "Error: Card not connected";

        byte[] imageData;
        try {
            imageData = HexUtils.hexToBytes(hexImage);
        } catch (IllegalArgumentException e) {
            return "Error: Invalid Hex String";
        }

        int totalBytes = imageData.length;
        String logFileName = "debug_image_chunks.txt";

        try (PrintWriter writer = new PrintWriter(new BufferedWriter(new FileWriter(logFileName, true)))) {
            String timeStamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            writer.println("\n=== REAL UPLOAD TO CARD - " + timeStamp + " ===");

            int offset = 0;
            int chunkIndex = 0;

            while (offset < totalBytes) {
                // 1. Cắt gói
                int len = Math.min(MAX_APDU_DATA_SIZE, totalBytes - offset);
                byte[] chunkData = new byte[len];
                System.arraycopy(imageData, offset, chunkData, 0, len);

                // 2. Tính P1, P2
                int p1 = (offset >> 8) & 0xFF;
                int p2 = offset & 0xFF;

                // 3. Ghi log debug
                writer.printf("Packet #%d (Offset %d): Len=%d\n", chunkIndex, offset, len);

                // 4. GỬI LỆNH XUỐNG THẺ THẬT
                // CLA=A0, INS=10, P1, P2, Data
                CommandAPDU cmd = new CommandAPDU(0xA0, INS_WRITE_IMAGE_INT, p1, p2, chunkData);

                long startTime = System.currentTimeMillis();
                ResponseAPDU res = channel.transmit(cmd);
                long duration = System.currentTimeMillis() - startTime;

                // 5. Kiểm tra phản hồi
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

            writer.println("=== UPLOAD COMPLETE ===");
            return "Success: Image uploaded to card (" + totalBytes + " bytes)";

        } catch (Exception e) {
            e.printStackTrace();
            return "Error: " + e.getMessage();
        }
    }
}
