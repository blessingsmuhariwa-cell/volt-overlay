package com.voltoverlay;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.Locale;

/** The dashboard: a gauge, time and drain estimates, battery health, a history graph and the overlay controls. */
public class MainActivity extends Activity {

    private static final int BG = 0xFF0F1115;
    private static final int CARD = 0xFF1B1F27;
    private static final int TEXT = 0xFFEDEFF3;
    private static final int MUTED = 0xFF9AA3B2;
    private static final int GREEN = 0xFF4CAF50;
    private static final int AMBER = 0xFFFFB300;
    private static final int RED = 0xFFE53935;
    private static final int BLUE = 0xFF42A5F5;

    private static class Row {
        TextView label;
        TextView value;
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private GaugeView gauge;
    private GraphView graph;
    private Row rSince, rScreen, rLeft, rTo15;
    private Row rCurrent, rPower, rVolt, rTemp, rCharger;
    private Row rUsedPct, rUsedMah, rAvgDrain;
    private Row rHealth, rCap, rCycles, rEmpty;
    private Row rOverlay;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            try {
                render();
            } catch (Exception ignored) {
                // never let a drawing problem close the app
            }
            handler.postDelayed(this, 1000);
        }
    };

    private int dp(int x) {
        return Math.round(x * getResources().getDisplayMetrics().density);
    }

    private LinearLayout card(LinearLayout parent, String title) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(dp(16));
        c.setBackground(bg);
        c.setPadding(dp(16), dp(14), dp(16), dp(14));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        parent.addView(c, lp);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(MUTED);
        t.setTextSize(12);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, 0, 0, dp(6));
        c.addView(t);
        return c;
    }

    private Row row(LinearLayout card, String label) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(0, dp(5), 0, dp(5));

        Row row = new Row();
        row.label = new TextView(this);
        row.label.setText(label);
        row.label.setTextColor(MUTED);
        row.label.setTextSize(15);

        row.value = new TextView(this);
        row.value.setText("--");
        row.value.setTextColor(TEXT);
        row.value.setTextSize(15);
        row.value.setTypeface(Typeface.DEFAULT_BOLD);
        row.value.setGravity(Gravity.END);

        r.addView(row.label, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(row.value, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(r);
        return row;
    }

    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(TEXT);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF2B3340);
        bg.setCornerRadius(dp(12));
        b.setBackground(bg);
        b.setOnClickListener(l);
        return b;
    }

    private void addButton(LinearLayout parent, Button b) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        parent.addView(b, lp);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().getDecorView().setBackgroundColor(BG);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            view.setPadding(dp(16) + bars.left, dp(12) + bars.top, dp(16) + bars.right, dp(24) + bars.bottom);
            return insets;
        });
        sv.addView(root);

        TextView title = new TextView(this);
        title.setText("Volt Overlay");
        title.setTextColor(TEXT);
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        gauge = new GaugeView(this);
        root.addView(gauge, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout time = card(root, "TIME");
        rSince = row(time, "On battery for");
        rScreen = row(time, "Screen on");
        rLeft = row(time, "Time left (to 0%)");
        rTo15 = row(time, "Until red (15%)");

        LinearLayout now = card(root, "RIGHT NOW");
        rCurrent = row(now, "Current");
        rPower = row(now, "Power");
        rVolt = row(now, "Voltage");
        rTemp = row(now, "Temperature");
        rCharger = row(now, "Charger");

        LinearLayout sess = card(root, "THIS STRETCH");
        rUsedPct = row(sess, "Used since unplug");
        rUsedMah = row(sess, "Charge used");
        rAvgDrain = row(sess, "Average drain");

        LinearLayout health = card(root, "BATTERY HEALTH");
        rHealth = row(health, "Health (estimate)");
        rCap = row(health, "Learned capacity");
        rCycles = row(health, "Cycles");
        rEmpty = row(health, "0% sits at");

        LinearLayout hist = card(root, "HISTORY");
        LinearLayout toggles = new LinearLayout(this);
        toggles.setOrientation(LinearLayout.HORIZONTAL);
        graph = new GraphView(this);
        Button b6 = button("6 hours", x -> graph.setHours(6));
        Button b24 = button("24 hours", x -> graph.setHours(24));
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        half.rightMargin = dp(4);
        toggles.addView(b6, half);
        LinearLayout.LayoutParams half2 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        half2.leftMargin = dp(4);
        toggles.addView(b24, half2);
        hist.addView(toggles);
        hist.addView(graph, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout ov = card(root, "OVERLAY");
        rOverlay = row(ov, "Status");
        addButton(ov, button("1. Allow display over other apps", x ->
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())))));
        addButton(ov, button("2. Start overlay", x -> startOverlay()));
        addButton(ov, button("Move overlay (10 seconds)", x ->
                startService(new Intent(this, OverlayService.class).setAction(OverlayService.ACTION_MOVE))));
        addButton(ov, button("Stop overlay", x ->
                startService(new Intent(this, OverlayService.class).setAction(OverlayService.ACTION_STOP))));

        TextView note = new TextView(this);
        note.setText("The overlay box lets taps pass through to the app behind it. Use Move to drag it.\n"
                + "Percentage, time left and health are estimates worked out from live voltage and current. "
                + "Health needs one clean cycle (full charge, then use down to about 8% without plugging in).");
        note.setTextColor(MUTED);
        note.setTextSize(12);
        note.setPadding(dp(4), dp(14), dp(4), 0);
        root.addView(note);

        setContentView(sv);
    }

    @Override
    protected void onResume() {
        super.onResume();
        History.load(new File(getFilesDir(), "history.csv"));
        handler.post(refresh);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(refresh);
    }

    private void startOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Do step 1 first", Toast.LENGTH_SHORT).show();
            return;
        }
        // Android 13+: needed for the service notification to be visible.
        // The service still runs if you deny it.
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        startForegroundService(new Intent(this, OverlayService.class));
    }

    private static String fmtDuration(long ms) {
        if (ms < 0) return "--";
        long m = ms / 60000;
        if (m < 1) return "<1 min";
        long h = m / 60;
        long mm = m % 60;
        return h > 0 ? h + " h " + mm + " min" : mm + " min";
    }

    private static String fmtHours(double hours) {
        if (Double.isNaN(hours)) return "--";
        if (hours > 99) return ">99 h";
        long totalMin = Math.round(hours * 60);
        if (totalMin < 1) return "<1 min";
        long h = totalMin / 60;
        long m = totalMin % 60;
        return h > 0 ? "~" + h + " h " + m + " min" : "~" + m + " min";
    }

    private static String chargerName(int type) {
        switch (type) {
            case 0: return "Not plugged in";
            case 1: return "AC charger";
            case 2: return "USB";
            case 4: return "Wireless";
            case 8: return "Dock";
            default: return "Other";
        }
    }

    private void showOff(SharedPreferences sp) {
        gauge.set(-1, MUTED, "Overlay is off", "Tap Start overlay");
        rSince.value.setText("--");
        rScreen.value.setText("--");
        rLeft.value.setText("--");
        rTo15.value.setText("--");
        rCurrent.value.setText("--");
        rPower.value.setText("--");
        rVolt.value.setText("--");
        rTemp.value.setText("--");
        rCharger.value.setText("--");
        rUsedPct.value.setText("--");
        rUsedMah.value.setText("--");
        rAvgDrain.value.setText("--");
        rEmpty.value.setText("--");
        rOverlay.value.setText("Off");
        rOverlay.value.setTextColor(AMBER);
    }

    private void render() {
        long now = SystemClock.elapsedRealtime();
        boolean live = LiveData.running && now - LiveData.updatedMs < 5000;
        SharedPreferences sp = getSharedPreferences("gauge", MODE_PRIVATE);
        double design = OverlayService.DESIGN_CAPACITY_MAH;
        double cap = live ? LiveData.capacityMah : sp.getFloat("cap", 0f);
        int cycles = live ? LiveData.learnedCycles : sp.getInt("cycles", 0);

        // Battery health: the capacity the app measured compared with the label capacity
        if (cap > 0 && cycles > 0) {
            double hp = Math.min(100.0, cap / design * 100.0);
            rHealth.value.setText(String.format(Locale.US, "%.0f%%", hp));
            rHealth.value.setTextColor(hp >= 80 ? GREEN : (hp >= 60 ? AMBER : RED));
            rCap.value.setText(String.format(Locale.US, "%.0f of %.0f mAh", cap, design));
        } else {
            rHealth.value.setText("learning");
            rHealth.value.setTextColor(TEXT);
            rCap.value.setText(cap > 0 ? String.format(Locale.US, "%.0f mAh (start value)", cap) : "--");
        }
        double equiv = live ? LiveData.equivCycles : (cap > 0 ? sp.getFloat("totDis", 0f) / cap : 0);
        rCycles.value.setText(String.format(Locale.US, "%.1f full (%d learned)", equiv, cycles));

        if (!live) {
            showOff(sp);
            return;
        }

        int pct = LiveData.pct;
        boolean plugged = LiveData.plugged;
        int color = plugged ? BLUE : (pct <= 15 ? RED : (pct <= 30 ? AMBER : GREEN));
        String state = plugged ? (LiveData.full ? "Plugged in, full" : "Charging") : "On battery";
        gauge.set(pct, color, String.format(Locale.US, "%.2f V", LiveData.volts), state);

        rSince.label.setText(plugged ? "Charging for" : "On battery for");
        rSince.value.setText(fmtDuration(System.currentTimeMillis() - LiveData.sessionStartWall));
        rScreen.value.setText(fmtDuration(LiveData.screenOnMs));
        if (plugged) {
            rLeft.label.setText("Time to full");
            rLeft.value.setText(LiveData.full ? "Full" : fmtHours(LiveData.hoursToFull));
            rTo15.value.setText("--");
        } else {
            rLeft.label.setText("Time left (to 0%)");
            rLeft.value.setText(fmtHours(LiveData.hoursTo0));
            rTo15.value.setText(pct <= 15 ? "now" : fmtHours(LiveData.hoursTo15));
        }

        double cur = LiveData.currentMa;
        rCurrent.value.setText(Double.isNaN(cur) ? "--"
                : String.format(Locale.US, "%.0f mA %s", Math.abs(cur), cur < 0 ? "out" : "in"));
        rPower.value.setText(Double.isNaN(cur) ? "--"
                : String.format(Locale.US, "%.2f W", Math.abs(cur) / 1000.0 * LiveData.volts));
        rVolt.value.setText(String.format(Locale.US, "%.3f V", LiveData.volts));
        rTemp.value.setText(Double.isNaN(LiveData.tempC) ? "--"
                : String.format(Locale.US, "%.1f \u00B0C", LiveData.tempC));
        rCharger.value.setText(chargerName(LiveData.plugType));

        int start = LiveData.sessionStartPct;
        rUsedPct.label.setText(plugged ? "Gained since plug-in" : "Used since unplug");
        if (start >= 0) {
            int diff = Math.abs(pct - start);
            rUsedPct.value.setText(diff + "%");
            rUsedMah.value.setText(String.format(Locale.US, "%.0f mAh", diff / 100.0 * LiveData.capacityMah));
        } else {
            rUsedPct.value.setText("--");
            rUsedMah.value.setText("--");
        }
        rAvgDrain.value.setText(plugged ? "--" : String.format(Locale.US, "%.0f mA", LiveData.avgDrainMa));
        rEmpty.value.setText(String.format(Locale.US, "%.2f V resting", LiveData.emptyV));

        rOverlay.value.setText("Running");
        rOverlay.value.setTextColor(GREEN);
        graph.invalidate();
    }
}
