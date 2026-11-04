package com.copyota;

import android.accessibilityservice.AccessibilityService;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

// Background apps can't read the clipboard, but Android 13+ shows a preview overlay on every
// copy and accessibility services can see it. Short text is taken from the preview. Previews
// that are cut off or hidden, and images, need a real read, which requires briefly holding window focus.
//
// Every message is encrypted as one type byte plus a body. An image is split into chunks that fit
// the relay's message limit: TYPE_IMAGE, 8-byte transfer id, chunk index, chunk count, bytes.
public class ClipService extends AccessibilityService {
    static final String TAG = "copyota";
    static final String PREFS = "pairing"; // url, room and key, saved by MainActivity
    static final String SYSTEM_UI = "com.android.systemui";
    static final String PREVIEW_ID = SYSTEM_UI + ":id/text_preview";
    static final String HIDDEN_ID = SYSTEM_UI + ":id/hidden_preview";
    static final String IMAGE_ID = SYSTEM_UI + ":id/image_preview";
    static final byte TYPE_TEXT = 0, TYPE_IMAGE = 1;
    static final int CHUNK_BYTES = 600 * 1024; // base64 and encryption stay under the relay's 1 MiB
    static final int MAX_IMAGE_BYTES = 10 << 20;
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
        boolean image = text == null && visible(root, IMAGE_ID) != null;
        if (image) text = "\0image"; // only used to spot repeats
        if (text == null) return; // not the clipboard overlay

        // The overlay fires several events per copy, and taking focus makes it fire again.
        long now = SystemClock.elapsedRealtime();
        boolean repeat = text.equals(lastPreview) && now - lastAt < REPEAT_MS;
        lastPreview = text;
        lastAt = now;
        if (repeat) return;

        if (hidden || image || text.length() >= PREVIEW_LIMIT || isGenericLabel(text)) readWithFocus();
        else send(text);
    }

    static AccessibilityNodeInfo visible(AccessibilityNodeInfo root, String id) {
        List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByViewId(id);
        return found.isEmpty() || !found.get(0).isVisibleToUser() ? null : found.get(0);
    }

    static String visibleText(AccessibilityNodeInfo root, String id) {
        AccessibilityNodeInfo node = visible(root, id);
        return node == null ? null : String.valueOf(node.getText());
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
                if (clip != null) {
                    ClipData.Item item = clip.getItemAt(0);
                    if (item.getText() != null) send(item.getText().toString());
                    else if (item.getUri() != null && clip.getDescription().hasMimeType("image/*")) sendImage(item.getUri());
                }
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

    void send(String text) {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        relay(() -> Arrays.asList(message(TYPE_TEXT, body)));
    }

    // ponytail: an image over the size cap is skipped, not shrunk.
    void sendImage(Uri uri) {
        relay(() -> {
            ByteArrayOutputStream image = new ByteArrayOutputStream();
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                byte[] buf = new byte[8192];
                for (int n; (n = in.read(buf)) > 0; ) {
                    image.write(buf, 0, n);
                    if (image.size() > MAX_IMAGE_BYTES) {
                        Log.w(TAG, "image over " + MAX_IMAGE_BYTES + " bytes, skipped");
                        return new ArrayList<>();
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "couldn't read the copied image", e);
                return new ArrayList<>();
            }
            byte[] bytes = image.toByteArray();
            int total = (bytes.length + CHUNK_BYTES - 1) / CHUNK_BYTES;
            byte[] id = new byte[8];
            new SecureRandom().nextBytes(id);
            Log.i(TAG, "sending an image of " + bytes.length + " bytes in " + total + " chunks");
            List<byte[]> chunks = new ArrayList<>();
            for (int i = 0; i < total; i++) {
                byte[] part = Arrays.copyOfRange(bytes, i * CHUNK_BYTES, Math.min(bytes.length, (i + 1) * CHUNK_BYTES));
                chunks.add(message(TYPE_IMAGE, id, new byte[] {(byte) i, (byte) total}, part));
            }
            return chunks;
        });
    }

    static byte[] message(byte type, byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(type);
        for (byte[] part : parts) out.write(part, 0, part.length);
        return out.toByteArray();
    }

    // Encrypts and posts each message on its own, so a lost chunk only costs that copy.
    // ponytail: one attempt, a copy made with no network is dropped.
    void relay(Supplier<List<byte[]>> messages) {
        SharedPreferences pairing = getSharedPreferences(PREFS, MODE_PRIVATE);
        String relay = pairing.getString("url", null), room = pairing.getString("room", null), key = pairing.getString("key", null);
        if (relay == null) {
            Log.w(TAG, "not paired, dropping a copy");
            return;
        }
        new Thread(() -> {
            try {
                SecretKeySpec secret = new SecretKeySpec(Base64.decode(key, Base64.DEFAULT), "AES");
                for (byte[] plain : messages.get()) {
                    byte[] nonce = new byte[12];
                    new SecureRandom().nextBytes(nonce);
                    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                    cipher.init(Cipher.ENCRYPT_MODE, secret, new GCMParameterSpec(128, nonce));
                    ByteArrayOutputStream sealed = new ByteArrayOutputStream();
                    sealed.write(nonce);
                    sealed.write(cipher.doFinal(plain));

                    HttpURLConnection http = (HttpURLConnection) new URL(relay + "/" + room).openConnection();
                    http.setRequestMethod("POST");
                    http.setDoOutput(true);
                    http.setConnectTimeout(10000);
                    http.setReadTimeout(10000);
                    try (OutputStream out = http.getOutputStream()) {
                        out.write(Base64.encode(sealed.toByteArray(), Base64.NO_WRAP));
                    }
                    int code = http.getResponseCode();
                    if (code >= 300) {
                        Log.w(TAG, "relay said " + code);
                        return;
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "send failed", e);
            }
        }).start();
    }

    @Override public void onInterrupt() {}
}
