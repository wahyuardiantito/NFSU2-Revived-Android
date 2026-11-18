package io.mininxd.nfsu2;

import android.content.Context;
import android.hardware.input.InputManager;
import android.os.Build;
import android.os.Environment;
import android.os.Process;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Dedicated persistent input & control recorder for NFSU2 Android.
 * Records connected input devices, multi-touch gestures, gamepad analog sticks & buttons,
 * and camera control telemetry.
 *
 * Log locations:
 * - Primary app file: /Android/data/io.mininxd.nfsu2/files/input_log.txt
 * - Internal fallback: /data/data/io.mininxd.nfsu2/files/input_log.txt
 * - SDCard: /sdcard/NFSU2/input_log.txt & /sdcard/nfsu2/input_log.txt
 */
public final class InputLog {
    private static final String TAG = "NFSU2-InputLog";
    private static final int MAX_MEMORY_LINES = 2000;
    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024; // 5 MB

    private static volatile Context appContext = null;
    private static final Deque<String> memoryBuffer = new ArrayDeque<>(MAX_MEMORY_LINES);
    private static final ExecutorService writerExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "InputLogWriter");
        t.setDaemon(true);
        return t;
    });

    private static final SimpleDateFormat DATE_FORMAT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);

    // Rate limiting caches for continuous events
    private static final ConcurrentHashMap<Integer, Long> lastTouchLogTimes = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, Long> lastAxisLogTimes = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, Boolean> lastAxisWasActive = new ConcurrentHashMap<>();

    public static synchronized void init(Context context) {
        if (context == null) return;
        appContext = context.getApplicationContext();

        String initLine = String.format(Locale.US,
                "=== Input Logger Initialized (PID %d, Device: %s %s, Android %s API %d) ===",
                Process.myPid(), Build.MANUFACTURER, Build.MODEL, Build.VERSION.RELEASE, Build.VERSION.SDK_INT);
        log("System", initLine);

        // Immediate write to ensure file exists and is not blank
        final Context ctx = appContext;
        writerExecutor.execute(() -> {
            logConnectedDevices(ctx);
        });

        registerDeviceListener(ctx);
    }

    public static void i(String tag, String msg) { log("INFO", tag, msg); }
    public static void w(String tag, String msg) { log("WARN", tag, msg); }
    public static void e(String tag, String msg) { log("ERROR", tag, msg); }

    public static void log(String level, String tag, String msg) {
        String timestamp = DATE_FORMAT.format(new Date());
        String line = String.format(Locale.US, "[%s] [%s/%s]: %s", timestamp, level, tag, msg);

        synchronized (memoryBuffer) {
            if (memoryBuffer.size() >= MAX_MEMORY_LINES) {
                memoryBuffer.pollFirst();
            }
            memoryBuffer.addLast(line);
        }

        Log.i(TAG, "[" + level + "/" + tag + "] " + msg);

        final Context ctx = appContext;
        writerExecutor.execute(() -> writeToFile(ctx, line));
    }

    private static void log(String tag, String msg) {
        log("INFO", tag, msg);
    }

    // -------------------------------------------------------------------------
    // Device Enumeration & Hotplug Listener
    // -------------------------------------------------------------------------

    private static void logConnectedDevices(Context ctx) {
        if (ctx == null) return;
        try {
            InputManager im = (InputManager) ctx.getSystemService(Context.INPUT_SERVICE);
            if (im == null) return;
            int[] ids = im.getInputDeviceIds();
            log("DeviceScan", String.format(Locale.US, "Found %d input devices connected", ids != null ? ids.length : 0));
            if (ids != null) {
                for (int id : ids) {
                    InputDevice dev = im.getInputDevice(id);
                    if (dev != null) {
                        logDeviceInfo("CONNECTED", dev);
                    }
                }
            }
        } catch (Throwable t) {
            log("DeviceScan", "Error scanning devices: " + t.getMessage());
        }
    }

    private static void registerDeviceListener(Context ctx) {
        if (ctx == null) return;
        try {
            InputManager im = (InputManager) ctx.getSystemService(Context.INPUT_SERVICE);
            if (im != null) {
                im.registerInputDeviceListener(new InputManager.InputDeviceListener() {
                    @Override
                    public void onInputDeviceAdded(int deviceId) {
                        try {
                            InputDevice dev = InputDevice.getDevice(deviceId);
                            if (dev != null) logDeviceInfo("ADDED", dev);
                        } catch (Throwable ignored) {}
                    }

                    @Override
                    public void onInputDeviceRemoved(int deviceId) {
                        log("Hotplug", String.format(Locale.US, "[DEVICE_REMOVED] id=%d", deviceId));
                    }

                    @Override
                    public void onInputDeviceChanged(int deviceId) {
                        try {
                            InputDevice dev = InputDevice.getDevice(deviceId);
                            if (dev != null) logDeviceInfo("CHANGED", dev);
                        } catch (Throwable ignored) {}
                    }
                }, null);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to register InputDeviceListener: " + t.getMessage());
        }
    }

    private static void logDeviceInfo(String eventType, InputDevice dev) {
        int sources = dev.getSources();
        List<String> srcNames = new ArrayList<>();
        if ((sources & InputDevice.SOURCE_TOUCHSCREEN) == InputDevice.SOURCE_TOUCHSCREEN) srcNames.add("Touchscreen");
        if ((sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) srcNames.add("Joystick");
        if ((sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD) srcNames.add("Gamepad");
        if ((sources & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD) srcNames.add("DPad");
        if ((sources & InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD) srcNames.add("Keyboard");
        if ((sources & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE) srcNames.add("Mouse");

        int vendorId = 0, productId = 0;
        if (Build.VERSION.SDK_INT >= 19) {
            vendorId = dev.getVendorId();
            productId = dev.getProductId();
        }

        String desc = dev.getDescriptor();
        if (desc == null) desc = "none";
        else if (desc.length() > 24) desc = desc.substring(0, 24) + "...";

        String msg = String.format(Locale.US,
                "[DEVICE_%s] id=%d name=\"%s\" isExternal=%b vendor=0x%04x product=0x%04x sources=[%s] desc=%s",
                eventType, dev.getId(), dev.getName(), dev.isExternal(), vendorId, productId,
                android.text.TextUtils.join("|", srcNames), desc);
        log("InputDevice", msg);
        try {
            List<InputDevice.MotionRange> ranges = dev.getMotionRanges();
            if (ranges != null) {
                for (InputDevice.MotionRange range : ranges) {
                    log("InputDevice", String.format(Locale.US,
                        "  Axis %d (%s): min=%.2f max=%.2f flat=%.2f fuzz=%.2f",
                        range.getAxis(), MotionEvent.axisToString(range.getAxis()),
                        range.getMin(), range.getMax(), range.getFlat(), range.getFuzz()));
                }
            }
        } catch (Throwable ignored) {}
    }

    // -------------------------------------------------------------------------
    // Touch Input Logging
    // -------------------------------------------------------------------------

    public static void touch(int pointerId, int action, float normX, float normY, String target) {
        String actionStr;
        boolean rateLimit = false;
        switch (action) {
            case MotionEvent.ACTION_DOWN: actionStr = "DOWN"; break;
            case MotionEvent.ACTION_UP: actionStr = "UP"; break;
            case MotionEvent.ACTION_MOVE: actionStr = "MOVE"; rateLimit = true; break;
            case MotionEvent.ACTION_CANCEL: actionStr = "CANCEL"; break;
            case MotionEvent.ACTION_POINTER_DOWN: actionStr = "PTR_DOWN"; break;
            case MotionEvent.ACTION_POINTER_UP: actionStr = "PTR_UP"; break;
            default: actionStr = "ACTION_" + action; break;
        }

        if (rateLimit) {
            long now = System.currentTimeMillis();
            Long lastTime = lastTouchLogTimes.get(pointerId);
            if (lastTime != null && (now - lastTime) < 80) { // limit move events to ~12 fps per pointer
                return;
            }
            lastTouchLogTimes.put(pointerId, now);
        } else {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_POINTER_UP) {
                lastTouchLogTimes.remove(pointerId);
            }
        }

        String msg = String.format(Locale.US,
                "[TOUCH] ptr=%d action=%-8s pos=(%.3f, %.3f) target=%s",
                pointerId, actionStr, normX, normY, target != null ? target : "none");
        log("Touch", msg);
    }

    // -------------------------------------------------------------------------
    // Gamepad Motion & Axis Logging
    // -------------------------------------------------------------------------

    public static void onGenericMotionEvent(MotionEvent event) {
        if (event == null) return;
        int source = event.getSource();
        if ((source & InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK &&
            (source & InputDevice.SOURCE_GAMEPAD) != InputDevice.SOURCE_GAMEPAD) {
            return;
        }

        int devId = event.getDeviceId();
        float lx = event.getAxisValue(MotionEvent.AXIS_X);
        float ly = event.getAxisValue(MotionEvent.AXIS_Y);

        float rx_z = event.getAxisValue(MotionEvent.AXIS_Z);
        float rx_rot = event.getAxisValue(MotionEvent.AXIS_RX);
        float rx = Math.abs(rx_rot) >= Math.abs(rx_z) ? rx_rot : rx_z;

        float ry_z = event.getAxisValue(MotionEvent.AXIS_RZ);
        float ry_rot = event.getAxisValue(MotionEvent.AXIS_RY);
        float ry = Math.abs(ry_rot) >= Math.abs(ry_z) ? ry_rot : ry_z;

        float hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X);
        float hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y);

        float lt = Math.max(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE));
        float rt = Math.max(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS));

        boolean isActive = Math.abs(lx) > 0.10f || Math.abs(ly) > 0.10f ||
                           Math.abs(rx) > 0.10f || Math.abs(ry) > 0.10f ||
                           Math.abs(hatX) > 0.10f || Math.abs(hatY) > 0.10f ||
                           lt > 0.10f || rt > 0.10f;

        Boolean wasActive = lastAxisWasActive.get(devId);
        if (wasActive == null) wasActive = Boolean.FALSE;

        long now = System.currentTimeMillis();
        Long lastTime = lastAxisLogTimes.get(devId);

        if (!isActive) {
            if (wasActive) {
                // Stick/Trigger returned to deadzone center: log the release
                lastAxisWasActive.put(devId, Boolean.FALSE);
                InputDevice dev = event.getDevice();
                String devName = dev != null ? dev.getName() : "Gamepad";
                log("Gamepad", String.format(Locale.US,
                        "[GAMEPAD_AXIS] dev=%d [%s] CENTER/RELEASED L=(0.00, 0.00) R=(0.00, 0.00)",
                        devId, devName));
            }
            return;
        }

        // Active stick movement: rate-limit to ~60ms
        if (lastTime != null && (now - lastTime) < 60) {
            return;
        }

        lastAxisLogTimes.put(devId, now);
        lastAxisWasActive.put(devId, Boolean.TRUE);

        InputDevice dev = event.getDevice();
        String devName = dev != null ? dev.getName() : "Gamepad";

        String msg = String.format(Locale.US,
                "[GAMEPAD_AXIS] dev=%d [%s] L=(%.2f, %.2f) R=(%.2f, %.2f) Hat=(%.0f, %.0f) Triggers=(L:%.2f, R:%.2f)",
                devId, devName, lx, ly, rx, ry, hatX, hatY, lt, rt);
        log("Gamepad", msg);
    }

    /**
     * Native input hook called from C++/NDK onInput when gamepad axes change.
     */
    public static void logNativeGamepadAxis(int devId, float lx, float ly, float rx, float ry, float hatX, float hatY, float lt, float rt) {
        boolean isActive = Math.abs(lx) > 0.10f || Math.abs(ly) > 0.10f ||
                           Math.abs(rx) > 0.10f || Math.abs(ry) > 0.10f ||
                           Math.abs(hatX) > 0.10f || Math.abs(hatY) > 0.10f ||
                           lt > 0.10f || rt > 0.10f;

        Boolean wasActive = lastAxisWasActive.get(devId);
        if (wasActive == null) wasActive = Boolean.FALSE;

        long now = System.currentTimeMillis();
        Long lastTime = lastAxisLogTimes.get(devId);

        if (!isActive) {
            if (wasActive) {
                lastAxisWasActive.put(devId, Boolean.FALSE);
                String devName = getDeviceName(devId);
                log("Gamepad", String.format(Locale.US,
                        "[GAMEPAD_AXIS] dev=%d [%s] CENTER/RELEASED L=(0.00, 0.00) R=(0.00, 0.00)",
                        devId, devName));
            }
            return;
        }

        if (lastTime != null && (now - lastTime) < 60) {
            return;
        }

        lastAxisLogTimes.put(devId, now);
        lastAxisWasActive.put(devId, Boolean.TRUE);

        String devName = getDeviceName(devId);
        String msg = String.format(Locale.US,
                "[GAMEPAD_AXIS] dev=%d [%s] L=(%.2f, %.2f) R=(%.2f, %.2f) Hat=(%.0f, %.0f) Triggers=(L:%.2f, R:%.2f)",
                devId, devName, lx, ly, rx, ry, hatX, hatY, lt, rt);
        log("Gamepad", msg);
    }

    /**
     * Native input hook called from C++/NDK onInput when gamepad buttons or keys change.
     */
    public static void logNativeGamepadKey(int devId, int action, int keyCode, int scanCode, int repeat) {
        if (action == 0 && repeat > 0) return; // ignore repeated down

        String devName = getDeviceName(devId);
        String keyName = KeyEvent.keyCodeToString(keyCode);
        String actionStr = (action == 0) ? "DOWN" : "UP";

        String msg = String.format(Locale.US,
                "[GAMEPAD_BTN] dev=%d [%s] action=%-4s key=%s (%d) scan=%d repeat=%d",
                devId, devName, actionStr, keyName, keyCode, scanCode, repeat);
        log("Gamepad", msg);
    }

    private static String getDeviceName(int devId) {
        try {
            InputDevice dev = InputDevice.getDevice(devId);
            if (dev != null) return dev.getName();
        } catch (Throwable ignored) {}
        return "Gamepad";
    }

    // -------------------------------------------------------------------------
    // Gamepad & Key Button Logging
    // -------------------------------------------------------------------------

    public static void onKeyEvent(KeyEvent event) {
        if (event == null) return;
        int action = event.getAction();
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP) return;

        int keyCode = event.getKeyCode();
        int scanCode = event.getScanCode();
        int repeat = event.getRepeatCount();
        int devId = event.getDeviceId();

        // Avoid logging repeated DOWN events beyond the first
        if (action == KeyEvent.ACTION_DOWN && repeat > 0) return;

        InputDevice dev = event.getDevice();
        String devName = dev != null ? dev.getName() : "Keyboard/Pad";
        String keyName = KeyEvent.keyCodeToString(keyCode);
        String actionStr = (action == KeyEvent.ACTION_DOWN) ? "DOWN" : "UP";

        String msg = String.format(Locale.US,
                "[GAMEPAD_BTN] dev=%d [%s] action=%-4s key=%s (%d) scan=%d repeat=%d",
                devId, devName, actionStr, keyName, keyCode, scanCode, repeat);
        log("Gamepad", msg);
    }

    // -------------------------------------------------------------------------
    // Camera Telemetry Logging
    // -------------------------------------------------------------------------

    public static void cameraLook(String source, float stickX, float stickY, float yaw, float pitch) {
        String msg = String.format(Locale.US,
                "[CAMERA_LOOK] src=%s stick=(%.2f, %.2f) yaw=%.2f pitch=%.2f",
                source, stickX, stickY, yaw, pitch);
        log("Camera", msg);
    }

    // -------------------------------------------------------------------------
    // File I/O & Diagnostics (Matching GameLog pattern)
    // -------------------------------------------------------------------------

    private static void writeToFile(Context ctx, String entry) {
        try {
            java.util.LinkedHashSet<String> writtenCanonicalPaths = new java.util.LinkedHashSet<>();

            // 1. Primary app file
            File logFile = getPrimaryLogFile(ctx);
            writeToSingleFile(logFile, entry, writtenCanonicalPaths);

            // 2. Active game root directory (e.g. /sdcard/nfsu2)
            try {
                File gameDir = LauncherActivity.gameRoot();
                if (gameDir != null && (gameDir.isDirectory() || gameDir.mkdirs())) {
                    writeToSingleFile(new File(gameDir, "input_log.txt"), entry, writtenCanonicalPaths);
                }
            } catch (Throwable ignored) {}

            // 3. Fallback sdcard folders
            for (String folder : new String[]{"NFSU2", "nfsu2"}) {
                try {
                    File sdcardDir = new File(Environment.getExternalStorageDirectory(), folder);
                    if (sdcardDir.isDirectory() || sdcardDir.mkdirs()) {
                        writeToSingleFile(new File(sdcardDir, "input_log.txt"), entry, writtenCanonicalPaths);
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            Log.d(TAG, "writeToFile failed: " + t.getMessage());
        }
    }

    private static void writeToSingleFile(File targetFile, String entry, java.util.Set<String> writtenPaths) {
        if (targetFile == null) return;
        try {
            File parent = targetFile.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();

            String canonPath;
            try {
                canonPath = targetFile.getCanonicalPath();
            } catch (Throwable t) {
                canonPath = targetFile.getAbsolutePath().toLowerCase(Locale.US);
            }

            if (!writtenPaths.add(canonPath)) {
                return; // already written to this physical file
            }

            if (targetFile.exists() && targetFile.length() > MAX_FILE_SIZE) {
                File oldFile = new File(targetFile.getAbsolutePath() + ".old");
                if (oldFile.exists()) oldFile.delete();
                targetFile.renameTo(oldFile);
            }

            try (FileWriter writer = new FileWriter(targetFile, true)) {
                writer.write(entry);
                writer.write("\n");
                writer.flush();
            }
        } catch (Throwable ignored) {}
    }

    public static File getPrimaryLogFile(Context ctx) {
        if (ctx == null) return null;
        try {
            File ext = ctx.getExternalFilesDir(null);
            if (ext != null) {
                return new File(ext, "input_log.txt");
            }
        } catch (Throwable ignored) {}
        return new File(ctx.getFilesDir(), "input_log.txt");
    }

    public static String getFullLogText(Context ctx) {
        File file = getPrimaryLogFile(ctx);
        if (file == null || !file.exists()) {
            try {
                File gameDir = LauncherActivity.gameRoot();
                if (gameDir != null) {
                    File alt = new File(gameDir, "input_log.txt");
                    if (alt.exists()) file = alt;
                }
            } catch (Throwable ignored) {}
        }
        if (file == null || !file.exists()) {
            for (String folder : new String[]{"NFSU2", "nfsu2"}) {
                File alt = new File(new File(Environment.getExternalStorageDirectory(), folder), "input_log.txt");
                if (alt.exists()) { file = alt; break; }
            }
        }
        if (file == null || !file.exists()) {
            StringBuilder sb = new StringBuilder();
            synchronized (memoryBuffer) {
                for (String s : memoryBuffer) {
                    sb.append(s).append("\n");
                }
            }
            return sb.toString();
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            return sb.toString();
        } catch (Throwable t) {
            return "Failed reading input log: " + t.getMessage();
        }
    }

    public static byte[] getLogBytes(Context ctx) {
        String full = getFullLogText(ctx);
        return full != null ? full.getBytes(StandardCharsets.UTF_8) : new byte[0];
    }

    public static void clear(Context ctx) {
        synchronized (memoryBuffer) {
            memoryBuffer.clear();
        }
        File file = getPrimaryLogFile(ctx);
        if (file != null && file.exists()) {
            file.delete();
        }
        try {
            File gameDir = LauncherActivity.gameRoot();
            if (gameDir != null) {
                File iLog = new File(gameDir, "input_log.txt");
                if (iLog.exists()) iLog.delete();
            }
        } catch (Throwable ignored) {}
        for (String folder : new String[]{"NFSU2", "nfsu2"}) {
            try {
                File sdcardDir = new File(Environment.getExternalStorageDirectory(), folder);
                File iLog = new File(sdcardDir, "input_log.txt");
                if (iLog.exists()) iLog.delete();
            } catch (Throwable ignored) {}
        }
        i("System", "=== Input Log Cleared ===");
    }
}
