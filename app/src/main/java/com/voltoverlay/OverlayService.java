package com.voltoverlay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.Locale;

public class OverlayService extends Service {

    static final String ACTION_STOP = "com.voltoverlay.STOP";
    private static final String CHANNEL = "volt_overlay";
    private static final long INTERVAL_MS = 1000;

    // ===== Tunables =====
    private static final double DEFAULT_CAPACITY_MAH = 5200; // starting guess; the app learns the real value
    private static final double DEFAULT_EMPTY_V = 3.40;      // starting guess; the app learns the real value
    private static final double R_OHMS = 0.10;               // internal resistance (voltage sag per amp)
    private static final double CHARGE_EFFICIENCY = 0.97;    // share of charging current that is stored
    private static final double[] VT = {3.40, 3.50, 3.60, 3.70, 3.80, 3.90, 4.00, 4.10, 4.20, 4.30, 4.35};
    private static final double[] PT = {0, 5, 12, 25, 41, 55, 67, 78, 88, 96, 100};
    // ====================

    private static final String[] SYSFS_PATHS = {
            "/sys/class/power_supply/battery/voltage_now",
            "/sys/class/power_supply/bms/voltage_now",
    };

    private WindowManager wm;
    private TextView box;
    private BatteryManager bm;
    private SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private double capacityMah = DEFAULT_CAPACITY_MAH; // learned battery capacity
    private double emptyV = DEFAULT_EMPTY_V;           // learned shutdown voltage
    private int learnedCycles = 0;
    private int shutdowns = 0;
    private double remainingMah = -1;      // charge left (mAh), -1 = unknown
    private double smoothV = Double.NaN;   // smoothed voltage (V)
    private double smoothI = Double.NaN;   // smoothed current (mA, + charging, - draining)
    private long lastTickMs = 0;
    private long lastSaveMs = 0;

    // Capacity-learning cycle: full -> unplug -> drain to near empty with no charging in between
    private boolean cycleArmed = false;
    private boolean cycleActive = false;
    private double cycleDrawnMah = 0;

