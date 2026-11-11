package local.yk.remote;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

public class ControlAccessibilityService extends AccessibilityService {
    public static volatile ControlAccessibilityService instance;
    private final Handler textHandler = new Handler(Looper.getMainLooper());
    private final Set<String> seenText = new HashSet<>();
    private volatile Consumer<String> screenTextSink;
    private volatile boolean forceNextSnapshot;
    private String lastTextSnapshot = "";
    private final Runnable captureTextTask = () -> {
        boolean force = forceNextSnapshot;
        forceNextSnapshot = false;
        captureScreenText(force);
    };

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || type == AccessibilityEvent.TYPE_WINDOWS_CHANGED
                || type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                || type == AccessibilityEvent.TYPE_VIEW_SCROLLED
                || type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
                || type == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            scheduleTextSnapshot(false);
        }
    }

    @Override public void onInterrupt() {}

    @Override public void onDestroy() {
        textHandler.removeCallbacksAndMessages(null);
        screenTextSink = null;
        if (instance == this) instance = null;
        super.onDestroy();
    }

    public void setScreenTextSink(Consumer<String> sink) {
        screenTextSink = sink;
    }

    public void requestScreenTextSnapshot() {
        scheduleTextSnapshot(true);
    }

    private void scheduleTextSnapshot(boolean force) {
        forceNextSnapshot |= force;
        textHandler.removeCallbacks(captureTextTask);
        textHandler.postDelayed(captureTextTask, force ? 0 : 220);
    }

    private void captureScreenText(boolean force) {
        Consumer<String> sink = screenTextSink;
        if (sink == null) return;
        Point size = displaySize();
        JSONArray items = new JSONArray();
        seenText.clear();
        String app = "";
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null) {
            CharSequence packageName = root.getPackageName();
            if (packageName != null) app = packageName.toString();
            collectText(root, items, size, 0, new int[]{0}, new int[]{0});
        }
        try {
            JSONObject snapshot = new JSONObject();
            snapshot.put("type", "screen_text");
            snapshot.put("width", size.x);
            snapshot.put("height", size.y);
            snapshot.put("app", app);
            snapshot.put("items", items);
            String contentKey = size.x + "x" + size.y + "|" + items;
            if (!force && contentKey.equals(lastTextSnapshot)) return;
            lastTextSnapshot = contentKey;
            sink.accept(snapshot.toString());
        } catch (Exception ignored) {}
    }

    private void collectText(AccessibilityNodeInfo node, JSONArray items, Point size, int depth,
                             int[] nodeCount, int[] characterCount) {
        if (node == null) return;
        try {
            if (depth > 32 || nodeCount[0] >= 350 || characterCount[0] >= 12000) return;
            nodeCount[0]++;
            if (node.isVisibleToUser() && !node.isPassword() && !node.isEditable()) {
                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                Rect clipped = new Rect(bounds);
                if (clipped.intersect(0, 0, size.x, size.y)) {
                    CharSequence text = node.getText();
                    CharSequence description = node.getContentDescription();
                    addTextItem(items, text, clipped, size, node.isClickable(), characterCount);
                    if (description != null && (text == null || !description.toString().equals(text.toString()))) {
                        addTextItem(items, description, clipped, size, node.isClickable(), characterCount);
                    }
                }
            }
            int childCount = Math.min(node.getChildCount(), 80);
            for (int i = 0; i < childCount && nodeCount[0] < 350 && characterCount[0] < 12000; i++) {
                collectText(node.getChild(i), items, size, depth + 1, nodeCount, characterCount);
            }
        } catch (Exception ignored) {
        } finally {
            try { node.recycle(); } catch (Exception ignored) {}
        }
    }

    private void addTextItem(JSONArray items, CharSequence value, Rect bounds, Point size,
                             boolean clickable, int[] characterCount) {
        if (value == null || items.length() >= 300 || size.x <= 0 || size.y <= 0) return;
        String text = value.toString().replaceAll("\\s+", " ").trim();
        if (text.isEmpty()) return;
        if (text.length() > 300) text = text.substring(0, 300);
        String key = text + "@" + bounds.left + "," + bounds.top + "," + bounds.right + "," + bounds.bottom;
        if (!seenText.add(key)) return;
        characterCount[0] += text.length();
        if (characterCount[0] > 12000) return;
        try {
            JSONObject item = new JSONObject();
            item.put("text", text);
            item.put("left", bounds.left / (double) size.x);
            item.put("top", bounds.top / (double) size.y);
            item.put("right", bounds.right / (double) size.x);
            item.put("bottom", bounds.bottom / (double) size.y);
            item.put("x", (bounds.centerX()) / (double) size.x);
            item.put("y", (bounds.centerY()) / (double) size.y);
            item.put("clickable", clickable);
            items.put(item);
        } catch (Exception ignored) {}
    }

    public Point displaySize() {
        Point size = new Point();
        WindowManager manager = (WindowManager) getSystemService(WINDOW_SERVICE);
        Display display = manager.getDefaultDisplay();
        display.getRealSize(size);
        return size;
    }

    public void handleRemoteControl(JSONObject message) {
        String action = message.optString("action", "");
        try {
            switch (action) {
                case "tap": {
                    Point size = displaySize();
                    float x = (float) message.getDouble("x");
                    float y = (float) message.getDouble("y");
                    if (message.optBoolean("normalized", false)) { x *= size.x; y *= size.y; }
                    if (x >= 0 && y >= 0 && x <= size.x && y <= size.y) tap(x, y);
                    break;
                }
                case "swipe": {
                    Point size = displaySize();
                    float x1 = (float) message.getDouble("x1");
                    float y1 = (float) message.getDouble("y1");
                    float x2 = (float) message.getDouble("x2");
                    float y2 = (float) message.getDouble("y2");
                    if (message.optBoolean("normalized", false)) { x1 *= size.x; x2 *= size.x; y1 *= size.y; y2 *= size.y; }
                    if (inside(x1, y1, size) && inside(x2, y2, size)) swipe(x1, y1, x2, y2, message.optLong("duration", 300));
                    break;
                }
                case "back": performGlobalAction(GLOBAL_ACTION_BACK); break;
                case "home": performGlobalAction(GLOBAL_ACTION_HOME); break;
                case "recents": performGlobalAction(GLOBAL_ACTION_RECENTS); break;
                case "notifications": performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS); break;
                case "text": setFocusedText(message.optString("text", "")); break;
            }
        } catch (Exception ignored) {}
    }

    private boolean inside(float x, float y, Point size) {
        return x >= 0 && y >= 0 && x <= size.x && y <= size.y;
    }

    private boolean tap(float x, float y) {
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 70)).build();
        return dispatchGesture(gesture, null, null);
    }

    private boolean swipe(float x1, float y1, float x2, float y2, long duration) {
        Path path = new Path();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, Math.max(80, Math.min(1200, duration)))).build();
        return dispatchGesture(gesture, null, null);
    }

    private boolean setFocusedText(String value) {
        if (value.length() > 4000) return false;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        boolean changed = false;
        if (focused != null && !focused.isPassword()) {
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
            changed = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        }
        if (focused != null) focused.recycle();
        root.recycle();
        return changed;
    }
}
