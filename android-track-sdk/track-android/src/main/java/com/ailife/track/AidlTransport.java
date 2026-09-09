package com.ailife.track;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.DeadObjectException;
import android.os.IBinder;
import android.os.RemoteException;

import com.ailife.track.aidl.ITrackCallback;
import com.ailife.track.aidl.ITrackService;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/** AIDL alternative to ProviderTransport. */
public final class AidlTransport implements Transport {
    static final long SEND_TIMEOUT_MS = 8000L;
    private static final long BIND_RETRY_MS = 2000L;

    private final Context context;
    private final ComponentName hubService;
    private final String appKey;
    private final boolean encrypted;
    private final Object lock = new Object();
    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            remote = ITrackService.Stub.asInterface(service);
            binding = false;
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            remote = null;
            binding = false;
        }

        @Override
        public void onBindingDied(ComponentName name) {
            remote = null;
            binding = false;
        }

        @Override
        public void onNullBinding(ComponentName name) {
            remote = null;
            binding = false;
        }
    };

    private volatile ITrackService remote;
    private volatile boolean binding;
    private long lastBindAttemptAt;

    /** Retained for source compatibility; SDK configuration defaults to encryption. */
    public AidlTransport(Context context, String hubPackage, String hubServiceClass,
                         String appKey) {
        this(context, hubPackage, hubServiceClass, appKey, true);
    }

    public AidlTransport(Context context, String hubPackage, String hubServiceClass,
                         String appKey, boolean encrypted) {
        this.context = context.getApplicationContext();
        this.hubService = new ComponentName(hubPackage, hubServiceClass);
        this.appKey = appKey;
        this.encrypted = encrypted;
    }

    private boolean ensureBound() {
        if (remote != null) {
            return true;
        }
        long now = System.currentTimeMillis();
        synchronized (lock) {
            if (remote != null) {
                return true;
            }
            if (binding || now - lastBindAttemptAt < BIND_RETRY_MS) {
                return false;
            }
            binding = true;
            lastBindAttemptAt = now;
        }
        try {
            Intent intent = new Intent(ITrackService.class.getName());
            intent.setComponent(hubService);
            if (!context.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
                binding = false;
                return false;
            }
            return remote != null;
        } catch (SecurityException e) {
            binding = false;
            return false;
        }
    }

    @Override
    public Result send(final String batchId, final byte[] gzipBatch,
                       final String signature, final long ts) {
        if (!ensureBound()) {
            return new Result(Code.DEAD_OBJECT, "hub service not bound");
        }
        final BlockingQueue<CallbackResult> latch =
                new ArrayBlockingQueue<CallbackResult>(1);
        try {
            remote.sendBatch(batchId, InboundBatchDecoder.PROTOCOL_VERSION, appKey, encrypted,
                    gzipBatch, signature, ts, new ITrackCallback.Stub() {
                        @Override
                        public void onResult(int code, String detail) {
                            latch.offer(new CallbackResult(code, detail));
                        }

                        @Override
                        public void onQueryResult(String[] rows) {
                            // Not used by the send path.
                        }
                    });
        } catch (DeadObjectException e) {
            remote = null;
            return new Result(Code.DEAD_OBJECT, "hub died during send");
        } catch (RemoteException e) {
            remote = null;
            return new Result(Code.DEAD_OBJECT, e.getMessage());
        }
        try {
            CallbackResult result = latch.poll(SEND_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (result == null) {
                return new Result(Code.TIMEOUT, "no callback within 8s");
            }
            Code code = Code.fromWire(result.code);
            return code == null || result.code > Code.RESULT_INVALID.wire
                    ? new Result(Code.DEAD_OBJECT, "unknown code " + result.code)
                    : new Result(code, result.detail);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(Code.DEAD_OBJECT, "interrupted");
        }
    }

    /** Status probe; null means "unknown" (hub absent). */
    @Override
    public HubStatus getHubStatus() {
        try {
            if (!ensureBound()) {
                return null;
            }
            int[] values = remote.getStatus();
            if (values == null || values.length < 4) {
                return null;
            }
            return new HubStatus(values[2] / 100.0, values[3]);
        } catch (RemoteException e) {
            remote = null;
            return null;
        }
    }

    void unbindForTest() {
        try {
            context.unbindService(connection);
        } catch (IllegalArgumentException ignore) {
            // Never bound (or was already unbound).
        }
    }

    private static final class CallbackResult {
        final int code;
        final String detail;

        CallbackResult(int code, String detail) {
            this.code = code;
            this.detail = detail;
        }
    }
}
