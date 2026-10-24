package com.voltoverlay;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private Button button(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets i = insets.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(dp(24) + i.left, dp(24) + i.top, dp(24) + i.right, dp(24) + i.bottom);
            return insets;
        });

        TextView info = new TextView(this);
        info.setTextSize(16);
        info.setText("Shows your battery voltage in a small box on top of every app.\n"
                + "Drag the box to move it.\n\n"
                + "Step 1: allow \"Display over other apps\".\n"
                + "Step 2: start the overlay.");
        info.setPadding(0, 0, 0, dp(16));
        root.addView(info);

        root.addView(button("1. Allow display over other apps", v ->
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())))));

        root.addView(button("2. Start overlay", v -> startOverlay()));

        root.addView(button("Stop overlay", v ->
                startService(new Intent(this, OverlayService.class)
                        .setAction(OverlayService.ACTION_STOP))));

        setContentView(root);
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
}
