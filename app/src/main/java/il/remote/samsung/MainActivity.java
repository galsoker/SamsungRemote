package il.remote.samsung;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.hardware.ConsumerIrManager;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.*;

import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity implements TvLink.Listener {

    private TvLink tv;
    private SharedPreferences prefs;
    private ConsumerIrManager ir;
    private TextView status;
    private EditText ipField;
    private Button modeBtn;
    private int sendMode = 0; // 0 auto, 1 wifi only, 2 IR only
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private Vibrator vib;

    private static final Map<String, Integer> IR = new HashMap<>();
    static {
        Object[][] t = {
            {"KEY_POWER", 0x02}, {"KEY_POWEROFF", 0x98}, {"KEY_POWERON", 0x99}, {"KEY_SOURCE", 0x01},
            {"KEY_1", 0x04}, {"KEY_2", 0x05}, {"KEY_3", 0x06}, {"KEY_4", 0x08}, {"KEY_5", 0x09},
            {"KEY_6", 0x0A}, {"KEY_7", 0x0C}, {"KEY_8", 0x0D}, {"KEY_9", 0x0E}, {"KEY_0", 0x11},
            {"KEY_VOLUP", 0x07}, {"KEY_VOLDOWN", 0x0B}, {"KEY_MUTE", 0x0F},
            {"KEY_CHUP", 0x12}, {"KEY_CHDOWN", 0x10}, {"KEY_PRECH", 0x13}, {"KEY_CH_LIST", 0x6B},
            {"KEY_MENU", 0x1A}, {"KEY_UP", 0x60}, {"KEY_DOWN", 0x61}, {"KEY_LEFT", 0x65},
            {"KEY_RIGHT", 0x62}, {"KEY_ENTER", 0x68}, {"KEY_RETURN", 0x58}, {"KEY_EXIT", 0x2D},
            {"KEY_INFO", 0x1F}, {"KEY_TOOLS", 0x4B}, {"KEY_GUIDE", 0x4F}, {"KEY_HOME", 0x79},
            {"KEY_RED", 0x6C}, {"KEY_GREEN", 0x14}, {"KEY_YELLOW", 0x15}, {"KEY_CYAN", 0x16},
            {"KEY_PLAY", 0x47}, {"KEY_PAUSE", 0x4A}, {"KEY_STOP", 0x46}, {"KEY_REWIND", 0x45},
            {"KEY_FF", 0x48}, {"KEY_REC", 0x49}, {"KEY_HDMI", 0x8B}, {"KEY_TV", 0x1B},
            {"KEY_SUB_TITLE", 0x25}, {"KEY_PICTURE_SIZE", 0x3E}, {"KEY_SLEEP", 0x03},
            {"KEY_HDMI1", 0xE9}, {"KEY_HDMI2", 0xBE}, {"KEY_HDMI3", 0xC2}, {"KEY_HDMI4", 0xC5},
            {"KEY_PMODE", 0x28}, {"KEY_AD", 0xE4}, {"KEY_E_MANUAL", 0x3F}, {"KEY_3D", 0x9F},
            {"KEY_DTV", 0x27}, {"KEY_W_LINK", 0x8C}, {"KEY_ANTENA", 0x36}, {"KEY_ASPECT", 0x3E},
        };
        for (Object[] r : t) IR.put((String) r[0], (Integer) r[1]);
    }

    private static final String[][] APPS = {
        {"Netflix", "3201907018807", "11101200001"},
        {"YouTube", "111299001912", "111299001912"},
        {"Prime Video", "3201910019365", "3201512006785"},
        {"Disney+", "3201901017640", "3201901017640"},
        {"Apple TV", "3201807016597", "3201807016597"},
        {"Spotify", "3201606009684", "3201606009684"},
        {"Yes+", "3201908019041", "3201908019041"},
        {"Cellcom TV", "3201812017479", "3201812017479"},
        {"Partner TV", "3201910019279", "3201910019279"},
        {"Kan 11", "3202011022131", "3202011022131"},
        {"Internet", "org.tizen.browser", "3201907018784"},
        {"Plex", "3201512006963", "3201512006963"},
    };

    private static final int BG = Color.rgb(18, 20, 28), CARD = Color.rgb(36, 40, 54), ACC = Color.rgb(20, 120, 255);

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("tv", MODE_PRIVATE);
        tv = new TvLink(this);
        try { ir = (ConsumerIrManager) getSystemService(CONSUMER_IR_SERVICE); } catch (Exception ignored) {}
        vib = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        getWindow().setStatusBarColor(BG);
        setContentView(buildUi());
        String ip = prefs.getString("ip", "");
        if (!ip.isEmpty()) connect();
        else setStatus("הקש 'סרוק' כדי למצוא את הטלוויזיה" + (hasIr() ? " (נמצא משדר IR בטלפון ✓)" : ""));
    }

    private boolean hasIr() { try { return ir != null && ir.hasIrEmitter(); } catch (Exception e) { return false; } }

    private View buildUi() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        LinearLayout root = col();
        root.setPadding(dp(12), dp(12), dp(12), dp(24));
        sv.addView(root);

        TextView title = new TextView(this);
        title.setText("שלט סמסונג");
        title.setTextColor(Color.WHITE); title.setTextSize(24); title.setGravity(Gravity.CENTER);
        root.addView(title);

        status = new TextView(this);
        status.setTextColor(Color.rgb(170, 180, 200)); status.setTextSize(13);
        status.setGravity(Gravity.CENTER); status.setPadding(0, dp(4), 0, dp(8));
        root.addView(status);

        LinearLayout conn = row();
        ipField = new EditText(this);
        ipField.setHint("כתובת IP של הטלוויזיה");
        ipField.setText(prefs.getString("ip", ""));
        ipField.setTextColor(Color.WHITE); ipField.setHintTextColor(Color.GRAY);
        ipField.setInputType(InputType.TYPE_CLASS_PHONE);
        conn.addView(ipField, new LinearLayout.LayoutParams(0, -2, 2f));
        conn.addView(btn("התחבר", v -> { prefs.edit().putString("ip", ipField.getText().toString().trim()).apply(); connect(); }, ACC), wp(1));
        conn.addView(btn("סרוק", v -> scan(), CARD), wp(1));
        root.addView(conn);

        LinearLayout conn2 = row();
        modeBtn = btn("מצב: אוטומטי", v -> cycleMode(), CARD);
        conn2.addView(modeBtn, wp(1));
        conn2.addView(btn("בדיקת כל השיטות", v -> testAll(), CARD), wp(1));
        root.addView(conn2);

        root.addView(section("הפעלה"));
        LinearLayout p = row();
        p.addView(btn("⏻ הפעל/כבה", v -> power(), Color.rgb(200, 40, 50)), wp(2));
        p.addView(key("מקור", "KEY_SOURCE"), wp(1));
        p.addView(key("HDMI", "KEY_HDMI"), wp(1));
        root.addView(p);

        root.addView(section("מספרים"));
        String[][] nums = {{"1", "2", "3"}, {"4", "5", "6"}, {"7", "8", "9"}};
        for (String[] r : nums) {
            LinearLayout rr = row();
            for (String n : r) rr.addView(key(n, "KEY_" + n), wp(1));
            root.addView(rr);
        }
        LinearLayout nr = row();
        nr.addView(key("רשימת ערוצים", "KEY_CH_LIST"), wp(1));
        nr.addView(key("0", "KEY_0"), wp(1));
        nr.addView(key("ערוץ קודם", "KEY_PRECH"), wp(1));
        root.addView(nr);

        root.addView(section("ווליום וערוצים"));
        LinearLayout vc = row();
        vc.addView(repeatKey("ווליום +", "KEY_VOLUP"), wp(1));
        vc.addView(key("השתק 🔇", "KEY_MUTE"), wp(1));
        vc.addView(repeatKey("ערוץ ▲", "KEY_CHUP"), wp(1));
        root.addView(vc);
        LinearLayout vc2 = row();
        vc2.addView(repeatKey("ווליום -", "KEY_VOLDOWN"), wp(1));
        vc2.addView(key("מדריך", "KEY_GUIDE"), wp(1));
        vc2.addView(repeatKey("ערוץ ▼", "KEY_CHDOWN"), wp(1));
        root.addView(vc2);

        root.addView(section("ניווט"));
        LinearLayout n1 = row();
        n1.addView(key("בית 🏠", "KEY_HOME"), wp(1));
        n1.addView(repeatKey("▲", "KEY_UP"), wp(1));
        n1.addView(key("תפריט", "KEY_MENU"), wp(1));
        root.addView(n1);
        LinearLayout n2 = row();
        n2.addView(repeatKey("◀", "KEY_LEFT"), wp(1));
        n2.addView(btnKey("OK", "KEY_ENTER", ACC), wp(1));
        n2.addView(repeatKey("▶", "KEY_RIGHT"), wp(1));
        root.addView(n2);
        LinearLayout n3 = row();
        n3.addView(key("חזור ↩", "KEY_RETURN"), wp(1));
        n3.addView(repeatKey("▼", "KEY_DOWN"), wp(1));
        n3.addView(key("יציאה", "KEY_EXIT"), wp(1));
        root.addView(n3);
        LinearLayout n4 = row();
        n4.addView(key("מידע", "KEY_INFO"), wp(1));
        n4.addView(key("כלים", "KEY_TOOLS"), wp(1));
        n4.addView(key("מספרים/צבעים", "KEY_MORE"), wp(1));
        root.addView(n4);

        LinearLayout cl = row();
        cl.addView(btnKey("A", "KEY_RED", Color.rgb(200, 40, 40)), wp(1));
        cl.addView(btnKey("B", "KEY_GREEN", Color.rgb(40, 160, 60)), wp(1));
        cl.addView(btnKey("C", "KEY_YELLOW", Color.rgb(200, 170, 20)), wp(1));
        cl.addView(btnKey("D", "KEY_CYAN", Color.rgb(30, 90, 200)), wp(1));
        root.addView(cl);

        root.addView(section("מדיה"));
        LinearLayout m1 = row();
        m1.addView(key("⏪", "KEY_REWIND"), wp(1));
        m1.addView(key("▶", "KEY_PLAY"), wp(1));
        m1.addView(key("⏸", "KEY_PAUSE"), wp(1));
        m1.addView(key("⏹", "KEY_STOP"), wp(1));
        m1.addView(key("⏩", "KEY_FF"), wp(1));
        m1.addView(key("⏺", "KEY_REC"), wp(1));
        root.addView(m1);

        root.addView(section("אפליקציות"));
        LinearLayout ar = null;
        for (int i = 0; i < APPS.length; i++) {
            if (i % 3 == 0) { ar = row(); root.addView(ar); }
            final String[] a = APPS[i];
            ar.addView(btn(a[0], v -> launch(a), CARD), wp(1));
        }

        root.addView(section("שליחת טקסט לטלוויזיה (חיפוש)"));
        LinearLayout tr = row();
        EditText txt = new EditText(this);
        txt.setHint("הקלד ושלח לשדה הפעיל בטלוויזיה");
        txt.setTextColor(Color.WHITE); txt.setHintTextColor(Color.GRAY);
        tr.addView(txt, new LinearLayout.LayoutParams(0, -2, 3f));
        tr.addView(btn("שלח", v -> {
            String s = txt.getText().toString();
            bg.execute(() -> toast(tv.sendText(s) ? "נשלח" : "שליחת טקסט עובדת רק בחיבור Wi-Fi לטלוויזיות Tizen"));
        }, ACC), wp(1));
        root.addView(tr);

        root.addView(section("כל המקשים המתקדמים"));
        String[][] extra = {
            {"HDMI 1", "KEY_HDMI1"}, {"HDMI 2", "KEY_HDMI2"}, {"HDMI 3", "KEY_HDMI3"},
            {"HDMI 4", "KEY_HDMI4"}, {"טלוויזיה", "KEY_TV"}, {"DTV", "KEY_DTV"},
            {"אנטנה", "KEY_ANTENA"}, {"כתוביות", "KEY_SUB_TITLE"}, {"יחס תמונה", "KEY_PICTURE_SIZE"},
            {"מצב תמונה", "KEY_PMODE"}, {"טיימר שינה", "KEY_SLEEP"}, {"תיאור קולי", "KEY_AD"},
            {"מדריך אלקטרוני", "KEY_E_MANUAL"}, {"3D", "KEY_3D"}, {"Ambient", "KEY_AMBIENT"},
            {"מקלדת", "KEY_KEYBOARD"}, {"מיקרופון/Bixby", "KEY_BT_VOICE"}, {"אינטרנט", "KEY_W_LINK"},
            {"כבה בלבד", "KEY_POWEROFF"}, {"הדלק בלבד", "KEY_POWERON"}, {"תפריט מהיר", "KEY_CONTENTS"},
            {"שפת שמע", "KEY_MTS"}, {"סאונד", "KEY_SRS"}, {"Teletext", "KEY_TTX_MIX"},
            {"עמוד ▲", "KEY_PAGE_UP"}, {"עמוד ▼", "KEY_PAGE_DOWN"}, {"זום", "KEY_ZOOM1"},
            {"Caption", "KEY_CAPTION"}, {"Fav", "KEY_FAVCH"}, {"Live", "KEY_LIVE"},
        };
        for (int i = 0; i < extra.length; i++) {
            if (i % 3 == 0) { ar = row(); root.addView(ar); }
            ar.addView(key(extra[i][0], extra[i][1]), wp(1));
        }

        root.addView(section("שליחת קוד מקש ידני"));
        LinearLayout ck = row();
        EditText kf = new EditText(this);
        kf.setHint("למשל KEY_FACTORY");
        kf.setTextColor(Color.WHITE); kf.setHintTextColor(Color.GRAY);
        ck.addView(kf, new LinearLayout.LayoutParams(0, -2, 3f));
        ck.addView(btn("שלח", v -> send(kf.getText().toString().trim().toUpperCase(Locale.ROOT)), ACC), wp(1));
        root.addView(ck);

        root.addView(section("כתובת MAC להדלקה דרך Wi-Fi (נמלא אוטומטית בסריקה)"));
        LinearLayout mr = row();
        EditText mac = new EditText(this);
        mac.setHint("AA:BB:CC:DD:EE:FF");
        mac.setText(prefs.getString("mac", ""));
        mac.setTextColor(Color.WHITE); mac.setHintTextColor(Color.GRAY);
        mr.addView(mac, new LinearLayout.LayoutParams(0, -2, 3f));
        mr.addView(btn("שמור", v -> { prefs.edit().putString("mac", mac.getText().toString().trim()).apply(); toast("נשמר"); }, CARD), wp(1));
        root.addView(mr);
        return sv;
    }

    private LinearLayout col() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout row() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); return l; }
    private LinearLayout.LayoutParams wp(float w) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(54), w);
        lp.setMargins(dp(3), dp(3), dp(3), dp(3));
        return lp;
    }
    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    private TextView section(String s) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextColor(Color.rgb(120, 160, 255)); t.setTextSize(14);
        t.setPadding(dp(4), dp(14), dp(4), dp(4));
        return t;
    }

    private Button btn(String label, View.OnClickListener l, int color) {
        Button b = new Button(this);
        b.setText(label); b.setAllCaps(false);
        b.setTextColor(Color.WHITE); b.setTextSize(14);
        GradientDrawable g = new GradientDrawable();
        g.setColor(color); g.setCornerRadius(dp(14));
        b.setBackground(g);
        b.setPadding(dp(2), 0, dp(2), 0);
        b.setOnClickListener(l);
        return b;
    }
    private Button key(String label, String k) { return btnKey(label, k, CARD); }
    private Button btnKey(String label, String k, int color) { return btn(label, v -> send(k), color); }

    private Button repeatKey(String label, String k) {
        Button b = btn(label, null, CARD);
        final ScheduledExecutorService[] rep = new ScheduledExecutorService[1];
        b.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                v.setPressed(true);
                send(k);
                rep[0] = Executors.newSingleThreadScheduledExecutor();
                rep[0].scheduleAtFixedRate(() -> runOnUiThread(() -> send(k)), 450, 150, TimeUnit.MILLISECONDS);
            } else if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) {
                v.setPressed(false);
                if (rep[0] != null) rep[0].shutdownNow();
            }
            return true;
        });
        return b;
    }

    private void connect() {
        String ip = prefs.getString("ip", "");
        if (ip.isEmpty()) { setStatus("אין כתובת IP – הקש סרוק"); return; }
        setStatus("מתחבר ל-" + ip + "...");
        bg.execute(() -> tv.connect(ip, prefs.getString("token", "")));
    }

    private void cycleMode() {
        sendMode = (sendMode + 1) % 3;
        modeBtn.setText(sendMode == 0 ? "מצב: אוטומטי" : sendMode == 1 ? "מצב: Wi-Fi בלבד" : "מצב: אינפרא-אדום");
        if (sendMode == 2 && !hasIr()) toast("לטלפון הזה אין משדר אינפרא-אדום");
    }

    private void send(String key) {
        if (key == null || key.isEmpty()) return;
        try { if (vib != null) vib.vibrate(VibrationEffect.createOneShot(25, 120)); } catch (Exception ignored) {}
        if (sendMode != 2 && tv.isConnected()) {
            bg.execute(() -> {
                if (!tv.sendKey(key, "Click")) {
                    if (sendMode == 0 && sendIr(key)) return;
                    toast("השליחה נכשלה – מתחבר מחדש");
                    connect();
                }
            });
            return;
        }
        if (sendMode != 1 && sendIr(key)) return;
        if (sendMode == 2) { toast(hasIr() ? "אין קוד IR למקש הזה" : "אין משדר IR בטלפון"); return; }
        toast("לא מחובר – מתחבר...");
        connect();
    }

    private boolean sendIr(String key) {
        Integer cmd = IR.get(key);
        if (cmd == null || !hasIr()) return false;
        try { ir.transmit(38000, samsung32(0x07, cmd)); return true; }
        catch (Exception e) { return false; }
    }

    private static int[] samsung32(int addr, int cmd) {
        int[] p = new int[2 + 64 + 1];
        int i = 0;
        p[i++] = 4500; p[i++] = 4500;
        int data = addr | (addr << 8) | (cmd << 16) | ((~cmd & 0xFF) << 24);
        for (int bit = 0; bit < 32; bit++) {
            p[i++] = 560;
            p[i++] = ((data >>> bit) & 1) == 1 ? 1690 : 560;
        }
        p[i] = 560;
        return p;
    }

    private void power() {
        if (tv.isConnected() && sendMode != 2) { send("KEY_POWER"); return; }
        String mac = prefs.getString("mac", "");
        String ip = prefs.getString("ip", "");
        boolean irOk = sendMode != 1 && sendIr("KEY_POWER");
        if (sendMode != 2 && !mac.isEmpty()) {
            bg.execute(() -> {
                try { TvLink.wakeOnLan(mac, ip.isEmpty() ? null : ip); toast("נשלחה פקודת הדלקה ברשת"); }
                catch (Exception e) { toast("שגיאת הדלקה ברשת: " + e.getMessage()); }
                try { Thread.sleep(6000); } catch (InterruptedException ignored) {}
                tv.connect(ip, prefs.getString("token", ""));
            });
        } else if (!irOk) {
            toast("כדי להדליק טלוויזיה כבויה צריך כתובת MAC (מתמלאת בסריקה) או טלפון עם IR");
        }
    }

    private void launch(String[] app) {
        bg.execute(() -> {
            boolean ok = tv.launchApp(app[1]);
            if (!app[2].equals(app[1])) ok |= tv.launchApp(app[2]);
            toast(ok ? "פותח " + app[0] : "לא הצלחתי לפתוח " + app[0] + " (ייתכן שאינה מותקנת)");
        });
    }

    private void testAll() {
        String ip = prefs.getString("ip", "");
        setStatus("בודק את כל שיטות החיבור...");
        bg.execute(() -> {
            StringBuilder sb = new StringBuilder();
            if (!ip.isEmpty()) {
                String info = TvLink.http("GET", "http://" + ip + ":8001/api/v2/");
                sb.append("REST 8001 (Tizen 2016+): ").append(info != null ? "✓ " + nz(TvLink.jsonField(info, "modelName")) : "✗").append('\n');
                sb.append("WSS 8002 (2018+): ").append(portOpen(ip, 8002) ? "✓" : "✗").append('\n');
                sb.append("WS 8001: ").append(portOpen(ip, 8001) ? "✓" : "✗").append('\n');
                sb.append("ישן 55000 (2010-2015): ").append(portOpen(ip, 55000) ? "✓" : "✗").append('\n');
                sb.append("DLNA 7676/9197: ").append(portOpen(ip, 7676) || portOpen(ip, 9197) ? "✓" : "✗").append('\n');
            } else sb.append("אין כתובת IP\n");
            sb.append("משדר IR בטלפון: ").append(hasIr() ? "✓" : "✗").append('\n');
            sb.append("MAC להדלקה: ").append(prefs.getString("mac", "").isEmpty() ? "✗" : "✓").append('\n');
            sb.append("חיבור נוכחי: ").append(tv.modeName());
            String res = sb.toString();
            runOnUiThread(() -> new AlertDialog.Builder(this).setTitle("תוצאות בדיקה").setMessage(res)
                    .setPositiveButton("התחבר עכשיו", (d, w) -> connect()).setNegativeButton("סגור", null).show());
        });
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static boolean portOpen(String ip, int port) {
        try (Socket s = new Socket()) { s.connect(new InetSocketAddress(ip, port), 1000); return true; }
        catch (Exception e) { return false; }
    }

    private void scan() {
        setStatus("סורק את הרשת... (עד 20 שניות)");
        new Thread(() -> {
            List<String> prefixes = new ArrayList<>();
            try {
                for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                    if (!ni.isUp() || ni.isLoopback()) continue;
                    for (InetAddress a : Collections.list(ni.getInetAddresses()))
                        if (a instanceof Inet4Address) {
                            String h = a.getHostAddress();
                            prefixes.add(h.substring(0, h.lastIndexOf('.') + 1));
                        }
                }
            } catch (Exception ignored) {}
            if (prefixes.isEmpty()) { setStatus("לא נמצא חיבור Wi-Fi"); return; }
            ExecutorService pool = Executors.newFixedThreadPool(64);
            List<String[]> found = Collections.synchronizedList(new ArrayList<>());
            for (String pre : prefixes)
                for (int i = 1; i < 255; i++) {
                    final String ip = pre + i;
                    pool.execute(() -> {
                        if (portOpen(ip, 8001) || portOpen(ip, 8002)) {
                            String info = TvLink.http("GET", "http://" + ip + ":8001/api/v2/");
                            String name = TvLink.jsonField(info, "name");
                            String model = TvLink.jsonField(info, "modelName");
                            String mac = TvLink.jsonField(info, "wifiMac");
                            found.add(new String[]{ip, (name != null ? name : "Samsung TV") + (model != null ? " (" + model + ")" : ""), mac});
                        } else if (portOpen(ip, 55000)) {
                            found.add(new String[]{ip, "Samsung (דגם ישן)", null});
                        }
                    });
                }
            pool.shutdown();
            try { pool.awaitTermination(40, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
            runOnUiThread(() -> {
                if (found.isEmpty()) {
                    setStatus("לא נמצאה טלוויזיה. ודא שהיא דולקת ובאותה רשת Wi-Fi, או הזן IP ידנית");
                    return;
                }
                String[] labels = new String[found.size()];
                for (int i = 0; i < labels.length; i++) labels[i] = found.get(i)[1] + "\n" + found.get(i)[0];
                new AlertDialog.Builder(this).setTitle("נמצאו טלוויזיות").setItems(labels, (d, w) -> {
                    String[] f = found.get(w);
                    SharedPreferences.Editor e = prefs.edit().putString("ip", f[0]);
                    if (f[2] != null && !f[2].isEmpty()) e.putString("mac", f[2]);
                    e.apply();
                    ipField.setText(f[0]);
                    connect();
                }).show();
                setStatus("נמצאו " + found.size() + " טלוויזיות");
            });
        }).start();
    }

    @Override public void onStatus(String msg) { setStatus(msg); }
    @Override public void onToken(String token) { prefs.edit().putString("token", token).apply(); }

    private void setStatus(String s) { runOnUiThread(() -> status.setText(s)); }
    private void toast(String s) { runOnUiThread(() -> Toast.makeText(this, s, Toast.LENGTH_SHORT).show()); }

    @Override protected void onDestroy() { super.onDestroy(); new Thread(tv::disconnect).start(); }
}
