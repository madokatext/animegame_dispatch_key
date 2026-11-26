package local.dispatch.observer;

import android.net.Uri;
import android.os.Process;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.AfterHookCallback;
import io.github.libxposed.api.XposedInterface.BeforeHookCallback;
import io.github.libxposed.api.XposedInterface.Hooker;
import io.github.libxposed.api.XposedInterface.MethodUnhooker;
import io.github.libxposed.api.XposedModule;

public final class DispatchHook extends XposedModule {
    private static final String TARGET = "com.miHoYo.ys.bilibili";
    private static final int MAX_BODY_BYTES = 4 * 1024 * 1024;
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static DispatchHook module;
    private static final String[] QUERY_FIELDS = {
            "version", "lang", "platform", "binary", "time", "channel_id",
            "sub_channel_id", "account_type", "dispatchSeed", "key_id"
    };

    private final Map<Object, Capture> captures = Collections.synchronizedMap(new WeakHashMap<>());
    private File captureDirectory;
    private boolean hooksInstalled;

    public DispatchHook(XposedInterface base, ModuleLoadedParam param) {
        super(base, param);
        module = this;
    }

    @Override
    public synchronized void onPackageLoaded(PackageLoadedParam param) {
        if (!TARGET.equals(param.getPackageName()) || hooksInstalled) {
            return;
        }

        // The framework context belongs to the module. Use the target package's data directory.
        captureDirectory = new File(param.getApplicationInfo().dataDir, "files/dispatch-observer");
        List<MethodUnhooker<?>> installed = new ArrayList<>();
        try {
            Class<?> request = Class.forName(
                    "com.unity3d.player.UnityWebRequest", false, param.getClassLoader());
            Constructor<?> constructor = request.getDeclaredConstructor(
                    long.class, String.class, Map.class, String.class, int.class);
            Method download = request.getDeclaredMethod("downloadCallback", ByteBuffer.class, int.class);
            Method status = request.getDeclaredMethod("responseCodeCallback", int.class);
            Method run = request.getDeclaredMethod("run");

            installed.add(hook(constructor, RequestHooker.class));
            installed.add(hook(download, DownloadHooker.class));
            installed.add(hook(status, StatusHooker.class));
            installed.add(hook(run, CompletionHooker.class));
            hooksInstalled = true;
            log("Dispatch Observer API 100 hooks installed, pid=" + Process.myPid());
        } catch (Throwable error) {
            for (MethodUnhooker<?> hook : installed) {
                try {
                    hook.unhook();
                } catch (Throwable unhookError) {
                    report(unhookError);
                }
            }
            report(error);
        }
    }

    public static final class RequestHooker implements Hooker {
        public static void after(AfterHookCallback callback) {
            if (callback.getThrowable() == null) {
                module.captureRequest(callback.getThisObject(), callback.getArgs());
            }
        }
    }

    public static final class DownloadHooker implements Hooker {
        public static void before(BeforeHookCallback callback) {
            module.captureChunk(callback.getThisObject(), callback.getArgs());
        }
    }

    public static final class StatusHooker implements Hooker {
        public static void before(BeforeHookCallback callback) {
            module.captureStatus(callback.getThisObject(), (int) callback.getArgs()[0]);
        }
    }

    public static final class CompletionHooker implements Hooker {
        public static void after(AfterHookCallback callback) {
            module.finishCapture(callback.getThisObject(), callback.getThrowable() != null);
        }
    }

    private void captureRequest(Object request, Object[] arguments) {
        try {
            for (Object argument : arguments) {
                if (!(argument instanceof String)) {
                    continue;
                }
                Uri uri = Uri.parse((String) argument);
                String path = uri.getPath();
                if ("https".equalsIgnoreCase(uri.getScheme())
                        && ("/query_region_list".equals(path) || "/query_cur_region".equals(path))) {
                    captures.put(request, new Capture(uri));
                    return;
                }
            }
        } catch (Exception error) {
            report(error);
        }
    }

    private void captureChunk(Object request, Object[] arguments) {
        Capture capture = captures.get(request);
        if (capture == null) {
            return;
        }
        synchronized (capture) {
            try {
                int count = (int) arguments[1];
                if (count == 0 || capture.truncated || capture.copyFailure) {
                    return;
                }
                ByteBuffer source = (ByteBuffer) arguments[0];
                if (source == null || count < 0 || count > source.capacity()) {
                    capture.copyFailure = true;
                    return;
                }
                if (count > MAX_BODY_BYTES - capture.body.size()) {
                    capture.truncated = true;
                    return;
                }

                // The inspected Unity callback receives bytes starting at buffer offset 0.
                ByteBuffer view = source.duplicate();
                view.clear();
                view.limit(count);
                byte[] chunk = new byte[count];
                view.get(chunk);
                capture.body.write(chunk, 0, chunk.length);
            } catch (Exception error) {
                capture.copyFailure = true;
                report(error);
            }
        }
    }

    private void captureStatus(Object request, int status) {
        Capture capture = captures.get(request);
        if (capture != null) {
            synchronized (capture) {
                capture.httpStatus = status;
            }
        }
    }

    private void finishCapture(Object request, boolean runHadThrowable) {
        Capture capture = captures.remove(request);
        if (capture == null) {
            return;
        }
        try {
            synchronized (capture) {
                save(capture, runHadThrowable);
            }
        } catch (Exception error) {
            report(error);
        }
    }

    private void save(Capture capture, boolean runHadThrowable) throws Exception {
        if (!captureDirectory.isDirectory() && !captureDirectory.mkdirs()
                && !captureDirectory.isDirectory()) {
            throw new IllegalStateException("Capture directory unavailable");
        }
        String stem = capture.startedAt + "-" + Process.myPid() + "-"
                + SEQUENCE.incrementAndGet() + "-" + capture.endpoint;
        capture.metadata.put("http_status", capture.httpStatus);
        capture.metadata.put("body_bytes", capture.body.size());
        capture.metadata.put("body_truncated", capture.truncated);
        capture.metadata.put("copy_failure", capture.copyFailure);
        capture.metadata.put("run_had_throwable", runHadThrowable);
        capture.metadata.put("finished_at_unix_ms", System.currentTimeMillis());
        write(new File(captureDirectory, stem + ".response"), capture.body.toByteArray());
        write(new File(captureDirectory, stem + ".meta.json"),
                capture.metadata.toString(2).getBytes(StandardCharsets.UTF_8));
        log("Dispatch Observer saved " + stem + ", bytes=" + capture.body.size());
    }

    private static void write(File file, byte[] data) throws Exception {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(data);
        }
    }

    private void report(Throwable error) {
        log("Dispatch Observer error " + error.getClass().getSimpleName());
    }

    private static final class Capture {
        final long startedAt = System.currentTimeMillis();
        final ByteArrayOutputStream body = new ByteArrayOutputStream();
        final JSONObject metadata = new JSONObject();
        final String endpoint;
        int httpStatus;
        boolean truncated;
        boolean copyFailure;

        Capture(Uri uri) throws Exception {
            endpoint = uri.getPath().substring(1);
            JSONObject parameters = new JSONObject();
            for (String name : QUERY_FIELDS) {
                String value = uri.getQueryParameter(name);
                if (value != null) {
                    parameters.put(name, value);
                }
            }
            metadata.put("host", uri.getHost());
            metadata.put("endpoint", endpoint);
            metadata.put("request_parameters", parameters);
            metadata.put("started_at_unix_ms", startedAt);
        }
    }
}
