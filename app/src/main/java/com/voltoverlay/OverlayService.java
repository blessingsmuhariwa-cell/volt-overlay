package com.voltoverlay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
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

    // Live battery voltage files exposed by the kernel. Many phones allow
    // apps to read these; if not, we fall back to the Android battery API.
    private static final String[] SYSFS_PATHS = {
            "/sys/class/power_supply/battery/voltage_now",
            "/sys/class/power_supply/bms/voltage_now",
    };

    private WindowManager wm;
    private TextView box;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (box != null) box.setText(readVoltage());
            handler.postDelayed(this, INTERVAL_MS);
        }
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startInForeground();
        if (!Settings.canDrawOverlays(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (box == null) addOverlay();
        return START_NOT_STICKY;
    }

    private void startInForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL, "Voltage overlay", NotificationManager.IMPORTANCE_LOW));

        PendingIntent stop = PendingIntent.getService(this, 0,
                new Intent(this, OverlayService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
                .setContentTitle("Voltage overlay running")
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                        "Stop", stop).build())
                .setOngoing(true)
                .build();

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
        box.setText("-.--- V");
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

    private String readVoltage() {
        double v = fromSysfs();
        if (Double.isNaN(v)) v = fromBatteryApi();
        return Double.isNaN(v) ? "-.--- V" : String.format(Locale.US, "%.3f V", v);
    }

    /** Raw kernel reading. Usually microvolts, sometimes millivolts. */
    private double fromSysfs() {
        for (String path : SYSFS_PATHS) {
            try (BufferedReader r = new BufferedReader(new FileReader(path))) {
                String s = r.readLine();
                if (s == null) continue;
                double raw = Double.parseDouble(s.trim());
                if (raw <= 0) continue;
                return raw > 100000 ? raw / 1e6 : raw / 1000.0;
            } catch (Exception ignored) {
                // file missing or blocked by SELinux: try the next one
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