    // Shutdown-voltage learning
    private boolean userStop = false;
    private boolean lowRecorded = false;
    private long lastLowWriteMs = 0;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (box != null) box.setText(update());
            handler.postDelayed(this, INTERVAL_MS);
        }
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            userStop = true;
            stopSelf();
            return START_NOT_STICKY;
        }
        if (prefs == null) loadState();
        startInForeground();
        if (!Settings.canDrawOverlays(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (box == null) addOverlay();
        return START_NOT_STICKY;
    }

    private void loadState() {
        bm = getSystemService(BatteryManager.class);
        prefs = getSharedPreferences("gauge", MODE_PRIVATE);

        double cap = prefs.getFloat("cap", (float) DEFAULT_CAPACITY_MAH);
        if (cap > 0.4 * DEFAULT_CAPACITY_MAH && cap < 1.2 * DEFAULT_CAPACITY_MAH) {
            capacityMah = cap;
        }
        learnedCycles = prefs.getInt("cycles", 0);

        double ev = prefs.getFloat("emptyV", (float) DEFAULT_EMPTY_V);
        if (ev >= 3.10 && ev <= 3.55) emptyV = ev;
        shutdowns = prefs.getInt("shutdowns", 0);

        // Did the phone die since the last run? (restarted without the Stop button being used)
        int bootNow = Settings.Global.getInt(getContentResolver(), Settings.Global.BOOT_COUNT, -2);
        int bootThen = prefs.getInt("boot", -1);
        float lowThen = prefs.getFloat("lowOcv", -1f);
        boolean wasRunning = prefs.getBoolean("running", false);
        if (wasRunning && bootNow != -2 && bootThen != -1 && bootNow != bootThen
                && lowThen >= 3.10f && lowThen <= 3.55f) {
            double w = shutdowns == 0 ? 0.7 : 0.3;
            emptyV = (1 - w) * emptyV + w * lowThen;
            shutdowns++;
        }
        prefs.edit()
                .putBoolean("running", true)
                .putInt("boot", bootNow)
                .putFloat("lowOcv", -1f)
                .putFloat("emptyV", (float) emptyV)
                .putInt("shutdowns", shutdowns)
                .apply();

        // Reuse the saved charge count if it is recent (under 10 minutes old)
        float saved = prefs.getFloat("mah", -1f);
        long savedAt = prefs.getLong("savedAt", 0);
        if (saved >= 0 && savedAt > 0
                && System.currentTimeMillis() - savedAt < 10 * 60 * 1000L) {
            remainingMah = Math.min(saved, capacityMah);
        }
    }

    private Notification buildNotification() {
        PendingIntent stop = PendingIntent.getService(this, 0,
                new Intent(this, OverlayService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        String text = (learnedCycles == 0
                ? String.format(Locale.US, "Capacity %.0f mAh (learning)", capacityMah)
                : String.format(Locale.US, "Capacity %.0f mAh (%d cycles)", capacityMah, learnedCycles))
                + String.format(Locale.US, " \u00B7 shuts down ~%.2fV", emptyV);

        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
                .setContentTitle("Voltage overlay running")
                .setContentText(text)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                        "Stop", stop).build())
                .setOngoing(true)
                .build();
    }

    private void startInForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL, "Voltage overlay", NotificationManager.IMPORTANCE_LOW));

        Notification n = buildNotification();
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1, n);
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void addOverlay() {
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);

        box = new TextView(this);
        box.setText("-.-V");
        box.setTextColor(0xFFFFFFFF);
        box.setTextSize(14);
        box.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        box.setPadding(dp(8), dp(4), dp(8), dp(4));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xB3000000);
        bg.setCornerRadius(dp(6));
        box.setBackground(bg);

        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = dp(12);
        lp.y = dp(80);

        box.setOnTouchListener(new View.OnTouchListener() {
            int startX, startY;
            float touchX, touchY;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = lp.x;
                        startY = lp.y;
                        touchX = e.getRawX();
                        touchY = e.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        lp.x = startX + (int) (e.getRawX() - touchX);
                        lp.y = startY + (int) (e.getRawY() - touchY);
                        wm.updateViewLayout(box, lp);
                        return true;
                }
                return false;
            }
        });

        wm.addView(box, lp);
        handler.post(tick);
    }

    /** Runs once per second: reads sensors, updates the charge count, returns the text. */
    private String update() {
        double v = fromSysfs();
        if (Double.isNaN(v)) v = fromBatteryApi();
        if (Double.isNaN(v)) return "-.-V";

        Intent bi = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int status = bi == null ? -1 : bi.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING;
        boolean full = status == BatteryManager.BATTERY_STATUS_FULL;

        smoothV = Double.isNaN(smoothV) ? v : smoothV + 0.15 * (v - smoothV);

        double iMa = readCurrentMa(charging, full);
        if (!Double.isNaN(iMa)) {
            smoothI = Double.isNaN(smoothI) ? iMa : smoothI + 0.15 * (iMa - smoothI);
        }

        long now = SystemClock.elapsedRealtime();
        // Missed ticks mean the count has a hole in it, so this cycle can't be used for learning
        if (lastTickMs != 0 && now - lastTickMs > 5000) cycleActive = false;
        double dtH = lastTickMs == 0 ? 0 : Math.min(now - lastTickMs, 120000L) / 3600000.0;
        lastTickMs = now;

        // Voltage estimate: add back the sag caused by the current, then use the table
        double iForV = Double.isNaN(smoothI) ? 0 : smoothI;
        double ocv = smoothV - (iForV / 1000.0) * R_OHMS;
        double estMah = pctFromVoltage(ocv) / 100.0 * capacityMah;
        double lowKnee = emptyV + 0.10;   // "nearly empty" voltage

        // Remember the last low resting voltage, in case the phone dies right after
        if (!Double.isNaN(iMa) && prefs != null) {
            if (iMa < 0 && ocv < 3.70) {
                if (now - lastLowWriteMs > 2000) {
                    prefs.edit().putFloat("lowOcv", (float) ocv).apply();
                    lastLowWriteMs = now;
                    lowRecorded = true;
                }
            } else if (lowRecorded && (charging || (iMa < 0 && ocv >= 3.80))) {
                prefs.edit().putFloat("lowOcv", -1f).apply();
                lowRecorded = false;
            }
        }

        if (remainingMah < 0 || Double.isNaN(iMa)) {
            // No saved count or no current sensor: fall back to the voltage estimate
            remainingMah = estMah;
            cycleActive = false;
        } else {
            // Coulomb counting: charge in or out = current x time
            remainingMah += iMa * dtH * (iMa > 0 ? CHARGE_EFFICIENCY : 1.0);
            // Slow pull toward the voltage estimate to fix drift (stronger near empty)
            if (Math.abs(smoothI) < 1000) {
                double k = ocv < lowKnee ? 0.02 : 0.001;
                remainingMah += (estMah - remainingMah) * k;
            }
        }

        // Learning cycle bookkeeping
        if (!Double.isNaN(iMa)) {
            if (iMa < 0) {
                if (cycleArmed) {            // unplugged after a full charge: start counting
                    cycleArmed = false;
                    cycleActive = true;
                    cycleDrawnMah = 0;
                }
                if (cycleActive) cycleDrawnMah += -iMa * dtH;
            } else if (charging) {
                cycleActive = false;         // charger plugged in mid-cycle: not a clean cycle
            }
        }

        // Full anchor: charger says full, or voltage is high and charge current has tapered
        boolean atFull = full
                || (charging && !Double.isNaN(iMa) && smoothV >= 4.33 && smoothI < 300);
        if (atFull) {
            remainingMah = capacityMah;
            cycleArmed = true;
            cycleActive = false;
            cycleDrawnMah = 0;
        }

        // Learn capacity: full -> (no charging) -> resting voltage reached the low knee
        if (cycleActive && !Double.isNaN(iMa) && Math.abs(smoothI) < 1000 && ocv <= lowKnee) {
            learnCapacity(ocv);
        }

        remainingMah = Math.max(0, Math.min(capacityMah, remainingMah));

        if (now - lastSaveMs > 30000) {
            save();
            lastSaveMs = now;
        }

        int pct = (int) Math.round(remainingMah / capacityMah * 100.0);
        String s = String.format(Locale.US, "%.1fV ~%d%%", smoothV, pct);
        return charging ? s + " \u26A1" : s;
    }

    /** Called when a clean full-to-low cycle finishes: measure the capacity and blend it in. */
    private void learnCapacity(double ocv) {
        double pctNow = pctFromVoltage(ocv);
        double frac = (100.0 - pctNow) / 100.0;   // share of capacity used since full
        cycleActive = false;
        if (frac < 0.5) return;

        double measured = cycleDrawnMah / frac;
        if (measured > 0.4 * DEFAULT_CAPACITY_MAH && measured < 1.2 * DEFAULT_CAPACITY_MAH) {
            double w = learnedCycles == 0 ? 0.5 : 0.3;
            capacityMah = (1 - w) * capacityMah + w * measured;
            learnedCycles++;
            remainingMah = pctNow / 100.0 * capacityMah;
            save();
            getSystemService(NotificationManager.class).notify(1, buildNotification());
        }
    }

    private void save() {
        if (prefs == null) return;
        SharedPreferences.Editor ed = prefs.edit();
        ed.putFloat("cap", (float) capacityMah)
                .putInt("cycles", learnedCycles)
                .putFloat("emptyV", (float) emptyV)
                .putInt("shutdowns", shutdowns);
        if (remainingMah >= 0) {
            ed.putFloat("mah", (float) remainingMah)
                    .putLong("savedAt", System.currentTimeMillis());
        }
        ed.apply();
    }

    /** Voltage to percent. Below 3.70V the curve is stretched so emptyV maps to 0%. */
    private double pctFromVoltage(double v) {
        if (v < 3.70) v = 3.40 + (v - emptyV) * (0.30 / (3.70 - emptyV));
        if (v <= VT[0]) return 0;
        if (v >= VT[VT.length - 1]) return 100;
        int k = 1;
        while (v > VT[k]) k++;
        return PT[k - 1] + (PT[k] - PT[k - 1]) * (v - VT[k - 1]) / (VT[k] - VT[k - 1]);
    }

    /** Battery current in mA: positive while charging, negative while draining. */
    private double readCurrentMa(boolean charging, boolean full) {
        if (bm == null) return Double.NaN;
        long raw = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
        if (raw == Long.MIN_VALUE) return Double.NaN;
        double mag = Math.abs((double) raw) / 1000.0;   // microamps -> milliamps
        if (charging) return mag;
        if (full) return 0;
        return -mag;
    }

    /** Raw kernel voltage. Usually microvolts, sometimes millivolts. */
    private double fromSysfs() {
        for (String path : SYSFS_PATHS) {
            try (BufferedReader r = new BufferedReader(new FileReader(path))) {
                String s = r.readLine();
                if (s == null) continue;
                double raw = Double.parseDouble(s.trim());
                if (raw <= 0) continue;
                return raw > 100000 ? raw / 1e6 : raw / 1000.0;
            } catch (Exception ignored) {
                // file missing or blocked: try the next one
            }
        }
        return Double.NaN;
    }

    /** Fallback: sticky battery broadcast, EXTRA_VOLTAGE is in millivolts. */
    private double fromBatteryApi() {
        Intent i = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (i == null) return Double.NaN;
        int mv = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1);
        return mv > 0 ? mv / 1000.0 : Double.NaN;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(tick);
        save();
        if (userStop && prefs != null) {
            // Stopped on purpose: this was not a shutdown
            prefs.edit().putBoolean("running", false).putFloat("lowOcv", -1f).apply();
        }
        if (box != null && wm != null) {
            try { wm.removeView(box); } catch (Exception ignored) { }
            box = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
            }f (raw == Long.MIN_VALUE) return Double.NaN;
        double mag = Math.abs((double) raw) / 1000.0;   // microamps -> milliamps
        if (charging) return mag;
        if (full) return 0;
        return -mag;
    }

    /** Raw kernel voltage. Usually microvolts, sometimes millivolts. */
    private double fromSysfs() {
        for (String path : SYSFS_PATHS) {
            try (BufferedReader r = new BufferedReader(new FileReader(path))) {
                String s = r.readLine();
                if (s == null) continue;
                double raw = Double.parseDouble(s.trim());
                if (raw <= 0) continue;
                return raw > 100000 ? raw / 1e6 : raw / 1000.0;
            } catch (Exception ignored) {
                // file missing or blocked: try the next one
            }
        }
        return Double.NaN;
    }

    /** Fallback: sticky battery broadcast, EXTRA_VOLTAGE is in millivolts. */
    private double fromBatteryApi() {
        Intent i = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (i == null) return Double.NaN;
        int mv = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1);
        return mv > 0 ? mv / 1000.0 : Double.NaN;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(tick);
        save();
        if (box != null && wm != null) {
            try { wm.removeView(box); } catch (Exception ignored) { }
            box = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
