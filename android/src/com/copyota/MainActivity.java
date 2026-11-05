package com.copyota;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.util.Base64;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

// Receives the copyota://pair link from the pairing QR page, or the same link pasted by hand, and shows the setup steps.
public class MainActivity extends Activity {
    static final java.util.Random RANDOM = new java.util.Random();
    static final RenderEffect BLUR = RenderEffect.createBlurEffect(16, 16, Shader.TileMode.CLAMP);

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.main);
        View settings = findViewById(R.id.settings);
        settings.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        // Springy press: squash on touch, overshoot back on release.
        settings.setOnTouchListener((v, e) -> {
            int a = e.getAction();
            if (a == MotionEvent.ACTION_DOWN) v.animate().scaleX(.95f).scaleY(.95f).setDuration(120).setInterpolator(null);
            else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL)
                v.animate().scaleX(1).scaleY(1).setDuration(350).setInterpolator(new OvershootInterpolator(3));
            return false;
        });
        findViewById(R.id.manual).setOnClickListener(v -> askForLink());
        findViewById(R.id.screenshots).setOnClickListener(v -> requestPermissions(new String[] {Manifest.permission.READ_MEDIA_IMAGES}, 0));
        View hero = findViewById(R.id.hero);
        hero.setScaleX(.5f);
        hero.setScaleY(.5f);
        hero.setRotation(-40);
        hero.animate().scaleX(1).scaleY(1).rotation(0).setDuration(700).setInterpolator(new OvershootInterpolator(2));
        offer(getIntent().getData());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        offer(intent.getData());
    }

    @Override protected void onResume() {
        super.onResume();
        String relay = getSharedPreferences(ClipService.PREFS, MODE_PRIVATE).getString("url", null);
        String on = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        boolean running = on != null && on.contains(getPackageName() + "/");
        ((TextView) findViewById(R.id.tagline)).setText(relay != null && running
                ? "Copy on your phone. Paste on your Mac." : "Two quick steps and you're syncing.");
        step(R.id.pair, 1, "Pair with your Mac", relay != null,
                relay == null ? "Run ./copyota setup on your Mac and scan its code with this phone's camera." : "Paired with");
        // The relay URL is private, so it starts blurred each time and a tap toggles it.
        TextView link = findViewById(R.id.pair).findViewById(R.id.link);
        link.setVisibility(relay == null ? View.GONE : View.VISIBLE);
        if (relay != null) {
            String host = Uri.parse(relay).getHost();
            link.setActivated(false); // activated = revealed
            hide(link, host);
            link.setOnClickListener(v -> {
                v.setActivated(!v.isActivated());
                if (v.isActivated()) { link.setText(host); link.setRenderEffect(null); } else hide(link, host);
            });
        }
        step(R.id.service, 2, "Turn on Copyota", running,
                running ? "Watching for copies." : "Switch it on in Settings > Accessibility > Copyota.");
        boolean photos = checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED;
        step(R.id.screenshots, 3, "Send screenshots", photos,
                photos ? "New screenshots go to your Mac." : "Tap to allow access to all photos.");
    }

    // After repeated denials Android stops showing the dialog, so the setting is the only way left.
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        if (results.length > 0 && results[0] != PackageManager.PERMISSION_GRANTED
                && !shouldShowRequestPermissionRationale(Manifest.permission.READ_MEDIA_IMAGES))
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
    }

    // Blurred state shows random characters of the same length, so the blur alone never has to hide the real text.
    void hide(TextView link, String host) {
        StringBuilder junk = new StringBuilder();
        for (int i = 0; i < host.length(); i++) junk.append("abcdefghijklmnopqrstuvwxyz0123456789".charAt(RANDOM.nextInt(36)));
        link.setText(junk);
        link.setRenderEffect(BLUR);
    }

    void step(int id, int number, String title, boolean done, String text) {
        View card = findViewById(id);
        card.setBackgroundResource(done ? R.drawable.card_done : R.drawable.card);
        TextView badge = card.findViewById(R.id.badge);
        badge.setBackgroundResource(done ? R.drawable.badge_done : R.drawable.badge);
        badge.setText(done ? "\u2713" : String.valueOf(number));
        badge.setTextColor(getColor(done ? R.color.on_primary : R.color.on_secondary_container));
        TextView t = card.findViewById(R.id.title), d = card.findViewById(R.id.text);
        t.setText(title);
        d.setText(text);
        t.setTextColor(getColor(done ? R.color.on_primary_container : R.color.on_surface));
        d.setTextColor(getColor(done ? R.color.on_primary_container : R.color.on_surface_variant));
        ((TextView) card.findViewById(R.id.link)).setTextColor(t.getCurrentTextColor());
    }

    // Takes the https://.../pair#room=...&key=... link that copyota pair prints, for when the QR can't be scanned.
    void askForLink() {
        EditText input = new EditText(this);
        input.setHint("https://\u2026/pair#room=\u2026");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING); // the link holds the private key
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Enter pairing link")
                .setMessage("Run ./copyota pair on your Mac and paste the link it prints.")
                .setPositiveButton("Continue", (d, w) -> {
                    // Whitespace is stripped because a link copied from a terminal can arrive wrapped.
                    Uri page = Uri.parse(input.getText().toString().replaceAll("\\s", ""));
                    offer(Uri.parse("copyota://pair?url=" + Uri.encode(page.getScheme() + "://" + page.getHost()) + "&" + page.getEncodedFragment()));
                })
                .setNegativeButton("Cancel", null)
                .create();
        int side = (int) (24 * getResources().getDisplayMetrics().density);
        dialog.setView(input, side, 0, side, 0);
        dialog.show();
    }

    // Any website can fire this link, so nothing is saved until the user confirms the relay.
    void offer(Uri link) {
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
                .setMessage("Only pair if this code came from your own Mac.")
                .setPositiveButton("Pair", (d, w) -> {
                    getSharedPreferences(ClipService.PREFS, MODE_PRIVATE).edit()
                            .putString("url", url).putString("room", room).putString("key", key).apply();
                    onResume();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
