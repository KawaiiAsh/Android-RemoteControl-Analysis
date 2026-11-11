package local.yk.remote;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.function.Consumer;

public final class ScreenCapture implements AutoCloseable {
    private final MediaProjection projection;
    private final HandlerThread worker;
    private final ImageReader reader;
    private final Consumer<byte[]> frameSink;
    private final Runnable onSystemStop;
    private volatile boolean closed;
    private VirtualDisplay virtualDisplay;
    private long lastFrameAt;

    private ScreenCapture(MediaProjection projection, HandlerThread worker, ImageReader reader,
                          Consumer<byte[]> frameSink, Runnable onSystemStop) {
        this.projection = projection;
        this.worker = worker;
        this.reader = reader;
        this.frameSink = frameSink;
        this.onSystemStop = onSystemStop;
    }

    public static ScreenCapture start(Context context, int resultCode, Intent resultData,
                                      Consumer<byte[]> frameSink, Runnable onSystemStop) throws Exception {
        ControlAccessibilityService access = ControlAccessibilityService.instance;
        if (access == null) throw new IllegalStateException("请先启用 yk Remote control 无障碍服务");
        MediaProjectionManager manager = (MediaProjectionManager) context.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        MediaProjection projection = manager.getMediaProjection(resultCode, resultData);
        if (projection == null) throw new IllegalStateException("系统没有授权屏幕共享");

        Point size = access.displaySize();
        int width = size.x;
        int height = size.y;
        if (width > 1080) {
            height = Math.round(height * (1080f / width));
            width = 1080;
        }
        WindowManager windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(metrics);

        HandlerThread worker = new HandlerThread("yk-screen-stream");
        worker.start();
        ImageReader reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        ScreenCapture capture = new ScreenCapture(projection, worker, reader, frameSink, onSystemStop);
        Handler handler = new Handler(worker.getLooper());
        reader.setOnImageAvailableListener(capture::onImageAvailable, handler);
        projection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() {
                boolean unexpected = !capture.closed;
                capture.close();
                if (unexpected && capture.onSystemStop != null) capture.onSystemStop.run();
            }
        }, handler);
        try {
            capture.virtualDisplay = projection.createVirtualDisplay("yk Remote", width, height,
                    metrics.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.getSurface(), null, handler);
        } catch (Exception error) {
            capture.close();
            throw error;
        }
        if (capture.virtualDisplay == null) {
            capture.close();
            throw new IllegalStateException("无法创建屏幕共享窗口");
        }
        return capture;
    }

    private void onImageAvailable(ImageReader source) {
        if (closed) return;
        Image image = null;
        Bitmap padded = null;
        Bitmap cropped = null;
        try {
            image = source.acquireLatestImage();
            if (image == null) return;
            long now = System.currentTimeMillis();
            if (now - lastFrameAt < 65) return;
            lastFrameAt = now;
            Image.Plane[] planes = image.getPlanes();
            if (planes.length == 0) return;
            Image.Plane plane = planes[0];
            ByteBuffer buffer = plane.getBuffer();
            int paddedWidth = plane.getRowStride() / plane.getPixelStride();
            padded = Bitmap.createBitmap(paddedWidth, image.getHeight(), Bitmap.Config.ARGB_8888);
            padded.copyPixelsFromBuffer(buffer);
            cropped = Bitmap.createBitmap(padded, 0, 0, image.getWidth(), image.getHeight());
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            cropped.compress(Bitmap.CompressFormat.JPEG, 66, output);
            frameSink.accept(output.toByteArray());
        } catch (Exception ignored) {
        } finally {
            if (cropped != null && cropped != padded) cropped.recycle();
            if (padded != null) padded.recycle();
            if (image != null) image.close();
        }
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        try { if (virtualDisplay != null) virtualDisplay.release(); } catch (Exception ignored) {}
        try { reader.close(); } catch (Exception ignored) {}
        try { projection.stop(); } catch (Exception ignored) {}
        worker.quitSafely();
    }
}
