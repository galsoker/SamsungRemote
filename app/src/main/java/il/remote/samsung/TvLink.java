package il.remote.samsung;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.*;

/**
 * Talks to a Samsung TV using every known method:
 *  1. WSS port 8002 with token (Tizen 2018+)
 *  2. WS  port 8001 (Tizen 2016-2017)
 *  3. Legacy TCP port 55000 (C/D/E/F/H series 2010-2015)
 */
public class TvLink {
    public interface Listener {
        void onStatus(String msg);
        void onToken(String token);
    }

    public static final String APP_NAME = "IL Samsung Remote";
    private final Listener listener;
    private volatile Socket socket;
    private volatile OutputStream out;
    private volatile int mode = 0; // 0 none, 1 ws, 2 legacy
    private String ip;
    private final SecureRandom rnd = new SecureRandom();

    public TvLink(Listener l) { this.listener = l; }

    public boolean isConnected() { return mode != 0 && socket != null && !socket.isClosed(); }
    public String modeName() { return mode == 1 ? "Wi-Fi (Tizen)" : mode == 2 ? "Wi-Fi (ישן 55000)" : "לא מחובר"; }

    public synchronized void connect(String ip, String token) {
        disconnect();
        this.ip = ip;
        String nameB64 = Base64.getEncoder().encodeToString(APP_NAME.getBytes(StandardCharsets.UTF_8));
        String path = "/api/v2/channels/samsung.remote.control?name=" + URLEncoder.encode(nameB64);
        try {
            String p = path + (token != null && !token.isEmpty() ? "&token=" + token : "");
            openWs(ip, 8002, true, p);
            mode = 1;
            listener.onStatus("מחובר דרך 8002 – אם מופיעה הודעה בטלוויזיה, אשר אותה");
            return;
        } catch (Exception e) { closeQuiet(); }
        try {
            openWs(ip, 8001, false, path);
            mode = 1;
            listener.onStatus("מחובר דרך 8001");
            return;
        } catch (Exception e) { closeQuiet(); }
        try {
            openLegacy(ip);
            mode = 2;
            listener.onStatus("מחובר בפרוטוקול הישן (55000) – אשר בטלוויזיה אם נדרש");
            return;
        } catch (Exception e) { closeQuiet(); }
        listener.onStatus("לא הצלחתי להתחבר ל-" + ip + ". בדוק שהטלוויזיה דולקת ובאותה רשת. ננסה אינפרא-אדום אם יש.");
    }

    public synchronized void disconnect() { closeQuiet(); mode = 0; }

    private void closeQuiet() {
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        socket = null; out = null;
    }

