package il.remote.samsung;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.hardware.ConsumerIrManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import android.widget.*;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity implements TvLink.Listener {

    private static final int BG1 = 0xFF07090F, BG2 = 0xFF121829;
    private static final int SURF = 0xFF1A2032, SURF2 = 0xFF242C44, STROKE = 0x22FFFFFF;
    private static final int ACC = 0xFF3D7BFF, ACC2 = 0xFF8A5CFF, RED = 0xFFE5484D, GREEN = 0xFF30C67C, ORANGE = 0xFFF5A524;
    private static final int TXT = 0xFFFFFFFF, SUB = 0xFF8C96B3;

    private TvLink tv;
    private SharedPreferences prefs;
    private ConsumerIrManager ir;
    private Vibrator vib;
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final ExecutorService bg2 = Executors.newFixedThreadPool(8);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private int sendMode = 0;

    private TextView statusText, statusDot, tvNameText, chip, modeBtn;
    private FrameLayout overlay;
    private LinearLayout resultsList;
    private TextView scanTitle;
    private ProgressBar scanBar;
    private EditText manualIp, macField;
    private final Set<String> seen = Collections.synchronizedSet(new HashSet<>());
    private volatile boolean scanning = false;

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
            {"KEY_DTV", 0x27}, {"KEY_W_LINK", 0x8C}, {"KEY_ANTENA", 0x36},
        };
        for (Object[] r : t) IR.put((String) r[0], (Integer) r[1]);
    }

    private static final String[][] APPS = {
        {"Netflix", "3201907018807", "11101200001", "#E50914"},
        {"YouTube", "111299001912", "111299001912", "#FF0033"},
        {"Prime Video", "3201910019365", "3201512006785", "#00A8E1"},
        {"Disney+", "3201901017640", "3201901017640", "#113CCF"},
        {"Apple TV", "3201807016597", "3201807016597", "#3A3A3C"},
        {"Spotify", "3201606009684", "3201606009684", "#1DB954"},
        {"Yes+", "3201908019041", "3201908019041", "#0072CE"},
        {"Cellcom TV", "3201812017479", "3201812017479", "#6C2DC7"},
        {"Partner TV", "3201910019279", "3201910019279", "#00B2A9"},
        {"כאן 11", "3202011022131", "3202011022131", "#F2A900"},
        {"דפדפן", "org.tizen.browser", "3201907018784", "#4A5568"},
        {"Plex", "3201512006963", "3201512006963", "#E5A00D"},
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("tv", MODE_PRIVATE);
        tv = new TvLink(this);
        try { ir = (ConsumerIrManager) getSystemService(CONSUMER_IR_SERVICE); } catch (Exception ignored) {}
        vib = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        getWindow().setStatusBarColor(BG1);
        getWindow().setNavigationBarColor(BG2);
        setContentView(buildRoot());
        String ip = prefs.getString("ip", "");
        refreshStatus("מתחבר...");
        if (!ip.isEmpty()) connect();
        else ui.postDelayed(this::openDiscovery, 350);
    }

    private boolean hasIr() { try { return ir != null && ir.hasIrEmitter(); } catch (Exception e) { return false; } }

    private View buildRoot() {
        FrameLayout root = new FrameLayout(this);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{BG1, BG2}));

        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout c = vbox();
        c.setPadding(dp(18), dp(14), dp(18), dp(32));
        sv.addView(c);
        root.addView(sv, new FrameLayout.LayoutParams(-1, -1));

        c.addView(header());
        c.addView(space(18));
        c.addView(powerRow());
        c.addView(space(22));
        c.addView(dpad(), centered(dp(264), dp(264)));
        c.addView(space(18));
        c.addView(navRow());
        c.addView(space(22));
        c.addView(rockerBlock());
        c.addView(space(22));
        c.addView(colorRow());
        c.addView(space(24));
        c.addView(tabs());

        chip = new TextView(this);
        chip.setTextColor(TXT); chip.setTextSize(15); chip.setTypeface(Typeface.DEFAULT_BOLD);
        chip.setPadding(dp(18), dp(10), dp(18), dp(10));
        chip.setBackground(round(0xF0263252, dp(24), 0x553D7BFF));
        chip.setElevation(dp(10));
        chip.setAlpha(0f);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        clp.topMargin = dp(70);
        root.addView(chip, clp);

        overlay = buildDiscovery();
        overlay.setVisibility(View.GONE);
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        return root;
    }

    private View header() {
        LinearLayout h = hbox();
        h.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles = vbox();
        titles.addView(text("שלט סמסונג", 26, TXT, true));
        LinearLayout st = hbox();
        st.setGravity(Gravity.CENTER_VERTICAL);
        statusDot = new TextView(this);
        statusDot.setBackground(round(ORANGE, dp(5), 0));
        st.addView(statusDot, new LinearLayout.LayoutParams(dp(10), dp(10)));
        statusText = text("", 13, SUB, false);
        statusText.setPadding(dp(8), 0, 0, 0);
        statusText.setMaxLines(2);
        st.addView(statusText, new LinearLayout.LayoutParams(0, -2, 1f));
        titles.addView(st);
        tvNameText = text(prefs.getString("tvname", ""), 12, 0xFF5E6A8A, false);
        titles.addView(tvNameText);
        h.addView(titles, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView find = pill("📡  חפש טלוויזיה", ACC, ACC2);
        find.setTextSize(13);
        find.setPadding(dp(14), dp(10), dp(14), dp(10));
        fx(find, null, false, this::openDiscovery);
        h.addView(find, new LinearLayout.LayoutParams(-2, -2));
        return h;
    }

    private View powerRow() {
        LinearLayout r = hbox();
        r.setGravity(Gravity.CENTER_VERTICAL);
        TextView power = circle("⏻", 28, RED, 0xFFB3262B);
        fx(power, null, false, this::power);
        r.addView(power, new LinearLayout.LayoutParams(dp(64), dp(64)));
        r.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        r.addView(keyPill("מקור", "KEY_SOURCE", "⎆  מקור"), new LinearLayout.LayoutParams(dp(110), dp(52)));
        r.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        TextView mute = circle("🔇", 22, SURF2, SURF);
        key(mute, "KEY_MUTE", "השתקה", false);
        r.addView(mute, new LinearLayout.LayoutParams(dp(64), dp(64)));
        return r;
    }

    private View dpad() {
        FrameLayout f = new FrameLayout(this);
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColors(new int[]{SURF2, SURF});
        ring.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        ring.setGradientRadius(dp(180));
        ring.setStroke(dp(1), STROKE);
        f.setBackground(ring);
        f.setElevation(dp(6));
        String[][] arrows = {{"▲", "KEY_UP", "למעלה"}, {"▼", "KEY_DOWN", "למטה"}, {"◀", "KEY_LEFT", "שמאלה"}, {"▶", "KEY_RIGHT", "ימינה"}};
        int[] grav = {Gravity.TOP | Gravity.CENTER_HORIZONTAL, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL,
                Gravity.LEFT | Gravity.CENTER_VERTICAL, Gravity.RIGHT | Gravity.CENTER_VERTICAL};
        for (int i = 0; i < 4; i++) {
            TextView a = ghost(arrows[i][0], 22);
            key(a, arrows[i][1], arrows[i][2], true);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(78), dp(78), grav[i]);
            lp.setMargins(dp(6), dp(6), dp(6), dp(6));
            f.addView(a, lp);
        }
        TextView ok = circle("OK", 20, ACC, ACC2);
        ok.setElevation(dp(8));
        key(ok, "KEY_ENTER", "אישור", false);
        f.addView(ok, new FrameLayout.LayoutParams(dp(100), dp(100), Gravity.CENTER));
        return f;
    }

    private View navRow() {
        LinearLayout r = hbox();
        r.setGravity(Gravity.CENTER);
        String[][] items = {{"↩", "KEY_RETURN", "חזור"}, {"⌂", "KEY_HOME", "בית"}, {"☰", "KEY_MENU", "תפריט"}, {"✕", "KEY_EXIT", "יציאה"}};
        for (String[] it : items) {
            LinearLayout col = vbox();
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            TextView b = circle(it[0], 22, SURF2, SURF);
            key(b, it[1], it[2], false);
            col.addView(b, new LinearLayout.LayoutParams(dp(58), dp(58)));
            TextView l = text(it[2], 11, SUB, false);
            l.setGravity(Gravity.CENTER);
            l.setPadding(0, dp(5), 0, 0);
            col.addView(l);
            r.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        }
        return r;
    }

    private View rockerBlock() {
        LinearLayout r = hbox();
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.addView(rocker("VOL", "KEY_VOLUP", "KEY_VOLDOWN", "ווליום +", "ווליום −"), new LinearLayout.LayoutParams(dp(74), dp(196)));
        LinearLayout mid = vbox();
        mid.setGravity(Gravity.CENTER);
        String[][] grid = {{"מדריך", "KEY_GUIDE"}, {"מידע", "KEY_INFO"}, {"כלים", "KEY_TOOLS"}, {"ערוצים", "KEY_CH_LIST"}, {"קודם", "KEY_PRECH"}, {"HDMI", "KEY_HDMI"}};
        for (int i = 0; i < grid.length; i += 2) {
            LinearLayout row = hbox();
            for (int j = 0; j < 2; j++) {
                TextView t = surfacePill(grid[i + j][0]);
                key(t, grid[i + j][1], grid[i + j][0], false);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(52), 1f);
                lp.setMargins(dp(4), dp(4), dp(4), dp(4));
                row.addView(t, lp);
            }
            mid.addView(row);
        }
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(0, -2, 1f);
        mlp.setMargins(dp(10), 0, dp(10), 0);
        r.addView(mid, mlp);
        r.addView(rocker("CH", "KEY_CHUP", "KEY_CHDOWN", "ערוץ ▲", "ערוץ ▼"), new LinearLayout.LayoutParams(dp(74), dp(196)));
        return r;
    }

    private View rocker(String label, String upKey, String downKey, String upLabel, String downLabel) {
        LinearLayout v = vbox();
        v.setGravity(Gravity.CENTER_HORIZONTAL);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{SURF2, SURF});
        g.setCornerRadius(dp(37));
        g.setStroke(dp(1), STROKE);
        v.setBackground(g);
        v.setElevation(dp(4));
        TextView up = ghost(label.equals("VOL") ? "+" : "▲", 26);
        key(up, upKey, upLabel, true);
        v.addView(up, new LinearLayout.LayoutParams(-1, 0, 1f));
        TextView l = text(label, 12, SUB, true);
        l.setGravity(Gravity.CENTER);
        v.addView(l, new LinearLayout.LayoutParams(-1, -2));
        TextView dn = ghost(label.equals("VOL") ? "−" : "▼", 26);
        key(dn, downKey, downLabel, true);
        v.addView(dn, new LinearLayout.LayoutParams(-1, 0, 1f));
        return v;
    }

    private View colorRow() {
        LinearLayout r = hbox();
        String[] names = {"A", "B", "C", "D"};
        String[] keys = {"KEY_RED", "KEY_GREEN", "KEY_YELLOW", "KEY_CYAN"};
        int[] cols = {0xFFE5484D, 0xFF30A46C, 0xFFE2B200, 0xFF2F6BE0};
        for (int i = 0; i < 4; i++) {
            TextView t = pill(names[i], cols[i], darker(cols[i]));
            t.setTextSize(15);
            key(t, keys[i], "מקש " + names[i], false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(40), 1f);
            lp.setMargins(dp(5), 0, dp(5), 0);
            r.addView(t, lp);
        }
        return r;
    }

    private final List<TextView> tabBtns = new ArrayList<>();
    private final List<View> tabPages = new ArrayList<>();

    private View tabs() {
        LinearLayout wrap = vbox();
        LinearLayout bar = hbox();
        bar.setBackground(round(SURF, dp(16), STROKE));
        bar.setPadding(dp(4), dp(4), dp(4), dp(4));
        String[] names = {"מספרים", "מדיה", "אפליקציות", "עוד"};
        FrameLayout pages = new FrameLayout(this);
        tabPages.add(numbersPage());
        tabPages.add(mediaPage());
        tabPages.add(appsPage());
        tabPages.add(morePage());
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            TextView t = text(names[i], 14, SUB, true);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(10), 0, dp(10));
            fx(t, null, false, () -> selectTab(idx));
            tabBtns.add(t);
            bar.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
            pages.addView(tabPages.get(i));
        }
        wrap.addView(bar);
        wrap.addView(space(14));
        wrap.addView(pages);
        selectTab(0);
        return wrap;
    }

    private void selectTab(int idx) {
        for (int i = 0; i < tabBtns.size(); i++) {
            boolean on = i == idx;
            tabBtns.get(i).setTextColor(on ? TXT : SUB);
            tabBtns.get(i).setBackground(on ? grad(ACC, ACC2, dp(12)) : null);
            View p = tabPages.get(i);
            if (on) { p.setVisibility(View.VISIBLE); p.setAlpha(0f); p.setTranslationY(dp(12)); p.animate().alpha(1f).translationY(0).setDuration(220).start(); }
            else p.setVisibility(View.GONE);
        }
    }

    private View numbersPage() {
        LinearLayout v = vbox();
        String[][] rows = {{"1", "2", "3"}, {"4", "5", "6"}, {"7", "8", "9"}, {"-", "0", "↺"}};
        String[][] keys = {{"KEY_1", "KEY_2", "KEY_3"}, {"KEY_4", "KEY_5", "KEY_6"}, {"KEY_7", "KEY_8", "KEY_9"}, {"KEY_PLUS100", "KEY_0", "KEY_PRECH"}};
        for (int i = 0; i < rows.length; i++) {
            LinearLayout r = hbox();
            for (int j = 0; j < 3; j++) {
                TextView t = surfacePill(rows[i][j]);
                t.setTextSize(22);
                key(t, keys[i][j], rows[i][j], false);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(60), 1f);
                lp.setMargins(dp(5), dp(5), dp(5), dp(5));
                r.addView(t, lp);
            }
            v.addView(r);
        }
        return v;
    }

    private View mediaPage() {
        LinearLayout v = vbox();
        LinearLayout r1 = hbox();
        String[][] m = {{"⏪", "KEY_REWIND", "הרצה אחורה"}, {"▶", "KEY_PLAY", "נגן"}, {"⏸", "KEY_PAUSE", "השהה"}, {"⏩", "KEY_FF", "הרצה קדימה"}};
        for (String[] it : m) {
            TextView t = circle(it[0], 22, SURF2, SURF);
            key(t, it[1], it[2], false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(64), 1f);
            lp.setMargins(dp(6), dp(6), dp(6), dp(6));
            r1.addView(t, lp);
        }
        v.addView(r1);
        LinearLayout r2 = hbox();
        String[][] m2 = {{"⏹  עצור", "KEY_STOP"}, {"⏺  הקלט", "KEY_REC"}, {"כתוביות", "KEY_SUB_TITLE"}};
        for (String[] it : m2) {
            TextView t = surfacePill(it[0]);
            key(t, it[1], it[0], false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(52), 1f);
            lp.setMargins(dp(5), dp(5), dp(5), dp(5));
            r2.addView(t, lp);
        }
        v.addView(r2);
        return v;
    }

    private View appsPage() {
        LinearLayout v = vbox();
        LinearLayout r = null;
        for (int i = 0; i < APPS.length; i++) {
            if (i % 3 == 0) { r = hbox(); v.addView(r); }
            final String[] a = APPS[i];
            int col = Color.parseColor(a[3]);
            TextView t = pill(a[0], col, darker(col));
            t.setTextSize(14);
            fx(t, "פותח " + a[0], false, () -> launch(a));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(66), 1f);
            lp.setMargins(dp(5), dp(5), dp(5), dp(5));
            r.addView(t, lp);
        }
        return v;
    }

    private View morePage() {
        LinearLayout v = vbox();
        v.addView(sectionTitle("⌨  שליחת טקסט לטלוויזיה"));
        LinearLayout tr = hbox();
        EditText txt = field("הקלד לחיפוש בטלוויזיה");
        tr.addView(txt, new LinearLayout.LayoutParams(0, dp(52), 1f));
        TextView send = pill("שלח", ACC, ACC2);
        fx(send, "טקסט נשלח", false, () -> {
            String s = txt.getText().toString();
            bg.execute(() -> { if (!tv.sendText(s)) flash("שליחת טקסט דורשת חיבור Wi-Fi", false); });
        });
        tr.addView(send, slp2());
        v.addView(tr);

        v.addView(sectionTitle("⚙  כל המקשים המתקדמים"));
        String[][] extra = {
            {"HDMI 1", "KEY_HDMI1"}, {"HDMI 2", "KEY_HDMI2"}, {"HDMI 3", "KEY_HDMI3"},
            {"HDMI 4", "KEY_HDMI4"}, {"טלוויזיה", "KEY_TV"}, {"אנטנה", "KEY_ANTENA"},
            {"יחס תמונה", "KEY_PICTURE_SIZE"}, {"מצב תמונה", "KEY_PMODE"}, {"טיימר שינה", "KEY_SLEEP"},
            {"תיאור קולי", "KEY_AD"}, {"מדריך למשתמש", "KEY_E_MANUAL"}, {"Ambient", "KEY_AMBIENT"},
            {"Bixby", "KEY_BT_VOICE"}, {"מקלדת", "KEY_KEYBOARD"}, {"תוכן", "KEY_CONTENTS"},
            {"שפת שמע", "KEY_MTS"}, {"עמוד ▲", "KEY_PAGE_UP"}, {"עמוד ▼", "KEY_PAGE_DOWN"},
            {"זום", "KEY_ZOOM1"}, {"מועדפים", "KEY_FAVCH"}, {"3D", "KEY_3D"},
            {"כבה", "KEY_POWEROFF"}, {"הדלק", "KEY_POWERON"}, {"Teletext", "KEY_TTX_MIX"},
        };
        LinearLayout r = null;
        for (int i = 0; i < extra.length; i++) {
            if (i % 3 == 0) { r = hbox(); v.addView(r); }
            TextView t = surfacePill(extra[i][0]);
            t.setTextSize(13);
            key(t, extra[i][1], extra[i][0], false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(48), 1f);
            lp.setMargins(dp(4), dp(4), dp(4), dp(4));
            r.addView(t, lp);
        }

        v.addView(sectionTitle("🔧  שליחת קוד מקש ידני"));
        LinearLayout ck = hbox();
        EditText kf = field("למשל KEY_FACTORY");
        ck.addView(kf, new LinearLayout.LayoutParams(0, dp(52), 1f));
        TextView ks = pill("שלח", ACC, ACC2);
        fx(ks, null, false, () -> { String k = kf.getText().toString().trim().toUpperCase(Locale.ROOT); send(k, k); });
        ck.addView(ks, slp2());
        v.addView(ck);
        return v;
    }

    private LinearLayout.LayoutParams slp2() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(80), dp(52));
        lp.setMargins(dp(8), 0, 0, 0);
        return lp;
    }

    // ============================ DISCOVERY ============================
    private FrameLayout buildDiscovery() {
        FrameLayout o = new FrameLayout(this);
        o.setBackgroundColor(0xCC000000);
        o.setClickable(true);

        ScrollView sv = new ScrollView(this);
        LinearLayout card = vbox();
        card.setPadding(dp(20), dp(20), dp(20), dp(20));
        GradientDrawable cg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xFF1E2640, 0xFF141A2C});
        cg.setCornerRadius(dp(28));
        cg.setStroke(dp(1), STROKE);
        card.setBackground(cg);
        card.setElevation(dp(16));
        sv.addView(card);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER);
        lp.setMargins(dp(16), dp(40), dp(16), dp(40));
        o.addView(sv, lp);

        LinearLayout top = hbox();
        top.setGravity(Gravity.CENTER_VERTICAL);
        scanTitle = text("חיפוש טלוויזיות ב-Wi-Fi", 20, TXT, true);
        top.addView(scanTitle, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView close = circle("✕", 16, SURF2, SURF);
        fx(close, null, false, this::closeDiscovery);
        top.addView(close, new LinearLayout.LayoutParams(dp(40), dp(40)));
        card.addView(top);

        TextView hint = text("ודא שהטלפון והטלוויזיה מחוברים לאותה רשת Wi-Fi ושהטלוויזיה דולקת", 13, SUB, false);
        hint.setPadding(0, dp(6), 0, dp(12));
        card.addView(hint);

        scanBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        scanBar.setIndeterminate(true);
        scanBar.setIndeterminateTintList(ColorStateList.valueOf(ACC));
        card.addView(scanBar, new LinearLayout.LayoutParams(-1, dp(6)));
        card.addView(space(10));

        resultsList = vbox();
        card.addView(resultsList);

        TextView again = pill("🔄  חפש שוב", ACC, ACC2);
        fx(again, null, false, this::startScan);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-1, dp(52));
        alp.setMargins(0, dp(12), 0, 0);
        card.addView(again, alp);

        card.addView(sectionTitle("או הזן כתובת IP ידנית"));
        LinearLayout mr = hbox();
        manualIp = field("192.168.1.50");
        manualIp.setInputType(InputType.TYPE_CLASS_PHONE);
        manualIp.setText(prefs.getString("ip", ""));
        mr.addView(manualIp, new LinearLayout.LayoutParams(0, dp(52), 1f));
        TextView go = pill("התחבר", ACC, ACC2);
        fx(go, null, false, () -> {
            String ip = manualIp.getText().toString().trim();
            if (ip.isEmpty()) return;
            prefs.edit().putString("ip", ip).apply();
            closeDiscovery(); connect();
        });
        mr.addView(go, slp2());
        card.addView(mr);
        TextView ipHelp = text("בטלוויזיה: הגדרות ← כללי ← רשת ← מצב רשת ← הגדרות IP", 11, 0xFF5E6A8A, false);
        ipHelp.setPadding(0, dp(6), 0, 0);
        card.addView(ipHelp);

        card.addView(sectionTitle("הגדרות חיבור"));
        LinearLayout sr = hbox();
        modeBtn = surfacePill(modeLabel());
        modeBtn.setTextSize(13);
        fx(modeBtn, null, false, this::cycleMode);
        LinearLayout.LayoutParams m1 = new LinearLayout.LayoutParams(0, dp(50), 1f);
        m1.setMargins(0, 0, dp(4), 0);
        sr.addView(modeBtn, m1);
        TextView test = surfacePill("🩺  בדיקת כל השיטות");
        test.setTextSize(13);
        fx(test, null, false, this::testAll);
        LinearLayout.LayoutParams m2 = new LinearLayout.LayoutParams(0, dp(50), 1f);
        m2.setMargins(dp(4), 0, 0, 0);
        sr.addView(test, m2);
        card.addView(sr);

        card.addView(sectionTitle("כתובת MAC להדלקה דרך Wi-Fi (מתמלאת אוטומטית)"));
        LinearLayout macRow = hbox();
        macField = field("AA:BB:CC:DD:EE:FF");
        macField.setText(prefs.getString("mac", ""));
        macRow.addView(macField, new LinearLayout.LayoutParams(0, dp(52), 1f));
        TextView save = surfacePill("שמור");
        fx(save, "נשמר", false, () -> prefs.edit().putString("mac", macField.getText().toString().trim()).apply());
        macRow.addView(save, slp2());
        card.addView(macRow);
        return o;
    }

    private void openDiscovery() {
        overlay.setVisibility(View.VISIBLE);
        overlay.setAlpha(0f);
        overlay.animate().alpha(1f).setDuration(200).start();
        startScan();
    }

    private void closeDiscovery() {
        scanning = false;
        overlay.animate().alpha(0f).setDuration(180).withEndAction(() -> overlay.setVisibility(View.GONE)).start();
    }

    private void startScan() {
        if (scanning) return;
        scanning = true;
        seen.clear();
        resultsList.removeAllViews();
        scanBar.setVisibility(View.VISIBLE);
        scanTitle.setText("מחפש טלוויזיות...");
        final CountDownLatch done = new CountDownLatch(2);
        new Thread(() -> { try { ssdpSearch(); } finally { done.countDown(); } }).start();
        new Thread(() -> { try { subnetScan(); } finally { done.countDown(); } }).start();
        new Thread(() -> {
            try { done.await(45, TimeUnit.SECONDS); Thread.sleep(1500); } catch (InterruptedException ignored) {}
            scanning = false;
            ui.post(() -> {
                scanBar.setVisibility(View.INVISIBLE);
                if (resultsList.getChildCount() == 0) {
                    scanTitle.setText("לא נמצאו טלוויזיות");
                    TextView t = text("נסה: להדליק את הטלוויזיה, לבדוק שהיא באותה רשת, או להזין IP ידנית למטה", 13, SUB, false);
                    t.setPadding(0, dp(8), 0, dp(4));
                    resultsList.addView(t);
                } else scanTitle.setText("נמצאו " + resultsList.getChildCount() + " טלוויזיות");
            });
        }).start();
    }

    private void ssdpSearch() {
        WifiManager.MulticastLock lock = null;
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wm != null) { lock = wm.createMulticastLock("samsung-ssdp"); lock.setReferenceCounted(false); lock.acquire(); }
            DatagramSocket ds = new DatagramSocket();
            ds.setSoTimeout(800);
            InetAddress group = InetAddress.getByName("239.255.255.250");
            String[] sts = {"urn:samsung.com:device:RemoteControlReceiver:1", "urn:dial-multiscreen-org:service:dial:1",
                    "urn:schemas-upnp-org:device:MediaRenderer:1", "ssdp:all"};
            for (int rep = 0; rep < 2; rep++)
                for (String st : sts) {
                    byte[] m = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: " + st + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
                    ds.send(new DatagramPacket(m, m.length, group, 1900));
                }
            long end = System.currentTimeMillis() + 5000;
            byte[] buf = new byte[2048];
            while (System.currentTimeMillis() < end && scanning) {
                try {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    ds.receive(p);
                    String s = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
                    if (s.contains("samsung") || s.contains("tizen")) {
                        final String ip = p.getAddress().getHostAddress();
                        bg2.execute(() -> probe(ip, true));
                    }
                } catch (SocketTimeoutException ignored) {}
            }
            ds.close();
        } catch (Exception ignored) {
        } finally { if (lock != null) try { lock.release(); } catch (Exception ignored) {} }
    }

    private void subnetScan() {
        List<String> prefixes = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses()))
                    if (a instanceof Inet4Address) {
                        String h = a.getHostAddress();
                        String pre = h.substring(0, h.lastIndexOf('.') + 1);
                        if (!prefixes.contains(pre)) prefixes.add(pre);
                    }
            }
        } catch (Exception ignored) {}
        ExecutorService pool = Executors.newFixedThreadPool(80);
        for (String pre : prefixes)
            for (int i = 1; i < 255; i++) {
                final String ip = pre + i;
                pool.execute(() -> { if (scanning) probe(ip, false); });
            }
        pool.shutdown();
        try { pool.awaitTermination(40, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
    }

    private void probe(String ip, boolean fromSsdp) {
        if (seen.contains(ip)) return;
        boolean tizen = portOpen(ip, 8001, 500) || portOpen(ip, 8002, 500);
        boolean legacy = !tizen && portOpen(ip, 55000, 500);
        if (!tizen && !legacy && !fromSsdp) return;
        if (!seen.add(ip)) return;
        String info = tizen ? TvLink.http("GET", "http://" + ip + ":8001/api/v2/") : null;
        String name = TvLink.jsonField(info, "name");
        String model = TvLink.jsonField(info, "modelName");
        String mac = TvLink.jsonField(info, "wifiMac");
        String kind = tizen ? "Tizen · Wi-Fi" : legacy ? "דגם 2010-2015" : "Samsung";
        final String n = name != null ? name.replace("[TV] ", "") : "Samsung TV";
        final String sub = (model != null ? model + " · " : "") + ip;
        ui.post(() -> addResult(ip, n, sub, kind, mac));
    }

    private void addResult(String ip, String name, String sub, String kind, String mac) {
        LinearLayout card = hbox();
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setBackground(ripple(round(SURF2, dp(18), STROKE), dp(18)));
        TextView icon = circle("📺", 22, ACC, ACC2);
        card.addView(icon, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout t = vbox();
        t.setPadding(dp(12), 0, dp(12), 0);
        t.addView(text(name, 16, TXT, true));
        t.addView(text(sub, 12, SUB, false));
        card.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView k = text(kind, 11, GREEN, true);
        k.setPadding(dp(10), dp(4), dp(10), dp(4));
        k.setBackground(round(0x2230C67C, dp(10), 0));
        card.addView(k);
        fx(card, "מתחבר ל-" + name, false, () -> {
            SharedPreferences.Editor e = prefs.edit().putString("ip", ip).putString("tvname", name + " · " + sub);
            if (mac != null && !mac.isEmpty()) e.putString("mac", mac);
            e.apply();
            manualIp.setText(ip);
            if (mac != null) macField.setText(mac);
            tvNameText.setText(name + " · " + sub);
            closeDiscovery();
            connect();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(6), 0, dp(6));
        card.setAlpha(0f); card.setTranslationY(dp(16));
        resultsList.addView(card, lp);
        card.animate().alpha(1f).translationY(0).setDuration(260).start();
        scanTitle.setText("נמצאו " + resultsList.getChildCount() + " טלוויזיות" + (scanning ? "..." : ""));
    }

    // ============================ LOGIC ============================
    private void connect() {
        String ip = prefs.getString("ip", "");
        if (ip.isEmpty()) { refreshStatus("לא נבחרה טלוויזיה"); return; }
        refreshStatus("מתחבר ל-" + ip + "...");
        bg.execute(() -> tv.connect(ip, prefs.getString("token", "")));
    }

    private String modeLabel() { return sendMode == 0 ? "מצב: אוטומטי" : sendMode == 1 ? "מצב: Wi-Fi בלבד" : "מצב: אינפרא-אדום"; }

    private void cycleMode() {
        sendMode = (sendMode + 1) % 3;
        modeBtn.setText(modeLabel());
        if (sendMode == 2 && !hasIr()) flash("לטלפון הזה אין משדר אינפרא-אדום", false);
    }

    private void send(String key, String label) {
        if (key == null || key.isEmpty()) return;
        if (sendMode != 2 && tv.isConnected()) {
            flash(label, true);
            bg.execute(() -> {
                if (!tv.sendKey(key, "Click")) {
                    if (sendMode == 0 && sendIr(key)) return;
                    flash("השליחה נכשלה – מתחבר מחדש", false);
                    connect();
                }
            });
            return;
        }
        if (sendMode != 1 && sendIr(key)) { flash(label + " · IR", true); return; }
        if (sendMode == 2) { flash(hasIr() ? "אין קוד IR למקש הזה" : "אין משדר IR בטלפון", false); return; }
        flash("לא מחובר – מתחבר...", false);
        connect();
    }

    private boolean sendIr(String key) {
        Integer cmd = IR.get(key);
        if (cmd == null || !hasIr()) return false;
        try { ir.transmit(38000, samsung32(0x07, cmd)); return true; } catch (Exception e) { return false; }
    }

    private static int[] samsung32(int addr, int cmd) {
        int[] p = new int[67];
        int i = 0;
        p[i++] = 4500; p[i++] = 4500;
        int data = addr | (addr << 8) | (cmd << 16) | ((~cmd & 0xFF) << 24);
        for (int bit = 0; bit < 32; bit++) { p[i++] = 560; p[i++] = ((data >>> bit) & 1) == 1 ? 1690 : 560; }
        p[i] = 560;
        return p;
    }

    private void power() {
        if (tv.isConnected() && sendMode != 2) { send("KEY_POWER", "הפעלה / כיבוי"); return; }
        String mac = prefs.getString("mac", "");
        String ip = prefs.getString("ip", "");
        boolean irOk = sendMode != 1 && sendIr("KEY_POWER");
        if (irOk) flash("הפעלה · IR", true);
        if (sendMode != 2 && !mac.isEmpty()) {
            flash("שולח פקודת הדלקה ברשת...", true);
            bg.execute(() -> {
                try { TvLink.wakeOnLan(mac, ip.isEmpty() ? null : ip); } catch (Exception e) { flash("שגיאה: " + e.getMessage(), false); }
                try { Thread.sleep(6000); } catch (InterruptedException ignored) {}
                tv.connect(ip, prefs.getString("token", ""));
            });
        } else if (!irOk) {
            flash("להדלקה צריך MAC (מתמלא בחיפוש) או IR", false);
        }
    }

    private void launch(String[] app) {
        bg.execute(() -> {
            boolean ok = tv.launchApp(app[1]);
            if (!app[2].equals(app[1])) ok |= tv.launchApp(app[2]);
            if (!ok) flash("לא הצלחתי לפתוח את " + app[0], false);
        });
    }

    private void testAll() {
        String ip = prefs.getString("ip", "");
        flash("בודק...", true);
        bg.execute(() -> {
            StringBuilder sb = new StringBuilder();
            if (!ip.isEmpty()) {
                String info = TvLink.http("GET", "http://" + ip + ":8001/api/v2/");
                sb.append("REST 8001 (Tizen 2016+): ").append(info != null ? "✓ " + nz(TvLink.jsonField(info, "modelName")) : "✗").append('\n');
                sb.append("WSS 8002 (2018+): ").append(portOpen(ip, 8002, 1000) ? "✓" : "✗").append('\n');
                sb.append("WS 8001: ").append(portOpen(ip, 8001, 1000) ? "✓" : "✗").append('\n');
                sb.append("ישן 55000 (2010-2015): ").append(portOpen(ip, 55000, 1000) ? "✓" : "✗").append('\n');
            } else sb.append("לא נבחרה טלוויזיה\n");
            sb.append("משדר IR בטלפון: ").append(hasIr() ? "✓" : "✗").append('\n');
            sb.append("MAC להדלקה: ").append(prefs.getString("mac", "").isEmpty() ? "✗" : "✓").append('\n');
            sb.append("חיבור נוכחי: ").append(tv.modeName());
            String res = sb.toString();
            ui.post(() -> new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("תוצאות בדיקה").setMessage(res)
                    .setPositiveButton("התחבר עכשיו", (d, w) -> connect()).setNegativeButton("סגור", null).show());
        });
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static boolean portOpen(String ip, int port, int timeout) {
        try (Socket s = new Socket()) { s.connect(new InetSocketAddress(ip, port), timeout); return true; }
        catch (Exception e) { return false; }
    }

    // ============================ FEEDBACK ============================
    private void haptic() {
        try {
            if (vib == null) return;
            if (Build.VERSION.SDK_INT >= 29) vib.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK));
            else vib.vibrate(VibrationEffect.createOneShot(18, 160));
        } catch (Exception ignored) {}
    }

    private final Runnable hideChip = new Runnable() {
        @Override public void run() { chip.animate().alpha(0f).translationY(-dp(10)).setDuration(220).start(); }
    };

    private void flash(String msg, boolean ok) {
        ui.post(() -> {
            chip.setText((ok ? "✓  " : "✕  ") + msg);
            chip.setBackground(round(ok ? 0xF0213A2E : 0xF03A2125, dp(24), ok ? 0x6630C67C : 0x66E5484D));
            ui.removeCallbacks(hideChip);
            chip.animate().cancel();
            chip.setTranslationY(-dp(10));
            chip.setScaleX(0.9f); chip.setScaleY(0.9f);
            chip.animate().alpha(1f).translationY(0).scaleX(1f).scaleY(1f).setDuration(160).start();
            ui.postDelayed(hideChip, 900);
        });
    }

    private void key(View v, String k, String label, boolean repeat) {
        fx(v, null, repeat, () -> send(k, label));
    }

    private void fx(View v, String flashMsg, boolean repeat, Runnable action) {
        final ScheduledExecutorService[] rep = new ScheduledExecutorService[1];
        v.setClickable(true);
        v.setOnTouchListener((view, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    view.drawableHotspotChanged(e.getX(), e.getY());
                    view.setPressed(true);
                    view.animate().cancel();
                    view.animate().scaleX(0.88f).scaleY(0.88f).alpha(0.85f).setDuration(70).start();
                    haptic();
                    if (repeat) {
                        action.run();
                        rep[0] = Executors.newSingleThreadScheduledExecutor();
                        rep[0].scheduleAtFixedRate(() -> ui.post(() -> { haptic(); action.run(); }), 450, 140, TimeUnit.MILLISECONDS);
                    }
                    return true;
                case MotionEvent.ACTION_MOVE:
                    view.drawableHotspotChanged(e.getX(), e.getY());
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    view.setPressed(false);
                    view.animate().scaleX(1f).scaleY(1f).alpha(1f).setInterpolator(new OvershootInterpolator(4f)).setDuration(260).start();
                    if (rep[0] != null) { rep[0].shutdownNow(); rep[0] = null; }
                    boolean inside = e.getX() >= 0 && e.getY() >= 0 && e.getX() <= view.getWidth() && e.getY() <= view.getHeight();
                    if (!repeat && e.getActionMasked() == MotionEvent.ACTION_UP && inside) {
                        if (flashMsg != null) flash(flashMsg, true);
                        action.run();
                    }
                    return true;
            }
            return false;
        });
    }

    // ============================ VIEW FACTORY ============================
    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }
    private LinearLayout vbox() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout hbox() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); return l; }
    private View space(int h) { View s = new View(this); s.setLayoutParams(new LinearLayout.LayoutParams(1, dp(h))); return s; }
    private LinearLayout.LayoutParams centered(int w, int h) { LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h); lp.gravity = Gravity.CENTER_HORIZONTAL; return lp; }

    private TextView text(String s, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(size); t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        return t;
    }

    private TextView sectionTitle(String s) {
        TextView t = text(s, 13, 0xFF9DB4FF, true);
        t.setPadding(dp(2), dp(18), 0, dp(8));
        return t;
    }

    private static int darker(int c) {
        return Color.rgb((int) (Color.red(c) * 0.7), (int) (Color.green(c) * 0.7), (int) (Color.blue(c) * 0.7));
    }

    private GradientDrawable round(int color, int radius, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color); g.setCornerRadius(radius);
        if (stroke != 0) g.setStroke(dp(1), stroke);
        return g;
    }

    private GradientDrawable grad(int c1, int c2, int radius) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{c1, c2});
        g.setCornerRadius(radius);
        return g;
    }

    private Drawable ripple(Drawable content, int radius) {
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE); mask.setCornerRadius(radius);
        return new RippleDrawable(ColorStateList.valueOf(0x55FFFFFF), content, mask);
    }

    private TextView base(String s, int size) {
        TextView t = text(s, size, TXT, true);
        t.setGravity(Gravity.CENTER);
        t.setMaxLines(2);
        return t;
    }

    private TextView pill(String s, int c1, int c2) {
        TextView t = base(s, 15);
        t.setBackground(ripple(grad(c1, c2, dp(18)), dp(18)));
        t.setElevation(dp(4));
        return t;
    }

    private TextView surfacePill(String s) {
        TextView t = base(s, 15);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{SURF2, SURF});
        g.setCornerRadius(dp(18));
        g.setStroke(dp(1), STROKE);
        t.setBackground(ripple(g, dp(18)));
        t.setElevation(dp(3));
        return t;
    }

    private TextView keyPill(String label, String k, String text) {
        TextView t = surfacePill(text);
        key(t, k, label, false);
        return t;
    }

    private TextView circle(String s, int size, int c1, int c2) {
        TextView t = base(s, size);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{c1, c2});
        g.setShape(GradientDrawable.OVAL);
        g.setStroke(dp(1), STROKE);
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.OVAL); mask.setColor(Color.WHITE);
        t.setBackground(new RippleDrawable(ColorStateList.valueOf(0x55FFFFFF), g, mask));
        t.setElevation(dp(5));
        return t;
    }

    private TextView ghost(String s, int size) {
        TextView t = base(s, size);
        t.setTextColor(0xFFDCE3F5);
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.OVAL); mask.setColor(Color.WHITE);
        t.setBackground(new RippleDrawable(ColorStateList.valueOf(0x553D7BFF), null, mask));
        return t;
    }

    private EditText field(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setTextColor(TXT); e.setHintTextColor(0xFF5E6A8A);
        e.setTextSize(15);
        e.setSingleLine(true);
        e.setPadding(dp(14), 0, dp(14), 0);
        e.setBackground(round(0xFF0E1322, dp(16), STROKE));
        return e;
    }

    // ============================ STATUS ============================
    private void refreshStatus(String msg) {
        ui.post(() -> {
            boolean on = tv.isConnected();
            boolean busy = msg != null && msg.startsWith("מתחבר");
            statusDot.setBackground(round(on ? GREEN : busy ? ORANGE : RED, dp(5), 0));
            statusText.setText(msg != null ? msg : on ? "מחובר · " + tv.modeName() : "לא מחובר");
            if (on) {
                statusDot.animate().scaleX(1.6f).scaleY(1.6f).setDuration(200)
                        .withEndAction(() -> statusDot.animate().scaleX(1f).scaleY(1f).setDuration(200).start()).start();
            }
        });
    }

    @Override public void onStatus(String msg) {
        refreshStatus(tv.isConnected() ? "מחובר · " + tv.modeName() : msg);
        if (tv.isConnected()) flash("מחובר לטלוויזיה", true);
    }

    @Override public void onToken(String token) { prefs.edit().putString("token", token).apply(); }

    @Override protected void onDestroy() { super.onDestroy(); scanning = false; new Thread(tv::disconnect).start(); }
}
