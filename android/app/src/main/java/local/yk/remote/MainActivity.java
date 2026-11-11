package local.yk.remote;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final int REQUEST_ACCESSIBILITY = 4101;
    private static final int REQUEST_PROJECTION = 4102;
    private static final String DEFAULT_ENDPOINT = "ws://127.0.0.1:5173/device?key=yk-device-mumu-v1&name=MuMu%20Android";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable statusRefresh = new Runnable() {
        @Override public void run() {
            refreshState();
            handler.postDelayed(this, 900);
        }
    };
    private EditText endpointInput;
    private TextView accessibilityState;
    private TextView controlState;
    private TextView screenState;
    private boolean pendingConnectAfterSettings;
    private boolean projectionPromptOpen;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildScreen();
        refreshState();
    }

    @Override protected void onResume() {
        super.onResume();
        refreshState();
        handler.removeCallbacks(statusRefresh);
        handler.postDelayed(statusRefresh, 900);
        if (pendingConnectAfterSettings && isAccessibilityEnabled()) {
            pendingConnectAfterSettings = false;
            waitForAccessibilityBinding(0);
        }
    }

    @Override protected void onPause() {
        handler.removeCallbacks(statusRefresh);
        super.onPause();
    }

    private void buildScreen() {
        int pad = dp(22);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(pad, dp(28), pad, dp(24));
        page.setBackgroundColor(0xFFF5F7FB);

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        content.addView(text("yk Remote", 28, 0xFF182232, true), matchWrap());
        TextView intro = text("先连接电脑控制台；屏幕共享可以单独开启。", 15, 0xFF657286, false);
        intro.setPadding(0, dp(7), 0, dp(22));
        content.addView(intro, matchWrap());

        LinearLayout accessCard = card(content);
        accessCard.addView(text("无障碍权限", 18, 0xFF182232, true), matchWrap());
        TextView accessCopy = text("首次使用时，需要你在系统设置中手动启用 yk Remote control。", 14, 0xFF687589, false);
        accessCopy.setPadding(0, dp(8), 0, dp(12));
        accessCard.addView(accessCopy, matchWrap());
        accessibilityState = text("检查无障碍状态…", 13, 0xFF3972CA, true);
        accessCard.addView(accessibilityState, matchWrap());
        Button settingsButton = button("打开无障碍设置");
        settingsButton.setOnClickListener(v -> openAccessibilitySettings(false));
        accessCard.addView(settingsButton, buttonLayout());

        LinearLayout endpointCard = card(content);
        endpointCard.addView(text("电脑服务地址", 18, 0xFF182232, true), matchWrap());
        TextView endpointCopy = text("MuMu 默认使用 ADB 反向端口，无需填写电脑 IP。请先在电脑运行 start.ps1。", 14, 0xFF687589, false);
        endpointCopy.setPadding(0, dp(8), 0, dp(12));
        endpointCard.addView(endpointCopy, matchWrap());
        endpointInput = new EditText(this);
        endpointInput.setSingleLine(true);
        endpointInput.setTextSize(13);
        endpointInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        endpointInput.setHint("ws://127.0.0.1:5173/device?key=…");
        endpointInput.setText(preferences().getString("endpoint", DEFAULT_ENDPOINT));
        endpointCard.addView(endpointInput, matchWrap());

        LinearLayout controlCard = card(content);
        controlCard.addView(text("第 1 步：连接控制通道", 18, 0xFF182232, true), matchWrap());
        TextView controlCopy = text("连接后即可从网页发送点按、滑动、导航和文字操作。这一步不会请求屏幕共享。", 14, 0xFF687589, false);
        controlCopy.setPadding(0, dp(8), 0, dp(12));
        controlCard.addView(controlCopy, matchWrap());
        controlState = text("控制通道未启动", 13, 0xFF687589, true);
        controlCard.addView(controlState, matchWrap());
        Button connectButton = button("连接电脑控制台");
        connectButton.setOnClickListener(v -> beginConnect());
        controlCard.addView(connectButton, buttonLayout());

        LinearLayout screenCard = card(content);
        screenCard.addView(text("第 2 步：屏幕共享（可选）", 18, 0xFF182232, true), matchWrap());
        TextView screenCopy = text("需要在网页查看实时画面时再开启。Android 会显示系统确认框；不授权时控制通道仍可继续使用。", 14, 0xFF687589, false);
        screenCopy.setPadding(0, dp(8), 0, dp(12));
        screenCard.addView(screenCopy, matchWrap());
        screenState = text("未开启屏幕共享", 13, 0xFF687589, true);
        screenCard.addView(screenState, matchWrap());
        Button shareButton = button("开始屏幕共享");
        shareButton.setOnClickListener(v -> beginScreenShare());
        screenCard.addView(shareButton, buttonLayout());
        Button stopShareButton = button("停止屏幕共享（保留控制连接）");
        stopShareButton.setOnClickListener(v -> stopScreenShare());
        screenCard.addView(stopShareButton, buttonLayout());

        Button stopAllButton = button("停止全部控制");
        stopAllButton.setOnClickListener(v -> stopSession());
        LinearLayout.LayoutParams stopParams = buttonLayout();
        stopParams.bottomMargin = dp(14);
        content.addView(stopAllButton, stopParams);

        TextView foot = text("电脑后台只需运行 localhost:5173。只连接本机服务，不要将端口转发到公网。", 12, 0xFF8994A4, false);
        content.addView(foot, matchWrap());
        scroll.addView(content);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(page);
    }

    private void beginConnect() {
        String endpoint = endpointInput.getText().toString().trim();
        if (!endpoint.startsWith("ws://") && !endpoint.startsWith("wss://")) {
            controlState.setText("地址必须以 ws:// 或 wss:// 开头");
            return;
        }
        preferences().edit().putString("endpoint", endpoint).apply();
        if (!isAccessibilityEnabled()) {
            pendingConnectAfterSettings = true;
            openAccessibilitySettings(true);
            return;
        }
        if (preferences().getBoolean("running", false)) {
            refreshState();
            return;
        }
        if (ControlAccessibilityService.instance == null) {
            waitForAccessibilityBinding(0);
            return;
        }
        startControlService(endpoint);
    }

    private void openAccessibilitySettings(boolean initiatedConnect) {
        if (initiatedConnect) pendingConnectAfterSettings = true;
        try {
            startActivityForResult(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), REQUEST_ACCESSIBILITY);
        } catch (Exception error) {
            accessibilityState.setText("无法打开系统无障碍设置");
        }
    }

    private void waitForAccessibilityBinding(int attempt) {
        refreshState();
        if (ControlAccessibilityService.instance != null) {
            startControlService(preferences().getString("endpoint", DEFAULT_ENDPOINT));
            return;
        }
        if (attempt >= 12) {
            pendingConnectAfterSettings = false;
            controlState.setText("请确认系统设置中的 yk Remote control 已开启，再点连接");
            return;
        }
        handler.postDelayed(() -> waitForAccessibilityBinding(attempt + 1), 400);
    }

    private void beginScreenShare() {
        if (!preferences().getBoolean("running", false)) {
            screenState.setText("请先完成第 1 步，连接控制通道");
            return;
        }
        if (projectionPromptOpen) return;
        try {
            MediaProjectionManager manager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            Intent consentIntent = Build.VERSION.SDK_INT >= 34
                    ? manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                    : manager.createScreenCaptureIntent();
            projectionPromptOpen = true;
            startActivityForResult(consentIntent, REQUEST_PROJECTION);
        } catch (Exception error) {
            screenState.setText("无法请求屏幕共享授权：" + message(error));
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_ACCESSIBILITY) {
            refreshState();
            if (!isAccessibilityEnabled()) {
                pendingConnectAfterSettings = false;
                controlState.setText("需要你在系统设置中手动启用服务");
            } else if (pendingConnectAfterSettings) {
                pendingConnectAfterSettings = false;
                waitForAccessibilityBinding(0);
            }
            return;
        }
        if (requestCode == REQUEST_PROJECTION) {
            projectionPromptOpen = false;
            if (resultCode != Activity.RESULT_OK || data == null) {
                screenState.setText("未授权屏幕共享；控制通道不受影响");
                return;
            }
            startScreenCaptureService(resultCode, data);
        }
    }

    private void startControlService(String endpoint) {
        Intent service = new Intent(this, DeviceControlService.class)
                .setAction(DeviceControlService.ACTION_START_CONTROL)
                .putExtra(DeviceControlService.EXTRA_ENDPOINT, endpoint);
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(service);
            else startService(service);
            preferences().edit().putBoolean("running", true).putBoolean("connected", false)
                    .putBoolean("screenSharing", false).putString("status", "正在连接电脑控制台").apply();
            controlState.setText("正在连接电脑控制台…");
        } catch (Exception error) {
            controlState.setText("连接启动失败：" + message(error));
        }
        refreshState();
    }

    private void startScreenCaptureService(int resultCode, Intent data) {
        Intent service = new Intent(this, DeviceControlService.class)
                .setAction(DeviceControlService.ACTION_START_CAPTURE)
                .putExtra(DeviceControlService.EXTRA_PROJECTION_RESULT, resultCode)
                .putExtra(DeviceControlService.EXTRA_PROJECTION_DATA, data)
                .putExtra(DeviceControlService.EXTRA_ENDPOINT, preferences().getString("endpoint", DEFAULT_ENDPOINT));
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(service);
            else startService(service);
            preferences().edit().putBoolean("screenSharing", true).putString("status", "控制连接和屏幕共享已开启").apply();
            screenState.setText("正在开启屏幕共享…");
        } catch (Exception error) {
            screenState.setText("屏幕共享启动失败：" + message(error) + "；控制通道仍可用");
        }
        refreshState();
    }

    private void stopScreenShare() {
        Intent service = new Intent(this, DeviceControlService.class).setAction(DeviceControlService.ACTION_STOP_CAPTURE);
        startService(service);
        preferences().edit().putBoolean("screenSharing", false).apply();
        refreshState();
    }

    private void stopSession() {
        Intent service = new Intent(this, DeviceControlService.class).setAction(DeviceControlService.ACTION_STOP);
        startService(service);
        preferences().edit().putBoolean("running", false).putBoolean("connected", false)
                .putBoolean("screenSharing", false).putString("status", "控制会话已停止").apply();
        refreshState();
    }

    private boolean isAccessibilityEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        ComponentName component = new ComponentName(this, ControlAccessibilityService.class);
        String flat = component.flattenToString();
        String shortFlat = component.flattenToShortString();
        for (String item : enabled.split(":")) {
            if (flat.equalsIgnoreCase(item) || shortFlat.equalsIgnoreCase(item)) return true;
        }
        return false;
    }

    private void refreshState() {
        SharedPreferences prefs = preferences();
        boolean accessEnabled = isAccessibilityEnabled();
        boolean running = prefs.getBoolean("running", false);
        boolean connected = prefs.getBoolean("connected", false);
        boolean sharing = prefs.getBoolean("screenSharing", false);
        String status = prefs.getString("status", "控制通道未启动");
        if (accessibilityState != null) {
            accessibilityState.setText(accessEnabled ? "已启用：可建立电脑控制连接" : "尚未启用：请打开系统无障碍设置");
            accessibilityState.setTextColor(accessEnabled ? 0xFF248958 : 0xFFB47B2E);
        }
        if (controlState != null) controlState.setText(running ? (connected ? "电脑控制台已连接" : status) : "控制通道未启动");
        if (screenState != null) {
            screenState.setText(sharing ? "屏幕共享正在运行" : (running ? "未开启；控制通道仍可使用" : "先连接控制通道，再按需开启"));
        }
    }

    private SharedPreferences preferences() { return getSharedPreferences("yk_remote", MODE_PRIVATE); }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private LinearLayout.LayoutParams matchWrap() { return new LinearLayout.LayoutParams(-1, -2); }
    private LinearLayout.LayoutParams buttonLayout() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(48));
        params.topMargin = dp(11);
        return params;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(null, android.graphics.Typeface.BOLD);
        return view;
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTextColor(0xFFFFFFFF);
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF236FDA));
        return button;
    }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(17), dp(17), dp(17), dp(17));
        box.setBackgroundColor(0xFFFFFFFF);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(14);
        box.setElevation(dp(2));
        parent.addView(box, params);
        return box;
    }

    private String message(Exception error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}