    private void openWs(String ip, int port, boolean tls, String path) throws Exception {
        Socket s;
        if (tls) {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] c, String a) {}
                public void checkServerTrusted(X509Certificate[] c, String a) {}
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }}, rnd);
            Socket raw = new Socket();
            raw.connect(new InetSocketAddress(ip, port), 3000);
            SSLSocket ss = (SSLSocket) ctx.getSocketFactory().createSocket(raw, ip, port, true);
            ss.setSoTimeout(8000);
            ss.startHandshake();
            s = ss;
        } else {
            s = new Socket();
            s.connect(new InetSocketAddress(ip, port), 3000);
            s.setSoTimeout(8000);
        }
        byte[] keyBytes = new byte[16]; rnd.nextBytes(keyBytes);
        String key = Base64.getEncoder().encodeToString(keyBytes);
        String req = "GET " + path + " HTTP/1.1\r\n" +
                "Host: " + ip + ":" + port + "\r\n" +
                "Upgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Key: " + key + "\r\n" +
                "Sec-WebSocket-Version: 13\r\n\r\n";
        OutputStream o = s.getOutputStream();
        o.write(req.getBytes(StandardCharsets.US_ASCII)); o.flush();
        InputStream in = s.getInputStream();
        String head = readHeaders(in);
        if (!head.startsWith("HTTP/1.1 101")) { s.close(); throw new IOException("handshake: " + head); }
        String first = readFrame(in, o);
        if (first != null && first.contains("ms.channel.unauthorized")) {
            s.close(); throw new IOException("unauthorized");
        }
        handleMsg(first);
        s.setSoTimeout(0);
        socket = s; out = o;
        final Socket fs = s;
        new Thread(() -> {
            try {
                while (!fs.isClosed()) { String m = readFrame(in, o); if (m == null) break; handleMsg(m); }
            } catch (Exception ignored) {}
            if (socket == fs) { mode = 0; listener.onStatus("החיבור נסגר"); }
        }).start();
    }

    private void handleMsg(String m) {
        if (m == null) return;
        Matcher t = Pattern.compile("\"token\"\\s*:\\s*\"?(\\d+)").matcher(m);
        if (t.find()) listener.onToken(t.group(1));
    }

    private static String readHeaders(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c, n = 0;
        while ((c = in.read()) != -1) {
            sb.append((char) c);
            n = (c == '\r' || c == '\n') ? n + 1 : 0;
            if (n == 4) break;
        }
        return sb.toString();
    }

    private String readFrame(InputStream in, OutputStream o) throws IOException {
        while (true) {
            int b0 = in.read(); if (b0 < 0) return null;
            int b1 = in.read(); if (b1 < 0) return null;
            int op = b0 & 0x0F;
            long len = b1 & 0x7F;
            if (len == 126) len = (in.read() << 8) | in.read();
            else if (len == 127) { len = 0; for (int i = 0; i < 8; i++) len = (len << 8) | in.read(); }
            byte[] mask = null;
            if ((b1 & 0x80) != 0) { mask = new byte[4]; readFully(in, mask); }
            byte[] data = new byte[(int) len];
            readFully(in, data);
            if (mask != null) for (int i = 0; i < data.length; i++) data[i] ^= mask[i % 4];
            if (op == 0x8) return null;
            if (op == 0x9) { synchronized (this) { writeFrame(o, 0xA, data); } continue; }
            if (op == 0x1 || op == 0x0) return new String(data, StandardCharsets.UTF_8);
        }
    }

    private static void readFully(InputStream in, byte[] b) throws IOException {
        int off = 0;
        while (off < b.length) { int r = in.read(b, off, b.length - off); if (r < 0) throw new EOFException(); off += r; }
    }

    private void writeFrame(OutputStream o, int op, byte[] data) throws IOException {
        ByteArrayOutputStream f = new ByteArrayOutputStream();
        f.write(0x80 | op);
        if (data.length < 126) f.write(0x80 | data.length);
        else if (data.length < 65536) { f.write(0x80 | 126); f.write(data.length >> 8); f.write(data.length & 0xFF); }
        else { f.write(0x80 | 127); for (int i = 7; i >= 0; i--) f.write((int) ((long) data.length >> (8 * i)) & 0xFF); }
        byte[] mask = new byte[4]; rnd.nextBytes(mask);
        f.write(mask);
        for (int i = 0; i < data.length; i++) f.write(data[i] ^ mask[i % 4]);
        o.write(f.toByteArray()); o.flush();
    }

    private synchronized boolean sendWs(String json) {
        try { writeFrame(out, 0x1, json.getBytes(StandardCharsets.UTF_8)); return true; }
        catch (Exception e) { mode = 0; return false; }
    }

    private static byte[] str(String s, boolean raw) {
        byte[] b = raw ? s.getBytes(StandardCharsets.UTF_8)
                : Base64.getEncoder().encode(s.getBytes(StandardCharsets.UTF_8));
        return cat(new byte[]{(byte) (b.length & 0xFF), (byte) (b.length >> 8)}, b);
    }
    private static byte[] bytesStr(byte[] b) {
        return cat(new byte[]{(byte) (b.length & 0xFF), (byte) (b.length >> 8)}, b);
    }
    private static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) o.write(p, 0, p.length);
        return o.toByteArray();
    }

    private void openLegacy(String ip) throws Exception {
        Socket s = new Socket();
        s.connect(new InetSocketAddress(ip, 55000), 3000);
        s.setSoTimeout(15000);
        String localIp = s.getLocalAddress().getHostAddress();
        byte[] payload = cat(new byte[]{0x64, 0x00}, str(localIp, false), str("il-remote-01", false), str(APP_NAME, false));
        byte[] packet = cat(new byte[]{0x00}, str("iphone..iapp.samsung", true), bytesStr(payload));
        OutputStream o = s.getOutputStream();
        o.write(packet); o.flush();
        InputStream in = s.getInputStream();
        for (int attempt = 0; attempt < 3; attempt++) {
            in.read();
            int l1 = in.read() | (in.read() << 8); byte[] a = new byte[l1]; readFully(in, a);
            int l2 = in.read() | (in.read() << 8); byte[] p = new byte[l2]; readFully(in, p);
            if (p.length >= 3 && p[0] == 0x64 && p[1] == 0x00 && p[2] == 0x01) break;
            if (p.length >= 3 && p[0] == 0x64 && p[1] == 0x00 && p[2] == 0x00) { s.close(); throw new IOException("denied"); }
            if (p.length >= 1 && p[0] == 0x65) { s.close(); throw new IOException("cancelled"); }
            if (p.length == 2 && p[0] == 0x0A) continue;
            break;
        }
        s.setSoTimeout(0);
        socket = s; out = o;
    }

    private synchronized boolean sendLegacy(String key) {
        try {
            byte[] payload = cat(new byte[]{0, 0, 0}, str(key, false));
            out.write(cat(new byte[]{0x00}, str("iphone..iapp.samsung", true), bytesStr(payload)));
            out.flush();
            return true;
        } catch (Exception e) { mode = 0; return false; }
    }

    public boolean sendKey(String key, String cmd) {
        if (mode == 1) {
            return sendWs("{\"method\":\"ms.remote.control\",\"params\":{\"Cmd\":\"" + cmd +
                    "\",\"DataOfCmd\":\"" + key + "\",\"Option\":\"false\",\"TypeOfRemote\":\"SendRemoteKey\"}}");
        } else if (mode == 2) {
            return sendLegacy(key);
        }
        return false;
    }

    public boolean sendText(String text) {
        if (mode != 1) return false;
        String b64 = Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
        boolean ok = sendWs("{\"method\":\"ms.remote.control\",\"params\":{\"Cmd\":\"" + b64 +
                "\",\"DataOfCmd\":\"base64\",\"TypeOfRemote\":\"SendInputString\"}}");
        sendWs("{\"method\":\"ms.remote.control\",\"params\":{\"TypeOfRemote\":\"SendInputEnd\"}}");
        return ok;
    }

    public boolean launchApp(String appId) {
        boolean ok = false;
        if (mode == 1) {
            ok = sendWs("{\"method\":\"ms.channel.emit\",\"params\":{\"event\":\"ed.apps.launch\",\"to\":\"host\",\"data\":{\"appId\":\""
                    + appId + "\",\"action_type\":\"DEEP_LINK\"}}}");
            sendWs("{\"method\":\"ms.channel.emit\",\"params\":{\"event\":\"ed.apps.launch\",\"to\":\"host\",\"data\":{\"appId\":\""
                    + appId + "\",\"action_type\":\"NATIVE_LAUNCH\"}}}");
        }
        if (ip != null) {
            ok |= http("POST", "http://" + ip + ":8001/api/v2/applications/" + appId) != null;
        }
        return ok;
    }

    public static String http(String method, String url) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(1200); c.setReadTimeout(2500);
            c.setRequestMethod(method);
            if ("POST".equals(method)) { c.setDoOutput(true); c.getOutputStream().close(); }
            int code = c.getResponseCode();
            InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            if (in != null) { byte[] buf = new byte[4096]; int r; while ((r = in.read(buf)) > 0) b.write(buf, 0, r); }
            return code < 400 ? b.toString("UTF-8") : null;
        } catch (Exception e) { return null; }
    }

    public static String jsonField(String json, String field) {
        if (json == null) return null;
        Matcher m = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    public static void wakeOnLan(String mac, String ip) throws Exception {
        String[] hex = mac.split("[:\\-]");
        if (hex.length != 6) throw new IllegalArgumentException("MAC");
        byte[] macB = new byte[6];
        for (int i = 0; i < 6; i++) macB[i] = (byte) Integer.parseInt(hex[i], 16);
        byte[] pkt = new byte[102];
        for (int i = 0; i < 6; i++) pkt[i] = (byte) 0xFF;
        for (int i = 6; i < pkt.length; i += 6) System.arraycopy(macB, 0, pkt, i, 6);
        DatagramSocket ds = new DatagramSocket();
        ds.setBroadcast(true);
        String[] targets = {"255.255.255.255", ip != null ? ip.substring(0, ip.lastIndexOf('.')) + ".255" : null, ip};
        for (int r = 0; r < 3; r++)
            for (String t : targets) {
                if (t == null) continue;
                for (int port : new int[]{9, 7})
                    ds.send(new DatagramPacket(pkt, pkt.length, InetAddress.getByName(t), port));
            }
        ds.close();
    }
}
