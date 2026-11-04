package com.copyota;

import android.accessibilityservice.AccessibilityService;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.PixelFormat;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

// Background apps can't read the clipboard, but Android 13+ shows a preview overlay on every
// copy and accessibility services can see it. Short text is taken from the preview. Previews
// that are cut off or hidden need a real read, which requires briefly holding window focus.
public class ClipService extends AccessibilityService {
    static final String TAG = "copyota";
    static final String SYSTEM_UI = "com.android.systemui";
    static final String PREVIEW_ID = SYSTEM_UI + ":id/text_preview";
    static final String HIDDEN_ID = SYSTEM_UI + ":id/hidden_preview";
    static final int PREVIEW_LIMIT = 500;
    static final long REPEAT_MS = 2000;

    String lastPreview = "";
    long lastAt;

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        AccessibilityNodeInfo root = event.getSource();
        if (root == null) return;
        for (AccessibilityNodeInfo p = root.getParent(); p != null; p = p.getParent()) root = p;

        String text = visibleText(root, HIDDEN_ID);
        boolean hidden = text != null;
        if (!hidden) text = visibleText(root, PREVIEW_ID);
        if (text == null) return; // not the clipboard overlay, or an image

        // The overlay fires several events per copy, and taking focus makes it fire again.
        long now = SystemClock.elapsedRealtime();
        boolean repeat = text.equals(lastPreview) && now - lastAt < REPEAT_MS;
        lastPreview = text;
        lastAt = now;
        if (repeat) return;

        if (hidden || text.length() >= PREVIEW_LIMIT || isGenericLabel(text)) readWithFocus();
        else send(text);
    }

    static String visibleText(AccessibilityNodeInfo root, String id) {
        List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByViewId(id);
        if (found.isEmpty() || !found.get(0).isVisibleToUser()) return null;
        return String.valueOf(found.get(0).getText());
    }

    // Clips with no text preview, like a bare URI, show SystemUI's "Copied" label instead.
    boolean isGenericLabel(String text) {
        try {
            Resources res = getPackageManager().getResourcesForApplication(SYSTEM_UI);
            int id = res.getIdentifier("clipboard_overlay_text_copied", "string", SYSTEM_UI);
            return id != 0 && text.contentEquals(res.getText(id));
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    void readWithFocus() {
        WindowManager wm = getSystemService(WindowManager.class);
        View view = new View(this) {
            @Override public void onWindowFocusChanged(boolean hasFocus) {
                if (!hasFocus) return;
                ClipData clip = getSystemService(ClipboardManager.class).getPrimaryClip();
                CharSequence text = clip == null ? null : clip.getItemAt(0).getText();
                if (text != null) send(text.toString());
                if (isAttachedToWindow()) wm.removeView(this);
            }
        };
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(1, 1,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        wm.addView(view, params);
        // Focus never arrives on some screens, so don't leave the window behind.
        view.postDelayed(() -> { if (view.isAttachedToWindow()) wm.removeView(view); }, 2000);
    }

    // ponytail: one attempt, a copy made with no network is dropped.
    void send(String text) {
        new Thread(() -> {
            try {
                byte[] nonce = new byte[12];
                new SecureRandom().nextBytes(nonce);
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.ENCRYPT_MODE,
                        new SecretKeySpec(Base64.decode(Config.KEY, Base64.DEFAULT), "AES"),
                        new GCMParameterSpec(128, nonce));
                ByteArrayOutputStream sealed = new ByteArrayOutputStream();
                sealed.write(nonce);
                sealed.write(cipher.doFinal(text.getBytes(StandardCharsets.UTF_8)));

                HttpURLConnection http = (HttpURLConnection) new URL(Config.URL + "/" + Config.ROOM).openConnection();
                http.setRequestMethod("POST");
                http.setDoOutput(true);
                http.setConnectTimeout(10000);
                http.setReadTimeout(10000);
                try (OutputStream out = http.getOutputStream()) {
                    out.write(Base64.encode(sealed.toByteArray(), Base64.NO_WRAP));
                }
                if (http.getResponseCode() >= 300) Log.w(TAG, "relay said " + http.getResponseCode());
                http.disconnect();
            } catch (Exception e) {
                Log.w(TAG, "send failed", e);
            }
        }).start();
    }

    @Override public void onInterrupt() {}
}
