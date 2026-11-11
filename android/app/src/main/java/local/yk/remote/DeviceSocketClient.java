package local.yk.remote;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

public final class DeviceSocketClient {
    public interface StateListener { void onState(boolean connected, String message); }

    private final Context context;
    private final String endpoint;
    private final StateListener stateListener;
    private final OkHttpClient client;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile WebSocket socket;
    private volatile boolean stopped;
    private volatile boolean screenSharing;
    private boolean retryScheduled;

    public DeviceSocketClient(Context context, String endpoint, StateListener listener) {
        this.context = context.getApplicationContext();
        this.endpoint = endpoint;
        this.stateListener = listener;
        this.client = new OkHttpClient.Builder().pingInterval(15, TimeUnit.SECONDS).build();
    }

    public void connect() {
        if (stopped || socket != null) return;
        Request request = new Request.Builder().url(endpoint).build();
        socket = client.newWebSocket(request, new WebSocketListener() {
            @Override public void onOpen(WebSocket webSocket, Response response) {
                if (stopped) { webSocket.close(1000, "stopped"); return; }
                socket = webSocket;
                retryScheduled = false;
                stateListener.onState(true, "已连接到电脑服务");
                sendDeviceInfo();
                ControlAccessibilityService accessibility = ControlAccessibilityService.instance;
                if (accessibility != null) accessibility.requestScreenTextSnapshot();
            }

            @Override public void onMessage(WebSocket webSocket, String text) {
                try {
                    JSONObject message = new JSONObject(text);
                    if ("control".equals(message.optString("type"))) {
                        ControlAccessibilityService service = ControlAccessibilityService.instance;
                        if (service != null) service.handleRemoteControl(message);
                    }
                } catch (Exception ignored) {}
            }

            @Override public void onClosed(WebSocket webSocket, int code, String reason) {
                if (socket == webSocket) socket = null;
                if (stopped) return;
                stateListener.onState(false, "电脑服务已断开");
                scheduleReconnect();
            }

            @Override public void onFailure(WebSocket webSocket, Throwable error, Response response) {
                if (socket == webSocket) socket = null;
                if (stopped) return;
                stateListener.onState(false, "等待 localhost:5173 服务");
                scheduleReconnect();
            }
        });
    }

    private void scheduleReconnect() {
        if (stopped || retryScheduled) return;
        retryScheduled = true;
        handler.postDelayed(() -> {
            retryScheduled = false;
            if (!stopped) connect();
        }, 1400);
    }

    public void sendFrame(byte[] jpeg) {
        WebSocket current = socket;
        if (current == null || jpeg == null || current.queueSize() > 1_500_000) return;
        current.send(ByteString.of(jpeg));
    }

    public void sendScreenText(String snapshot) {
        WebSocket current = socket;
        if (current == null || snapshot == null || snapshot.length() > 64_000 || current.queueSize() > 500_000) return;
        current.send(snapshot);
    }

    public void setScreenSharing(boolean enabled) {
        screenSharing = enabled;
        sendDeviceInfo();
    }

    private void sendDeviceInfo() {
        WebSocket current = socket;
        if (current == null) return;
        try {
            JSONObject info = new JSONObject();
            info.put("type", "device_info");
            info.put("name", "MuMu Android " + android.os.Build.VERSION.RELEASE);
            info.put("screenSharing", screenSharing);
            current.send(info.toString());
        } catch (Exception ignored) {}
    }

    public void close() {
        stopped = true;
        handler.removeCallbacksAndMessages(null);
        WebSocket current = socket;
        socket = null;
        if (current != null) current.close(1000, "session stopped");
        client.dispatcher().cancelAll();
        client.connectionPool().evictAll();
    }
}
