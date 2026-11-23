package il.remote.samsung;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
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

    private static final int BG = 0xFFF3F5FA, CARD = 0xFFFFFFFF, CARD2 = 0xFFF8F9FD, STROKE = 0xFFE2E6F0;
    private static final int INK = 0xFF2A3052, SUB = 0xFF8B93AD, SOFT = 0xFFC5CBDC;
    private static final int ACC = 0xFF4B5EEA, ACC2 = 0xFF2632B5, RED = 0xFFE5484D, GREEN = 0xFF2FB872, ORANGE = 0xFFF2A11F;

    private TvLink tv;
    private SharedPreferences prefs;
    private ConsumerIrManager ir;
    private Vibrator vib;
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final ExecutorService bg2 = Executors.newFixedThreadPool(8);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private int sendMode = 0;

    private TextView tvTitle, statusDot, chip, modeBtn;
    private FrameLayout overlay, content;
    private LinearLayout resultsList;
    private TextView scanTitle;
    private ProgressBar scanSpin;
    private EditText manualIp, macField;
    private final Set<String> seen = Collections.synchronizedSet(new HashSet<>());
    private volatile boolean scanning = false;

    private final List<View> screens = new ArrayList<>();
    private final List<TextView[]> navItems = new ArrayList<>();

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
            {"KEY_DTV", 0x27}, {"KEY_W_LINK", 0x8C}, {"KEY_ANTENA", 0x36}, {"KEY_COMPONENT1", 0x86}, {"KEY_AV1", 0x84},
        };
        for (Object[] r : t) IR.put((String) r[0], (Integer) r[1]);
    }

    private static final String[][] APPS = {
        {"Netflix", "3201907018807", "11101200001", "#E50914"},
        {"YouTube", "111299001912", "111299001912", "#FF0033"},
        {"Prime Video", "3201910019365", "3201512006785", "#00A8E1"},
        {"Disney+", "3201901017640", "3201901017640", "#113CCF"},
        {"Apple TV", "3201807016597", "3201807016597", "#1C1C1E"},
        {"Spotify", "3201606009684", "3201606009684", "#1DB954"},
        {"Yes+", "3201908019041", "3201908019041", "#0072CE"},
        {"Cellcom TV", "3201812017479", "3201812017479", "#6C2DC7"},
        {"Partner TV", "3201910019279", "3201910019279", "#00A99D"},
        {"כאן 11", "3202011022131", "3202011022131", "#E8A200"},
        {"דפדפן", "org.tizen.browser", "3201907018784", "#4A5568"},
        {"Plex", "3201512006963", "3201512006963", "#D9930B"},
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("tv", MODE_PRIVATE);
        tv = new TvLink(this);
        try { ir = (ConsumerIrManager) getSystemService(CONSUMER_IR_SERVICE); } catch (Exception ignored) {}
        vib = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(CARD);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        setContentView(buildRoot());
        String ip = prefs.getString("ip", "");
        if (!ip.isEmpty()) connect();
        else { setDot(RED); ui.postDelayed(this::openDiscovery, 300); }
    }

    private boolean hasIr() { try { return ir != null && ir.hasIrEmitter(); } catch (Exception e) { return false; } }

    private View buildRoot() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);

        LinearLayout col = vbox();
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));
        col.addView(topBar());

        content = new FrameLayout(this);
        col.addView(content, new LinearLayout.LayoutParams(-1, 0, 1f));
        screens.add(scroll(remoteScreen()));
        screens.add(scroll(hdmiScreen()));
        screens.add(scroll(appsScreen()));
        screens.add(scroll(settingsScreen()));
        for (View s : screens) content.addView(s, new FrameLayout.LayoutParams(-1, -1));

        col.addView(bottomNav());
        selectScreen(0);

        chip = new TextView(this);
        chip.setTextColor(Color.WHITE); chip.setTextSize(14); chip.setTypeface(Typeface.DEFAULT_BOLD);
        chip.setPadding(dp(18), dp(10), dp(18), dp(10));
        chip.setElevation(dp(12));
        chip.setAlpha(0f);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        clp.topMargin = dp(78);
        root.addView(chip, clp);

        overlay = buildDeviceScreen();
        overlay.setVisibility(View.GONE);
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        return root;
    }

    private View scroll(View v) {
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.setFillViewport(true);
        sv.addView(v);
        return sv;
    }

    private View topBar() {
        LinearLayout h = hbox();
        h.setGravity(Gravity.CENTER_VERTICAL);
        h.setPadding(dp(16), dp(10), dp(16), dp(10));

        TextView cast = iconBtn("📡", 20);
        fx(cast, null, false, this::openDiscovery);
        h.addView(cast, new LinearLayout.LayoutParams(dp(48), dp(48)));

        tvTitle = text(titleText(), 18, INK, true);
        tvTitle.setSingleLine(true);
        tvTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tvTitle.setPadding(dp(12), 0, dp(8), 0);
        fx(tvTitle, null, false, this::openDiscovery);
        h.addView(tvTitle, new LinearLayout.LayoutParams(0, -2, 1f));

        statusDot = new TextView(this);
        h.addView(statusDot, new LinearLayout.LayoutParams(dp(10), dp(10)));
        setDot(ORANGE);

        View gap = new View(this);
        h.addView(gap, new LinearLayout.LayoutParams(dp(12), 1));

        TextView power = text("⏻", 26, RED, true);
        power.setGravity(Gravity.CENTER);
        GradientDrawable pg = new GradientDrawable();
        pg.setShape(GradientDrawable.OVAL); pg.setColor(CARD); pg.setStroke(dp(1), STROKE);
        power.setBackground(rippleOval(pg, 0x33E5484D));
        power.setElevation(dp(6));
        fx(power, null, false, this::power);
        h.addView(power, new LinearLayout.LayoutParams(dp(56), dp(56)));
        return h;
    }

    private String titleText() {
        String n = prefs.getString("tvname", "");
        return n.isEmpty() ? "בחר טלוויזיה" : n;
    }

    private LinearLayout pagesHolder;
    private final List<View> pages = new ArrayList<>();
    private final List<View> dots = new ArrayList<>();
    private int pageIdx = 0;
    private FrameLayout padHolder;
    private View dpadView, touchView;
    private TextView segPad, segTouch;

    private View remoteScreen() {
        LinearLayout v = vbox();
        v.setPadding(dp(16), dp(6), dp(16), dp(16));

        LinearLayout top = hbox();
        top.addView(rocker("CH", "KEY_CHUP", "KEY_CHDOWN", "ערוץ ▲", "ערוץ ▼", "︿", "﹀"), new LinearLayout.LayoutParams(dp(84), dp(232)));

        FrameLayout pager = new FrameLayout(this) {
            float sx; boolean swiping;
            @Override public boolean onInterceptTouchEvent(MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) { sx = e.getX(); swiping = false; }
                else if (e.getActionMasked() == MotionEvent.ACTION_MOVE && Math.abs(e.getX() - sx) > dp(28)) swiping = true;
                return swiping;
            }
            @Override public boolean onTouchEvent(MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                    float dx = e.getX() - sx;
                    if (Math.abs(dx) > dp(40)) showPage(pageIdx + (dx < 0 ? 1 : -1));
                    swiping = false;
                }
                return true;
            }
        };
        pages.add(grid4(new String[][]{{"⌂", "בית", "KEY_HOME"}, {"⌨", "מקלדת", "@KEYBOARD"}, {"↺", "חזור", "KEY_RETURN"}, {"🔇", "השתק", "KEY_MUTE"}}));
        pages.add(grid4(new String[][]{{"☰", "תפריט", "KEY_MENU"}, {"⎆", "מקור", "KEY_SOURCE"}, {"▦", "מדריך", "KEY_GUIDE"}, {"ⓘ", "מידע", "KEY_INFO"}}));
        pages.add(grid4(new String[][]{{"⏯", "נגן/השהה", "KEY_PLAY"}, {"⏹", "עצור", "KEY_STOP"}, {"⏪", "אחורה", "KEY_REWIND"}, {"⏩", "קדימה", "KEY_FF"}}));
        pages.add(grid4(new String[][]{{"#", "מספרים", "@NUMBERS"}, {"≡", "ערוצים", "KEY_CH_LIST"}, {"⇄", "ערוץ קודם", "KEY_PRECH"}, {"✕", "יציאה", "KEY_EXIT"}}));
        for (View p : pages) pager.addView(p, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(0, dp(232), 1f);
        plp.setMargins(dp(8), 0, dp(8), 0);
        top.addView(pager, plp);

        top.addView(rocker("VOL", "KEY_VOLUP", "KEY_VOLDOWN", "ווליום +", "ווליום −", "+", "—"), new LinearLayout.LayoutParams(dp(84), dp(232)));
        v.addView(top);

        LinearLayout dotRow = hbox();
        dotRow.setGravity(Gravity.CENTER);
        dotRow.setPadding(0, dp(18), 0, dp(8));
        for (int i = 0; i < pages.size(); i++) {
            final int idx = i;
            View d = new View(this);
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(9), dp(9));
            dl.setMargins(dp(6), 0, dp(6), 0);
            d.setOnClickListener(x -> showPage(idx));
            dots.add(d);
            dotRow.addView(d, dl);
        }
        v.addView(dotRow);
        showPage(0);

        LinearLayout segWrap = hbox();
        segWrap.setGravity(Gravity.END);
        LinearLayout seg = hbox();
        seg.setBackground(round(CARD, dp(18), STROKE));
        seg.setElevation(dp(3));
        seg.setPadding(dp(4), dp(4), dp(4), dp(4));
        segPad = text("✥", 20, INK, true);
        segTouch = text("☝", 20, INK, true);
        segPad.setGravity(Gravity.CENTER); segTouch.setGravity(Gravity.CENTER);
        fx(segPad, null, false, () -> setPadMode(false));
        fx(segTouch, null, false, () -> setPadMode(true));
        seg.addView(segPad, new LinearLayout.LayoutParams(dp(60), dp(46)));
        seg.addView(segTouch, new LinearLayout.LayoutParams(dp(60), dp(46)));
        segWrap.addView(seg);
        v.addView(segWrap);

        padHolder = new FrameLayout(this);
        dpadView = diamondPad();
        touchView = touchPad();
        padHolder.addView(dpadView, new FrameLayout.LayoutParams(dp(300), dp(300), Gravity.CENTER));
        padHolder.addView(touchView, new FrameLayout.LayoutParams(-1, dp(300), Gravity.CENTER));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-1, dp(320));
        hlp.topMargin = dp(4);
        v.addView(padHolder, hlp);
        setPadMode(false);

        LinearLayout br = hbox();
        br.setPadding(0, dp(8), 0, 0);
        TextView src = cardBtn("⇥", "מקור");
        key(src, "KEY_SOURCE", "מקור", false);
        br.addView(src, new LinearLayout.LayoutParams(dp(130), dp(62)));
        br.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        TextView menu = cardBtn("☰", "תפריט");
        key(menu, "KEY_MENU", "תפריט", false);
        br.addView(menu, new LinearLayout.LayoutParams(dp(130), dp(62)));
        v.addView(br);

        LinearLayout cr = hbox();
        cr.setPadding(0, dp(16), 0, 0);
        String[] cn = {"A", "B", "C", "D"};
        String[] ck = {"KEY_RED", "KEY_GREEN", "KEY_YELLOW", "KEY_CYAN"};
        int[] cc = {0xFFE5484D, 0xFF2FB872, 0xFFE8B400, 0xFF3B6FE0};
        for (int i = 0; i < 4; i++) {
            TextView t = text(cn[i], 14, Color.WHITE, true);
            t.setGravity(Gravity.CENTER);
            t.setBackground(ripple(round(cc[i], dp(14), 0), dp(14), 0x55FFFFFF));
            t.setElevation(dp(3));
            key(t, ck[i], "מקש " + cn[i], false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(36), 1f);
            lp.setMargins(dp(5), 0, dp(5), 0);
            cr.addView(t, lp);
        }
        v.addView(cr);
        return v;
    }

    private void showPage(int idx) {
        if (idx < 0 || idx >= pages.size()) return;
        int dir = idx > pageIdx ? 1 : -1;
        for (int i = 0; i < pages.size(); i++) {
            View p = pages.get(i);
            if (i == idx) {
                p.setVisibility(View.VISIBLE);
                if (idx != pageIdx) { p.setTranslationX(dir * dp(60)); p.setAlpha(0f); p.animate().translationX(0).alpha(1f).setDuration(220).start(); }
            } else p.setVisibility(View.GONE);
            View d = dots.isEmpty() ? null : dots.get(i);
            if (d != null) d.setBackground(round(i == idx ? ACC : SOFT, dp(5), 0));
        }
        pageIdx = idx;
    }

    private void setPadMode(boolean touch) {
        dpadView.setVisibility(touch ? View.GONE : View.VISIBLE);
        touchView.setVisibility(touch ? View.VISIBLE : View.GONE);
        segPad.setBackground(touch ? null : round(0xFFE8EBF5, dp(14), 0));
        segTouch.setBackground(touch ? round(0xFFE8EBF5, dp(14), 0) : null);
        segPad.setTextColor(touch ? SUB : ACC);
        segTouch.setTextColor(touch ? ACC : SUB);
    }

    private View grid4(String[][] items) {
        LinearLayout g = vbox();
        for (int r = 0; r < 2; r++) {
            LinearLayout row = hbox();
            for (int c = 0; c < 2; c++) {
                String[] it = items[r * 2 + c];
                TextView b = cardBtn(it[0], it[1]);
                if (it[2].equals("@KEYBOARD")) fx(b, null, false, this::keyboardDialog);
                else if (it[2].equals("@NUMBERS")) fx(b, null, false, this::numbersDialog);
                else key(b, it[2], it[1], false);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1f);
                lp.setMargins(dp(5), dp(5), dp(5), dp(5));
                row.addView(b, lp);
            }
            g.addView(row, new LinearLayout.LayoutParams(-1, 0, 1f));
        }
        return g;
    }

    private TextView cardBtn(String icon, String label) {
        TextView t = new TextView(this);
        t.setText(icon + "\n" + label);
        t.setTextColor(INK);
        t.setTextSize(15);
        t.setGravity(Gravity.CENTER);
        t.setLineSpacing(dp(4), 1f);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setBackground(ripple(cardBg(dp(22)), dp(22), 0x224B5EEA));
        t.setElevation(dp(4));
        return t;
    }

    private GradientDrawable cardBg(int radius) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{CARD, CARD2});
        g.setCornerRadius(radius);
        g.setStroke(dp(1), STROKE);
        return g;
    }

    private View rocker(String label, String upKey, String downKey, String upL, String dnL, String upIcon, String dnIcon) {
        LinearLayout v = vbox();
        v.setBackground(cardBg(dp(26)));
        v.setElevation(dp(4));
        TextView up = text(upIcon, 28, INK, false);
        up.setGravity(Gravity.CENTER);
        up.setBackground(rippleRound(dp(26), 0x224B5EEA));
        key(up, upKey, upL, true);
        v.addView(up, new LinearLayout.LayoutParams(-1, 0, 1f));
        TextView l = text(label, 15, SUB, false);
        l.setGravity(Gravity.CENTER);
        v.addView(l, new LinearLayout.LayoutParams(-1, -2));
        TextView dn = text(dnIcon, 28, INK, false);
        dn.setGravity(Gravity.CENTER);
        dn.setBackground(rippleRound(dp(26), 0x224B5EEA));
        key(dn, downKey, dnL, true);
        v.addView(dn, new LinearLayout.LayoutParams(-1, 0, 1f));
        return v;
    }

    private View diamondPad() {
        FrameLayout f = new FrameLayout(this);
        View diamond = new View(this);
        GradientDrawable dg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFFEFF2F9, 0xFFE3E8F3});
        dg.setCornerRadius(dp(40));
        dg.setStroke(dp(1), STROKE);
        diamond.setBackground(dg);
        diamond.setRotation(45f);
        diamond.setElevation(dp(2));
        f.addView(diamond, new FrameLayout.LayoutParams(dp(208), dp(208), Gravity.CENTER));

        String[][] arrows = {{"▲", "KEY_UP", "למעלה"}, {"▼", "KEY_DOWN", "למטה"}, {"◀", "KEY_LEFT", "שמאלה"}, {"▶", "KEY_RIGHT", "ימינה"}};
        int[] grav = {Gravity.TOP | Gravity.CENTER_HORIZONTAL, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL,
                Gravity.LEFT | Gravity.CENTER_VERTICAL, Gravity.RIGHT | Gravity.CENTER_VERTICAL};
        boolean[] horiz = {true, true, false, false};
        for (int i = 0; i < 4; i++) {
            TextView a = text(arrows[i][0], 20, 0xFFB4BCD3, false);
            a.setGravity(Gravity.CENTER);
            a.setBackground(ripple(cardBg(dp(26)), dp(26), 0x334B5EEA));
            a.setElevation(dp(5));
            key(a, arrows[i][1], arrows[i][2], true);
            int w = horiz[i] ? dp(118) : dp(74), h = horiz[i] ? dp(74) : dp(118);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h, grav[i]);
            lp.setMargins(dp(14), dp(14), dp(14), dp(14));
            f.addView(a, lp);
        }
        TextView ok = text("OK", 30, Color.WHITE, true);
        ok.setGravity(Gravity.CENTER);
        GradientDrawable og = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{ACC, ACC2});
        og.setShape(GradientDrawable.OVAL);
        ok.setBackground(rippleOval(og, 0x55FFFFFF));
        ok.setElevation(dp(10));
        key(ok, "KEY_ENTER", "אישור", false);
        f.addView(ok, new FrameLayout.LayoutParams(dp(112), dp(112), Gravity.CENTER));
        return f;
    }

    private View touchPad() {
        TextView pad = text("החלק לניווט · הקש לאישור", 14, SUB, false);
        pad.setGravity(Gravity.CENTER);
        pad.setBackground(cardBg(dp(28)));
        pad.setElevation(dp(4));
        final float[] start = new float[2];
        final long[] last = new long[1];
        pad.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    start[0] = e.getX(); start[1] = e.getY();
                    v.animate().scaleX(0.98f).scaleY(0.98f).setDuration(80).start();
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getX() - start[0], dy = e.getY() - start[1];
                    if (Math.max(Math.abs(dx), Math.abs(dy)) > dp(56) && System.currentTimeMillis() - last[0] > 120) {
                        haptic();
                        if (Math.abs(dx) > Math.abs(dy)) send(dx > 0 ? "KEY_RIGHT" : "KEY_LEFT", dx > 0 ? "ימינה" : "שמאלה");
                        else send(dy > 0 ? "KEY_DOWN" : "KEY_UP", dy > 0 ? "למטה" : "למעלה");
                        start[0] = e.getX(); start[1] = e.getY();
                        last[0] = System.currentTimeMillis();
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP: {
                    v.animate().scaleX(1f).scaleY(1f).setInterpolator(new OvershootInterpolator(3f)).setDuration(220).start();
                    float dx = e.getX() - start[0], dy = e.getY() - start[1];
                    if (Math.abs(dx) < dp(12) && Math.abs(dy) < dp(12) && System.currentTimeMillis() - last[0] > 300) { haptic(); send("KEY_ENTER", "אישור"); }
                    return true;
                }
                case MotionEvent.ACTION_CANCEL:
                    v.animate().scaleX(1f).scaleY(1f).setDuration(150).start();
                    return true;
            }
            return false;
        });
        return pad;
    }

    private void keyboardDialog() {
        EditText et = field("הקלד טקסט לשליחה לטלוויזיה");
        LinearLayout box = vbox();
        box.setPadding(dp(20), dp(10), dp(20), 0);
        box.addView(et, new LinearLayout.LayoutParams(-1, dp(52)));
        new AlertDialog.Builder(this).setTitle("מקלדת").setView(box)
                .setPositiveButton("שלח", (d, w) -> {
                    String s = et.getText().toString();
                    bg.execute(() -> flash(tv.sendText(s) ? "הטקסט נשלח" : "שליחת טקסט דורשת חיבור Wi-Fi", tv.isConnected()));
                }).setNegativeButton("ביטול", null).show();
    }

    private void numbersDialog() {
        LinearLayout v = vbox();
        v.setPadding(dp(16), dp(8), dp(16), dp(8));
        String[][] rows = {{"1", "2", "3"}, {"4", "5", "6"}, {"7", "8", "9"}, {"-", "0", "⇄"}};
        String[][] keys = {{"KEY_1", "KEY_2", "KEY_3"}, {"KEY_4", "KEY_5", "KEY_6"}, {"KEY_7", "KEY_8", "KEY_9"}, {"KEY_PLUS100", "KEY_0", "KEY_PRECH"}};
        for (int i = 0; i < 4; i++) {
            LinearLayout r = hbox();
            for (int j = 0; j < 3; j++) {
                TextView t = text(rows[i][j], 22, INK, true);
                t.setGravity(Gravity.CENTER);
                t.setBackground(ripple(cardBg(dp(18)), dp(18), 0x224B5EEA));
                t.setElevation(dp(3));
                key(t, keys[i][j], rows[i][j], false);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(58), 1f);
                lp.setMargins(dp(5), dp(5), dp(5), dp(5));
                r.addView(t, lp);
            }
            v.addView(r);
        }
        new AlertDialog.Builder(this).setTitle("מספרים").setView(v).setNegativeButton("סגור", null).show();
    }

    private View hdmiScreen() {
        LinearLayout v = vbox();
        v.setPadding(dp(16), dp(10), dp(16), dp(24));
        v.addView(header("בחירת מקור", "הקש כדי לעבור · לחיצה ארוכה כדי לשנות שם"));
        String[][] inputs = {{"HDMI 1", "KEY_HDMI1"}, {"HDMI 2", "KEY_HDMI2"}, {"HDMI 3", "KEY_HDMI3"}, {"HDMI 4", "KEY_HDMI4"}};
        LinearLayout row = null;
        for (int i = 0; i < inputs.length; i++) {
            if (i % 2 == 0) { row = hbox(); v.addView(row); }
            final String keyName = inputs[i][1];
            final String def = inputs[i][0];
            LinearLayout card = vbox();
            card.setGravity(Gravity.CENTER);
            card.setBackground(ripple(cardBg(dp(24)), dp(24), 0x224B5EEA));
            card.setElevation(dp(4));
            TextView ic = text("▭", 34, ACC, true);
            ic.setGravity(Gravity.CENTER);
            card.addView(ic);
            TextView nm = text(prefs.getString("name_" + keyName, def), 16, INK, true);
            nm.setGravity(Gravity.CENTER);
            card.addView(nm);
            TextView sb = text(def, 12, SUB, false);
            sb.setGravity(Gravity.CENTER);
            card.addView(sb);
            fx(card, null, false, () -> switchHdmi(keyName, nm.getText().toString(), i0(keyName)));
            card.setOnLongClickListener(x -> { renameInput(keyName, def, nm); return true; });
            card.setLongClickable(true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(132), 1f);
            lp.setMargins(dp(6), dp(6), dp(6), dp(6));
            row.addView(card, lp);
        }
        v.addView(sectionTitle("מקורות נוספים"));
        String[][] more = {{"📺", "טלוויזיה", "KEY_TV"}, {"📡", "אנטנה", "KEY_ANTENA"}, {"⎆", "תפריט מקורות", "KEY_SOURCE"},
                {"🔁", "המקור הבא", "KEY_HDMI"}, {"AV", "AV", "KEY_AV1"}, {"▤", "Component", "KEY_COMPONENT1"}};
        for (int i = 0; i < more.length; i++) {
            if (i % 3 == 0) { row = hbox(); v.addView(row); }
            TextView t = cardBtn(more[i][0], more[i][1]);
            t.setTextSize(13);
            key(t, more[i][2], more[i][1], false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(86), 1f);
            lp.setMargins(dp(5), dp(5), dp(5), dp(5));
            row.addView(t, lp);
        }
        TextView note = text("בדגמים חדשים שבהם מקשי HDMI ישירים לא עובדים, הפעל בהגדרות \"ניווט בתפריט מקורות\" והאפליקציה תפתח את תפריט המקורות ותנווט למקור שבחרת.", 12, SUB, false);
        note.setPadding(dp(4), dp(14), dp(4), 0);
        v.addView(note);
        return v;
    }

    private int i0(String k) { return k.charAt(k.length() - 1) - '1'; }

    private void switchHdmi(String keyName, String label, int index) {
        if (sendMode != 2 && tv.isConnected()) {
            flash(label, true);
            bg.execute(() -> {
                tv.sendKey(keyName, "Click");
                if (prefs.getBoolean("hdmi_nav", false)) {
                    sleep(1500);
                    tv.sendKey("KEY_SOURCE", "Click");
                    sleep(1200);
                    for (int i = 0; i < 8; i++) { tv.sendKey("KEY_LEFT", "Click"); sleep(120); }
                    for (int i = 0; i < index + 1; i++) { tv.sendKey("KEY_RIGHT", "Click"); sleep(250); }
                    tv.sendKey("KEY_ENTER", "Click");
                }
            });
        } else send(keyName, label);
    }

    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) {} }

    private void renameInput(String keyName, String def, TextView nameView) {
        haptic();
        EditText et = field("למשל: PlayStation, סלקום, Apple TV");
        et.setText(prefs.getString("name_" + keyName, ""));
        LinearLayout box = vbox();
        box.setPadding(dp(20), dp(10), dp(20), 0);
        box.addView(et, new LinearLayout.LayoutParams(-1, dp(52)));
        new AlertDialog.Builder(this).setTitle("שם עבור " + def).setView(box)
                .setPositiveButton("שמור", (d, w) -> {
                    String n = et.getText().toString().trim();
                    prefs.edit().putString("name_" + keyName, n).apply();
                    nameView.setText(n.isEmpty() ? def : n);
                }).setNegativeButton("ביטול", null).show();
    }

    private View appsScreen() {
        LinearLayout v = vbox();
        v.setPadding(dp(16), dp(10), dp(16), dp(24));
        v.addView(header("אפליקציות", "פתיחה ישירה בטלוויזיה"));
        LinearLayout row = null;
        for (int i = 0; i < APPS.length; i++) {
            if (i % 3 == 0) { row = hbox(); v.addView(row); }
            final String[] a = APPS[i];
            int col = Color.parseColor(a[3]);
            LinearLayout card = vbox();
            card.setGravity(Gravity.CENTER);
            card.setBackground(ripple(cardBg(dp(22)), dp(22), 0x224B5EEA));
            card.setElevation(dp(4));
            TextView logo = text(a[0].substring(0, 1), 22, Color.WHITE, true);
            logo.setGravity(Gravity.CENTER);
            logo.setBackground(round(col, dp(16), 0));
            card.addView(logo, new LinearLayout.LayoutParams(dp(52), dp(52)));
            TextView nm = text(a[0], 13, INK, true);
            nm.setGravity(Gravity.CENTER);
            nm.setPadding(0, dp(8), 0, 0);
            card.addView(nm);
            fx(card, "פותח " + a[0], false, () -> launch(a));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(118), 1f);
            lp.setMargins(dp(5), dp(5), dp(5), dp(5));
            row.addView(card, lp);
        }
        return v;
    }

    private View settingsScreen() {
        LinearLayout v = vbox();
        v.setPadding(dp(16), dp(10), dp(16), dp(24));
        v.addView(header("הגדרות", "חיבור, בדיקות ומקשים מתקדמים"));

        TextView find = primary("📡  חיפוש טלוויזיה ברשת");
        fx(find, null, false, this::openDiscovery);
        v.addView(find, new LinearLayout.LayoutParams(-1, dp(56)));

        v.addView(sectionTitle("חיבור"));
        LinearLayout sr = hbox();
        modeBtn = cardBtn("⇆", modeLabel());
        modeBtn.setTextSize(13);
        fx(modeBtn, null, false, this::cycleMode);
        LinearLayout.LayoutParams m1 = new LinearLayout.LayoutParams(0, dp(84), 1f);
        m1.setMargins(0, 0, dp(5), 0);
        sr.addView(modeBtn, m1);
        TextView test = cardBtn("🩺", "בדיקת כל השיטות");
        test.setTextSize(13);
        fx(test, null, false, this::testAll);
        LinearLayout.LayoutParams m2 = new LinearLayout.LayoutParams(0, dp(84), 1f);
        m2.setMargins(dp(5), 0, 0, 0);
        sr.addView(test, m2);
        v.addView(sr);

        v.addView(sectionTitle("כתובת MAC להדלקה דרך Wi-Fi"));
        LinearLayout macRow = hbox();
        macField = field("AA:BB:CC:DD:EE:FF");
        macField.setText(prefs.getString("mac", ""));
        macRow.addView(macField, new LinearLayout.LayoutParams(0, dp(52), 1f));
        TextView save = primary("שמור");
        fx(save, "נשמר", false, () -> prefs.edit().putString("mac", macField.getText().toString().trim()).apply());
        macRow.addView(save, sideBtn());
        v.addView(macRow);

        v.addView(sectionTitle("HDMI בדגמים חדשים"));
        TextView nav = cardBtn("🧭", prefs.getBoolean("hdmi_nav", false) ? "ניווט בתפריט מקורות: פעיל" : "ניווט בתפריט מקורות: כבוי");
        nav.setTextSize(13);
        fx(nav, null, false, () -> {
            boolean on = !prefs.getBoolean("hdmi_nav", false);
            prefs.edit().putBoolean("hdmi_nav", on).apply();
            nav.setText("🧭\n" + (on ? "ניווט בתפריט מקורות: פעיל" : "ניווט בתפריט מקורות: כבוי"));
        });
        v.addView(nav, new LinearLayout.LayoutParams(-1, dp(80)));

        v.addView(sectionTitle("כל המקשים המתקדמים"));
        String[][] extra = {
            {"יחס תמונה", "KEY_PICTURE_SIZE"}, {"מצב תמונה", "KEY_PMODE"}, {"טיימר שינה", "KEY_SLEEP"},
            {"כתוביות", "KEY_SUB_TITLE"}, {"תיאור קולי", "KEY_AD"}, {"מדריך", "KEY_E_MANUAL"},
            {"Ambient", "KEY_AMBIENT"}, {"Bixby", "KEY_BT_VOICE"}, {"כלים", "KEY_TOOLS"},
            {"שפת שמע", "KEY_MTS"}, {"הקלטה", "KEY_REC"}, {"השהה", "KEY_PAUSE"},
            {"עמוד ▲", "KEY_PAGE_UP"}, {"עמוד ▼", "KEY_PAGE_DOWN"}, {"זום", "KEY_ZOOM1"},
            {"מועדפים", "KEY_FAVCH"}, {"כבה", "KEY_POWEROFF"}, {"הדלק", "KEY_POWERON"},
        };
        LinearLayout r = null;
        for (int i = 0; i < extra.length; i++) {
            if (i % 3 == 0) { r = hbox(); v.addView(r); }
            TextView t = text(extra[i][0], 13, INK, true);
            t.setGravity(Gravity.CENTER);
            t.setBackground(ripple(cardBg(dp(16)), dp(16), 0x224B5EEA));
            t.setElevation(dp(3));
            key(t, extra[i][1], extra[i][0], false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(50), 1f);
            lp.setMargins(dp(4), dp(4), dp(4), dp(4));
            r.addView(t, lp);
        }

        v.addView(sectionTitle("שליחת קוד מקש ידני"));
        LinearLayout ck = hbox();
        EditText kf = field("למשל KEY_FACTORY");
        ck.addView(kf, new LinearLayout.LayoutParams(0, dp(52), 1f));
        TextView ks = primary("שלח");
        fx(ks, null, false, () -> { String k = kf.getText().toString().trim().toUpperCase(Locale.ROOT); send(k, k); });
        ck.addView(ks, sideBtn());
        v.addView(ck);
        return v;
    }

    private LinearLayout.LayoutParams sideBtn() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(84), dp(52));
        lp.setMargins(dp(8), 0, 0, 0);
        return lp;
    }

    private View header(String title, String sub) {
        LinearLayout h = vbox();
        h.setPadding(dp(4), 0, 0, dp(12));
        h.addView(text(title, 24, INK, true));
        h.addView(text(sub, 13, SUB, false));
        return h;
    }

    private View bottomNav() {
        LinearLayout n = hbox();
        n.setBackgroundColor(CARD);
        n.setElevation(dp(12));
        n.setPadding(0, dp(8), 0, dp(8));
        String[][] items = {{"✥", "שלט"}, {"▭", "HDMI"}, {"▦", "אפליקציות"}, {"⚙", "הגדרות"}};
        for (int i = 0; i < items.length; i++) {
            final int idx = i;
            LinearLayout col = vbox();
            col.setGravity(Gravity.CENTER);
            col.setBackground(rippleRound(dp(16), 0x224B5EEA));
            TextView ic = text(items[i][0], 22, SUB, true);
            ic.setGravity(Gravity.CENTER);
            TextView lb = text(items[i][1], 12, SUB, true);
            lb.setGravity(Gravity.CENTER);
            col.addView(ic);
            col.addView(lb);
            navItems.add(new TextView[]{ic, lb});
            fx(col, null, false, () -> selectScreen(idx));
            n.addView(col, new LinearLayout.LayoutParams(0, dp(58), 1f));
        }
        return n;
    }

    private void selectScreen(int idx) {
        for (int i = 0; i < screens.size(); i++) {
            View s = screens.get(i);
            boolean on = i == idx;
            if (on && s.getVisibility() != View.VISIBLE) { s.setVisibility(View.VISIBLE); s.setAlpha(0f); s.animate().alpha(1f).setDuration(180).start(); }
            else if (on) s.setVisibility(View.VISIBLE);
            else s.setVisibility(View.GONE);
            if (i < navItems.size()) {
                navItems.get(i)[0].setTextColor(on ? ACC : SUB);
                navItems.get(i)[1].setTextColor(on ? ACC : SUB);
            }
        }
    }

    private FrameLayout buildDeviceScreen() {
        FrameLayout o = new FrameLayout(this);
        o.setBackgroundColor(BG);
        o.setClickable(true);
        LinearLayout v = vbox();
        o.addView(v, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout bar = hbox();
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(16), dp(12), dp(16), dp(12));
        TextView back = iconBtn("→", 22);
        fx(back, null, false, this::closeDiscovery);
        bar.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout mid = hbox();
        mid.setGravity(Gravity.CENTER);
        scanTitle = text("בחר מכשיר", 20, INK, true);
        mid.addView(scanTitle);
        scanSpin = new ProgressBar(this);
        scanSpin.setIndeterminateTintList(ColorStateList.valueOf(ACC));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(24), dp(24));
        sp.setMargins(dp(10), 0, 0, 0);
        mid.addView(scanSpin, sp);
        bar.addView(mid, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView skip = text("דלג", 16, SUB, true);
        skip.setPadding(dp(8), dp(8), dp(8), dp(8));
        fx(skip, null, false, this::closeDiscovery);
        bar.addView(skip);
        v.addView(bar);

        ScrollView sv = new ScrollView(this);
        LinearLayout inner = vbox();
        inner.setPadding(dp(18), dp(4), dp(18), dp(24));
        sv.addView(inner);
        v.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout warn = hbox();
        warn.setGravity(Gravity.CENTER_VERTICAL);
        warn.setPadding(dp(14), dp(12), dp(14), dp(12));
        warn.setBackground(round(0xFFFFF6E5, dp(16), 0xFFFBE2B0));
        TextView wi = text("!", 18, ORANGE, true);
        wi.setGravity(Gravity.CENTER);
        GradientDrawable wg = new GradientDrawable(); wg.setShape(GradientDrawable.OVAL); wg.setStroke(dp(2), ORANGE);
        wi.setBackground(wg);
        warn.addView(wi, new LinearLayout.LayoutParams(dp(32), dp(32)));
        TextView wt = text("ודא שהטלפון והטלוויזיה מחוברים לאותה רשת Wi-Fi ושהטלוויזיה דולקת.", 14, 0xFF6B5A2E, false);
        wt.setPadding(dp(12), 0, 0, 0);
        warn.addView(wt, new LinearLayout.LayoutParams(0, -2, 1f));
        inner.addView(warn);

        resultsList = vbox();
        resultsList.setPadding(0, dp(12), 0, dp(12));
        inner.addView(resultsList);

        TextView again = primary("🔄  חפש שוב");
        fx(again, null, false, this::startScan);
        inner.addView(again, new LinearLayout.LayoutParams(-1, dp(54)));

        inner.addView(sectionTitle("הזנת כתובת IP ידנית"));
        LinearLayout mr = hbox();
        manualIp = field("192.168.1.50");
        manualIp.setInputType(InputType.TYPE_CLASS_PHONE);
        manualIp.setText(prefs.getString("ip", ""));
        mr.addView(manualIp, new LinearLayout.LayoutParams(0, dp(52), 1f));
        TextView go = primary("התחבר");
        fx(go, null, false, () -> {
            String ip = manualIp.getText().toString().trim();
            if (ip.isEmpty()) return;
            prefs.edit().putString("ip", ip).apply();
            closeDiscovery(); connect();
        });
        mr.addView(go, sideBtn());
        inner.addView(mr);
        TextView ipHelp = text("בטלוויזיה: הגדרות ← כללי ← רשת ← מצב רשת", 12, SUB, false);
        ipHelp.setPadding(dp(4), dp(6), 0, dp(16));
        inner.addView(ipHelp);

        TextView irBtn = text("מצב IR (אינפרא-אדום)", 16, INK, true);
        irBtn.setGravity(Gravity.CENTER);
        irBtn.setBackground(ripple(cardBg(dp(20)), dp(20), 0x224B5EEA));
        irBtn.setElevation(dp(3));
        fx(irBtn, null, false, () -> {
            if (!hasIr()) { flash("לטלפון הזה אין משדר אינפרא-אדום", false); return; }
            sendMode = 2; if (modeBtn != null) modeBtn.setText("⇆\n" + modeLabel());
            prefs.edit().putString("tvname", "Samsung · IR").apply();
            tvTitle.setText("Samsung · IR");
            setDot(GREEN);
            closeDiscovery();
            flash("מצב אינפרא-אדום פעיל", true);
        });
        inner.addView(irBtn, new LinearLayout.LayoutParams(-1, dp(60)));
        return o;
    }

    private void openDiscovery() {
        overlay.setVisibility(View.VISIBLE);
        overlay.setTranslationX(-dp(40));
        overlay.setAlpha(0f);
        overlay.animate().alpha(1f).translationX(0).setDuration(220).start();
        startScan();
    }

    private void closeDiscovery() {
        scanning = false;
        overlay.animate().alpha(0f).translationX(-dp(40)).setDuration(180).withEndAction(() -> overlay.setVisibility(View.GONE)).start();
    }

    private void startScan() {
        if (scanning) return;
        scanning = true;
        seen.clear();
        resultsList.removeAllViews();
        scanSpin.setVisibility(View.VISIBLE);
        scanTitle.setText("מחפש מכשירים");
        final CountDownLatch done = new CountDownLatch(2);
        new Thread(() -> { try { ssdpSearch(); } finally { done.countDown(); } }).start();
        new Thread(() -> { try { subnetScan(); } finally { done.countDown(); } }).start();
        new Thread(() -> {
            try { done.await(45, TimeUnit.SECONDS); Thread.sleep(1500); } catch (InterruptedException ignored) {}
            scanning = false;
            ui.post(() -> {
                scanSpin.setVisibility(View.GONE);
                scanTitle.setText("בחר מכשיר");
                if (resultsList.getChildCount() == 0) {
                    TextView t = text("לא נמצאו טלוויזיות. הדלק את הטלוויזיה ונסה שוב, או הזן IP ידנית.", 14, SUB, false);
                    t.setGravity(Gravity.CENTER);
                    t.setPadding(dp(8), dp(24), dp(8), dp(24));
                    resultsList.addView(t);
                }
            });
        }).start();
    }

    private void ssdpSearch() {
        WifiManager.MulticastLock lock = null;
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
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
        String kind = tizen ? "Wi-Fi" : legacy ? "דגם ישן" : "Samsung";
        final String n = name != null ? name : "Samsung TV";
        final String sub = (model != null ? model + " · " : "") + ip;
        ui.post(() -> addResult(ip, n, sub, kind, mac));
    }

    private void addResult(String ip, String name, String sub, String kind, String mac) {
        LinearLayout card = hbox();
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setBackground(ripple(cardBg(dp(20)), dp(20), 0x224B5EEA));
        card.setElevation(dp(4));
        TextView icon = text("📺", 22, INK, false);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(round(0xFFEEF0FD, dp(16), 0));
        card.addView(icon, new LinearLayout.LayoutParams(dp(50), dp(50)));
        LinearLayout t = vbox();
        t.setPadding(dp(14), 0, dp(10), 0);
        TextView nm = text(name, 16, INK, true);
        nm.setSingleLine(true);
        nm.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.addView(nm);
        t.addView(text(sub, 12, SUB, false));
        card.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView k = text(kind, 11, GREEN, true);
        k.setPadding(dp(10), dp(4), dp(10), dp(4));
        k.setBackground(round(0x1A2FB872, dp(10), 0));
        card.addView(k);
        fx(card, "מתחבר ל-" + name, false, () -> {
            SharedPreferences.Editor e = prefs.edit().putString("ip", ip).putString("tvname", name);
            if (mac != null && !mac.isEmpty()) e.putString("mac", mac);
            e.apply();
            manualIp.setText(ip);
            if (mac != null && macField != null) macField.setText(mac);
            tvTitle.setText(name);
            if (sendMode == 2) sendMode = 0;
            closeDiscovery();
            connect();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(6), 0, dp(6));
        card.setAlpha(0f); card.setTranslationY(dp(16));
        resultsList.addView(card, lp);
        card.animate().alpha(1f).translationY(0).setDuration(260).start();
    }

    private void connect() {
        String ip = prefs.getString("ip", "");
        if (ip.isEmpty()) { setDot(RED); return; }
        setDot(ORANGE);
        bg.execute(() -> tv.connect(ip, prefs.getString("token", "")));
    }

    private String modeLabel() { return sendMode == 0 ? "מצב: אוטומטי" : sendMode == 1 ? "מצב: Wi-Fi בלבד" : "מצב: אינפרא-אדום"; }

    private void cycleMode() {
        sendMode = (sendMode + 1) % 3;
        modeBtn.setText("⇆\n" + modeLabel());
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
        if (sendMode != 1 && sendIr(key)) { flash(label, true); return; }
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
        if (irOk) flash("הפעלה / כיבוי", true);
        if (sendMode != 2 && !mac.isEmpty()) {
            if (!irOk) flash("שולח פקודת הדלקה ברשת...", true);
            setDot(ORANGE);
            bg.execute(() -> {
                try { TvLink.wakeOnLan(mac, ip.isEmpty() ? null : ip); } catch (Exception e) { flash("שגיאה: " + e.getMessage(), false); }
                sleep(6000);
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
            ui.post(() -> new AlertDialog.Builder(this).setTitle("תוצאות בדיקה").setMessage(res)
                    .setPositiveButton("התחבר עכשיו", (d, w) -> connect()).setNegativeButton("סגור", null).show());
        });
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static boolean portOpen(String ip, int port, int timeout) {
        try (Socket s = new Socket()) { s.connect(new InetSocketAddress(ip, port), timeout); return true; }
        catch (Exception e) { return false; }
    }

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
            chip.setBackground(round(ok ? 0xEE2A3052 : 0xEEE5484D, dp(22), 0));
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
        final boolean[] longFired = new boolean[1];
        final Runnable longPress = () -> { if (v.isLongClickable()) { longFired[0] = true; v.performLongClick(); } };
        v.setClickable(true);
        v.setOnTouchListener((view, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    longFired[0] = false;
                    view.drawableHotspotChanged(e.getX(), e.getY());
                    view.setPressed(true);
                    view.animate().cancel();
                    view.animate().scaleX(0.9f).scaleY(0.9f).setDuration(70).start();
                    haptic();
                    if (repeat) {
                        action.run();
                        rep[0] = Executors.newSingleThreadScheduledExecutor();
                        rep[0].scheduleAtFixedRate(() -> ui.post(() -> { haptic(); action.run(); }), 450, 140, TimeUnit.MILLISECONDS);
                    } else ui.postDelayed(longPress, 600);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    view.drawableHotspotChanged(e.getX(), e.getY());
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    ui.removeCallbacks(longPress);
                    view.setPressed(false);
                    view.animate().scaleX(1f).scaleY(1f).setInterpolator(new OvershootInterpolator(4f)).setDuration(260).start();
                    if (rep[0] != null) { rep[0].shutdownNow(); rep[0] = null; }
                    boolean inside = e.getX() >= 0 && e.getY() >= 0 && e.getX() <= view.getWidth() && e.getY() <= view.getHeight();
                    if (!repeat && !longFired[0] && e.getActionMasked() == MotionEvent.ACTION_UP && inside) {
                        if (flashMsg != null) flash(flashMsg, true);
                        action.run();
                    }
                    return true;
            }
            return false;
        });
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }
    private LinearLayout vbox() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout hbox() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); return l; }

    private TextView text(String s, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(size); t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        return t;
    }

    private TextView sectionTitle(String s) {
        TextView t = text(s, 14, SUB, true);
        t.setPadding(dp(4), dp(20), 0, dp(10));
        return t;
    }

    private TextView iconBtn(String s, int size) {
        TextView t = text(s, size, ACC, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(rippleRound(dp(16), 0x224B5EEA));
        return t;
    }

    private TextView primary(String s) {
        TextView t = text(s, 15, Color.WHITE, true);
        t.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{ACC, ACC2});
        g.setCornerRadius(dp(18));
        t.setBackground(ripple(g, dp(18), 0x55FFFFFF));
        t.setElevation(dp(5));
        return t;
    }

    private void setDot(int color) {
        ui.post(() -> {
            statusDot.setBackground(round(color, dp(5), 0));
            statusDot.animate().scaleX(1.5f).scaleY(1.5f).setDuration(180)
                    .withEndAction(() -> statusDot.animate().scaleX(1f).scaleY(1f).setDuration(180).start()).start();
        });
    }

    private GradientDrawable round(int color, int radius, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color); g.setCornerRadius(radius);
        if (stroke != 0) g.setStroke(dp(1), stroke);
        return g;
    }

    private Drawable ripple(Drawable content, int radius, int color) {
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE); mask.setCornerRadius(radius);
        return new RippleDrawable(ColorStateList.valueOf(color), content, mask);
    }

    private Drawable rippleRound(int radius, int color) {
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE); mask.setCornerRadius(radius);
        return new RippleDrawable(ColorStateList.valueOf(color), null, mask);
    }

    private Drawable rippleOval(Drawable content, int color) {
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.OVAL); mask.setColor(Color.WHITE);
        return new RippleDrawable(ColorStateList.valueOf(color), content, mask);
    }

    private EditText field(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setTextColor(INK); e.setHintTextColor(SOFT);
        e.setTextSize(15);
        e.setSingleLine(true);
        e.setPadding(dp(14), 0, dp(14), 0);
        e.setBackground(round(CARD, dp(16), STROKE));
        return e;
    }

    @Override public void onStatus(String msg) {
        boolean on = tv.isConnected();
        setDot(on ? GREEN : RED);
        if (on) flash("מחובר לטלוויזיה", true);
        else if (msg != null && !msg.isEmpty()) flash(msg.length() > 40 ? "לא הצלחתי להתחבר" : msg, false);
    }

    @Override public void onToken(String token) { prefs.edit().putString("token", token).apply(); }

    @Override protected void onDestroy() { super.onDestroy(); scanning = false; new Thread(tv::disconnect).start(); }
}
