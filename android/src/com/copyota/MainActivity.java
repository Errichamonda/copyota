package com.copyota;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Base64;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

// Receives the copyota://pair link from the pairing QR page and shows whether this phone is paired.
public class MainActivity extends Activity {
    TextView status;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        status = new TextView(this);
        status.setTextSize(18);
        Button settings = new Button(this);
        settings.setText("Open accessibility settings");
        settings.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setFitsSystemWindows(true);
        box.setPadding(48, 48, 48, 48);
        box.addView(status);
        box.addView(settings);
        setContentView(box);
        offer(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        offer(intent);
    }

    @Override protected void onResume() {
        super.onResume();
        String relay = getSharedPreferences(ClipService.PREFS, MODE_PRIVATE).getString("url", null);
        status.setText(relay == null
                ? "Not paired. Run ./copyota setup on your Mac and scan its code with this phone's camera."
                : "Paired with " + Uri.parse(relay).getHost() + ".\n\nTurn Copyota on in accessibility settings to start syncing copies.");
    }

    // Any website can fire this link, so nothing is saved until the user confirms the relay.
    void offer(Intent intent) {
        Uri link = intent.getData();
        if (link == null) return;
        String url = link.getQueryParameter("url"), room = link.getQueryParameter("room"), key = link.getQueryParameter("key");
        try {
            if (url == null || !url.matches("https://[A-Za-z0-9.-]+")
                    || room == null || !room.matches("[0-9a-f]{32}")
                    || key == null || Base64.decode(key, Base64.DEFAULT).length != 32) throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) {
            Toast.makeText(this, "That isn't a valid Copyota pairing code", Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Send your copies to " + Uri.parse(url).getHost() + "?")
                .setMessage("Only pair if you scanned this code from your own Mac.")
                .setPositiveButton("Pair", (d, w) -> {
                    getSharedPreferences(ClipService.PREFS, MODE_PRIVATE).edit()
                            .putString("url", url).putString("room", room).putString("key", key).apply();
                    onResume();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
