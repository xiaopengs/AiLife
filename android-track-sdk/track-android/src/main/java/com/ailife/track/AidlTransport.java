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

/**
 * AIDL transport (alternative to ProviderTransport; chosen via
 * TrackConfig.ChannelMode.AIDL). Binds the hub ITrackService with
 * BIND_AUTO_CREATE, sends batches one-way and awaits the async result on a
 * one-shot latch with an 8s budget; DEAD_OBJECT handling identical to the
 * provider channel. Never crashes on bind failures; ChannelCore backoff
 * governs rebind attempts.
 */
public final class AidlTransport implements Transport {
    static final long SEND_TIMEOUT_MS = 8000L;

    private final Context context;
    private final ComponentName hubService;
    private final String appKey;
    private final Object LOCK = new Object();

    private volatile ITrackService remote;
    private volatile boolean binding;
    private long lastBindAttemptAt = 0;
    private static final long BIND_RETRY_MS = 2000L;

    public AidlTransport(Context context, String hubPackage, String hubServiceClass,
                         String appKey) {
        this.context = context.getApplicationContext();
        this.hubService = new ComponentName(hubPackage, hubServiceClass);
        this.appKey = appKey;
    }

    private boolean ensureBound() {
        if (remote != null) {
            return true;
        }
        long now = System.currentTimeMillis();
        synchronized (LOCK) {
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
            Intent it = new Intent(ITrackService.class.getName());
            it.setComponent(hubService);
            boolean ok = context.bindService(it, new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder service) {
                    remote = ITrackService.Stub.asInterface(service);
                    binding = false;
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    // hub process gone: binder proxy invalidated, backoff governs rebind
                    remote = null;
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
            }, Context.BIND_AUTO_CREATE);
            if (!ok) {
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
        final BlockingQueue<int[]> latch = new ArrayBlockingQueue<int[]>(1);
        try {
            remote.sendBatch(batchId, gzipBatch, signature, ts, new ITrackCallback.Stub() {
                @Override
                public void onResult(int code, String detail) {
                    latch.offer(new int[] {code});
                }

                @Override
                public void onQueryResult(String[] rows) {
                    // not used by send path
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
            int[] r = latch.poll(SEND_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (r == null) {
                return new Result(Code.TIMEOUT, "no callback within 8s");
            }
            Code c = Code.fromWire(r[0]);
            return c == null
                    ? new Result(Code.RESULT_INVALID, "unknown code " + r[0])
                    : new Result(c, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(Code.DEAD_OBJECT, "interrupted");
        }
    }

    /** Status probe; null fields mean "unknown" (hub absent). */
    public int[] getStatus() {
        try {
            if (!ensureBound()) {
                return null;
            }
            return remote.getStatus();
        } catch (RemoteException e) {
            remote = null;
            return null;
        }
    }

    void unbindForTest() {
        try {
            context.unbindService(null);
        } catch (Exception ignore) {
            // test-only best effort
        }
    }
}
