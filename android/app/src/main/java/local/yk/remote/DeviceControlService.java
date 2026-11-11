package local.yk.remote;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

public class DeviceControlService extends Service {
    public static final String ACTION_START_CONTROL = "local.yk.remote.START_CONTROL";
    public static final String ACTION_START_CAPTURE = "local.yk.remote.START_CAPTURE";
    public static final String ACTION_STOP_CAPTURE = "local.yk.remote.STOP_CAPTURE";
    public static final String ACTION_STOP = "local.yk.remote.STOP";
    public static final String EXTRA_PROJECTION_RESULT = "local.yk.remote.PROJECTION_RESULT";
    public static final String EXTRA_PROJECTION_DATA = "local.yk.remote.PROJECTION_DATA";
    public static final String EXTRA_ENDPOINT = "local.yk.remote.ENDPOINT";
    private static final String CHANNEL_ID = "yk_control_device";
    private static final int NOTIFICATION_ID = 5173;

    private ScreenCapture screenCapture;
    private DeviceSocketClient socketClient;
    private volatile boolean starting;

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START_CONTROL : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopSession();
            return START_NOT_STICKY;
        }
        if (ACTION_STOP_CAPTURE.equals(action)) {
            stopScreenCapture();
            return START_NOT_STICKY;
        }
        if (ACTION_START_CAPTURE.equals(action)) {
            startScreenCapture(intent);
            return START_NOT_STICKY;
        }
        startControl(intent);
        return START_NOT_STICKY;
    }

    private void startControl(Intent intent) {
        if (socketClient != null || starting) return;
        starting = true;
        createNotificationChannel();
        enterForeground(false);
        String endpoint = intent == null ? null : intent.getStringExtra(EXTRA_ENDPOINT);
        if (endpoint == null || endpoint.isEmpty()) {
            endpoint = preferences().getString("endpoint", "");
        }
        if (endpoint.isEmpty()) {
            starting = false;
            setState(false, false, false, "请先设置电脑服务地址");
            stopForegroundCompat();
            stopSelf();
            return;
        }
        try {
            DeviceSocketClient client = new DeviceSocketClient(this, endpoint,
                    (connected, message) -> setState(true, connected, screenCapture != null, message));
            socketClient = client;
            ControlAccessibilityService accessibility = ControlAccessibilityService.instance;
            if (accessibility != null) accessibility.setScreenTextSink(client::sendScreenText);
            client.connect();
            preferences().edit()
                    .putBoolean("running", true)
                    .putBoolean("connected", false)
                    .putBoolean("screenSharing", false)
                    .putString("endpoint", endpoint)
                    .putString("status", "正在连接电脑控制台")
                    .remove("error")
                    .apply();
        } catch (Exception error) {
            setState(false, false, false, error.getMessage() == null ? "连接启动失败" : error.getMessage());
            stopForegroundCompat();
            stopSelf();
        } finally {
            starting = false;
        }
    }

    private void startScreenCapture(Intent intent) {
        if (screenCapture != null) return;
        if (socketClient == null) startControl(intent);
        if (socketClient == null) return;

        int resultCode = intent == null ? 0 : intent.getIntExtra(EXTRA_PROJECTION_RESULT, 0);
        Intent resultData = null;
        if (intent != null && Build.VERSION.SDK_INT >= 33) {
            resultData = intent.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent.class);
        } else if (intent != null) {
            resultData = intent.getParcelableExtra(EXTRA_PROJECTION_DATA);
        }
        if (resultData == null) {
            setState(true, preferences().getBoolean("connected", false), false, "屏幕共享授权未提供；控制连接仍可用");
            return;
        }

        enterForeground(true);
        try {
            screenCapture = ScreenCapture.start(this, resultCode, resultData, socketClient::sendFrame, this::stopScreenCapture);
            socketClient.setScreenSharing(true);
            setState(true, preferences().getBoolean("connected", false), true, "控制连接和屏幕共享已开启");
        } catch (Exception error) {
            enterForeground(false);
            setState(true, preferences().getBoolean("connected", false), false,
                    error.getMessage() == null ? "屏幕共享启动失败；控制连接仍可用" : error.getMessage());
        }
    }

    private void stopScreenCapture() {
        ScreenCapture current = screenCapture;
        screenCapture = null;
        if (current != null) current.close();
        if (socketClient != null) socketClient.setScreenSharing(false);
        boolean running = socketClient != null;
        boolean connected = preferences().getBoolean("connected", false);
        setState(running, connected, false, running ? "屏幕共享已停止；控制连接仍可用" : "控制会话已停止");
        if (running) enterForeground(false);
        else stopSelf();
    }

    private void enterForeground(boolean withScreenCapture) {
        Notification notification = buildNotification(withScreenCapture);
        if (Build.VERSION.SDK_INT >= 29) {
            int types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
            if (withScreenCapture) types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
            startForeground(NOTIFICATION_ID, notification, types);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void setState(boolean running, boolean connected, boolean sharing, String status) {
        preferences().edit()
                .putBoolean("running", running)
                .putBoolean("connected", connected)
                .putBoolean("screenSharing", sharing)
                .putString("status", status)
                .apply();
    }

    private Notification buildNotification(boolean sharing) {
        Intent stopIntent = new Intent(this, DeviceControlService.class);
        stopIntent.setAction(ACTION_STOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent stopPending = PendingIntent.getService(this, 2, stopIntent, flags);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        builder.setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("yk Remote 控制已运行")
                .setContentText(sharing ? "屏幕共享已开启；点按通知可停止会话" : "控制通道已连接；屏幕共享可选")
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_pause, "停止全部", stopPending).build());
        return builder.build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "yk Remote 控制", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("本地控制通道状态；屏幕共享可单独开启");
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private SharedPreferences preferences() { return getSharedPreferences("yk_remote", MODE_PRIVATE); }

    private void stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
    }

    private void stopSession() {
        starting = false;
        if (screenCapture != null) {
            ScreenCapture current = screenCapture;
            screenCapture = null;
            current.close();
        }
        if (socketClient != null) {
            ControlAccessibilityService accessibility = ControlAccessibilityService.instance;
            if (accessibility != null) accessibility.setScreenTextSink(null);
            socketClient.close();
            socketClient = null;
        }
        setState(false, false, false, "控制会话已停止");
        stopForegroundCompat();
        stopSelf();
    }

    @Override public void onDestroy() {
        if (screenCapture != null) screenCapture.close();
        if (socketClient != null) {
            ControlAccessibilityService accessibility = ControlAccessibilityService.instance;
            if (accessibility != null) accessibility.setScreenTextSink(null);
            socketClient.close();
        }
        setState(false, false, false, "控制会话已停止");
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
