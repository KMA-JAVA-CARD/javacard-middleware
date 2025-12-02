package sondoannam.github.services;

import sondoannam.github.utils.HexUtils;

import javax.smartcardio.*;
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
}
