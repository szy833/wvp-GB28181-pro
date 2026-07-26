package com.genersoft.iot.vmp.service.bean;

import com.genersoft.iot.vmp.media.event.hook.HookData;
import com.genersoft.iot.vmp.media.event.hook.HookSubscribe;
import com.genersoft.iot.vmp.media.event.hook.HookSubscriptionHandle;
import com.genersoft.iot.vmp.conf.DynamicTask;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ScheduledFuture;

/**
 * Owns the terminal state and cleanup callback for one RTP open attempt.
 */
public class RtpResourceContext {

    private final String resourceId;
    private final String businessStreamId;
    private final String zlmStreamId;
    private final ErrorCallback<HookData> callback;
    private final Runnable legacyCleanup;
    private volatile Runnable successCleanup = () -> {};
    private volatile Runnable terminalCleanup = () -> {};
    private final AtomicReference<RtpResourceState> state =
            new AtomicReference<>(RtpResourceState.REGISTERING);
    private final AtomicReference<HookData> earlyArrival = new AtomicReference<>();
    private final Object lifecycleLock = new Object();
    private String taskKey;
    private ScheduledFuture<?> taskOwner;
    private DynamicTask dynamicTask;
    private HookSubscriptionHandle hookOwner;
    private HookSubscribe subscribe;

    private volatile String authKey;
    private volatile Object authOwner;

    public RtpResourceContext(String resourceId, String businessStreamId, String zlmStreamId,
                              ErrorCallback<HookData> callback, Runnable cleanup) {
        this.resourceId = Objects.requireNonNull(resourceId, "resourceId");
        this.businessStreamId = businessStreamId;
        this.zlmStreamId = zlmStreamId;
        this.callback = callback;
        this.legacyCleanup = cleanup == null ? () -> {} : cleanup;
        this.successCleanup = this.legacyCleanup;
        this.terminalCleanup = this.legacyCleanup;
    }

    public String getResourceId() {
        return resourceId;
    }

    public String getBusinessStreamId() {
        return businessStreamId;
    }

    public String getZlmStreamId() {
        return zlmStreamId;
    }

    public RtpResourceState getState() {
        return state.get();
    }

    public String getAuthKey() {
        return authKey;
    }

    public Object getAuthOwner() {
        return authOwner;
    }

    public synchronized boolean markAuthWritten(String key, Object owner) {
        if (state.get() == RtpResourceState.FAILED || state.get() == RtpResourceState.TIMED_OUT
                || state.get() == RtpResourceState.CLOSED) {
            return false;
        }
        this.authKey = key;
        this.authOwner = owner;
        return true;
    }

    public boolean isAuthWritable() {
        RtpResourceState current = state.get();
        return current == RtpResourceState.REGISTERING || current == RtpResourceState.WAITING_MEDIA
                || current == RtpResourceState.SUCCESS;
    }

    /** Registers a task or immediately stops it if this context already terminated. */
    public boolean registerTask(String taskKey, ScheduledFuture<?> future, DynamicTask dynamicTask) {
        synchronized (lifecycleLock) {
            if (isTerminal()) {
                if (future != null && dynamicTask != null) {
                    dynamicTask.stop(taskKey, future);
                }
                return false;
            }
            this.taskKey = taskKey;
            this.taskOwner = future;
            this.dynamicTask = dynamicTask;
            return true;
        }
    }

    /** Registers a hook or immediately removes it if this context already terminated. */
    public boolean registerHook(HookSubscriptionHandle handle, HookSubscribe subscribe) {
        synchronized (lifecycleLock) {
            if (isTerminal()) {
                if (handle != null && subscribe != null) {
                    subscribe.removeSubscribe(handle);
                }
                return false;
            }
            this.hookOwner = handle;
            this.subscribe = subscribe;
            return true;
        }
    }

    /** Cleans task and hook owners under the same lock used by registration. */
    public void cleanupRegisteredResources() {
        ScheduledFuture<?> taskOwnerSnapshot;
        DynamicTask dynamicTaskSnapshot;
        String taskKeySnapshot;
        HookSubscriptionHandle hookOwnerSnapshot;
        HookSubscribe subscribeSnapshot;
        synchronized (lifecycleLock) {
            taskOwnerSnapshot = taskOwner;
            dynamicTaskSnapshot = dynamicTask;
            taskKeySnapshot = taskKey;
            hookOwnerSnapshot = hookOwner;
            subscribeSnapshot = subscribe;
            taskOwner = null;
            dynamicTask = null;
            hookOwner = null;
            subscribe = null;
        }
        try {
            if (taskOwnerSnapshot != null && dynamicTaskSnapshot != null) {
                dynamicTaskSnapshot.stop(taskKeySnapshot, taskOwnerSnapshot);
            }
        } catch (RuntimeException ignored) {
            // One resource cleanup must not prevent the remaining owners from being released.
        }
        try {
            if (hookOwnerSnapshot != null && subscribeSnapshot != null) {
                subscribeSnapshot.removeSubscribe(hookOwnerSnapshot);
            }
        } catch (RuntimeException ignored) {
            // Hook cleanup is best effort, just like task cleanup.
        }
    }

    private boolean isTerminal() {
        RtpResourceState current = state.get();
        return current == RtpResourceState.SUCCESS || current == RtpResourceState.FAILED
                || current == RtpResourceState.TIMED_OUT || current == RtpResourceState.CLOSED;
    }

    public boolean moveAuthKey(String oldKey, String newKey) {
        synchronized (this) {
            if (!Objects.equals(this.authKey, oldKey) || !isAuthWritable()) {
                return false;
            }
            this.authKey = newKey;
            return true;
        }
    }

    public void setSuccessCleanup(Runnable cleanup) {
        this.successCleanup = cleanup == null ? () -> {} : cleanup;
    }

    public void setTerminalCleanup(Runnable cleanup) {
        this.terminalCleanup = cleanup == null ? legacyCleanup : cleanup;
    }

    public synchronized boolean transitionTo(RtpResourceState target) {
        Objects.requireNonNull(target, "target");
        while (true) {
            RtpResourceState current = state.get();
            if (!isAllowed(current, target)) {
                return false;
            }
            if (state.compareAndSet(current, target)) {
                return true;
            }
        }
    }

    public boolean markWaitingMedia(int port, String zlmStreamId) {
        HookData pending;
        boolean successClaimed = false;
        synchronized (this) {
            if (!state.compareAndSet(RtpResourceState.REGISTERING, RtpResourceState.WAITING_MEDIA)) {
                return false;
            }
            pending = earlyArrival.getAndSet(null);
            if (pending != null) {
                // Claim SUCCESS while holding the same lifecycle lock as timeout
                // and failure transitions; the cached arrival cannot be lost.
                successClaimed = state.compareAndSet(RtpResourceState.WAITING_MEDIA,
                        RtpResourceState.SUCCESS);
            }
        }
        if (successClaimed) {
            runCleanup(successCleanup);
            runCallback(0, "success", pending);
        }
        return true;
    }

    /** Accepts an arrival before or after the remote create call completes. */
    public void onMediaArrival(HookData data) {
        RtpResourceState current = state.get();
        if (current == RtpResourceState.WAITING_MEDIA) {
            completeSuccess(data);
        } else if (current == RtpResourceState.REGISTERING) {
            earlyArrival.compareAndSet(null, data);
            // Close the race with a concurrent state transition.
            if (state.get() == RtpResourceState.WAITING_MEDIA) {
                HookData pending = earlyArrival.getAndSet(null);
                if (pending != null) completeSuccess(pending);
            }
        }
    }

    public boolean completeSuccess(HookData data) {
        synchronized (this) {
            if (!state.compareAndSet(RtpResourceState.WAITING_MEDIA, RtpResourceState.SUCCESS)) {
                return false;
            }
        }
        runCleanup(successCleanup);
        runCallback(0, "success", data);
        return true;
    }

    public boolean completeFailure(int code, String msg, HookData data) {
        return completeFailureState(RtpResourceState.FAILED, code, msg, data);
    }

    public boolean completeDeparture(int code, String msg, HookData data) {
        return completeFailureState(RtpResourceState.FAILED, code, msg, data);
    }

    public boolean completeTimeout(int code, String msg, HookData data) {
        return completeFailureState(RtpResourceState.TIMED_OUT, code, msg, data);
    }

    private boolean completeFailureState(RtpResourceState terminalState, int code, String msg, HookData data) {
        synchronized (this) {
            RtpResourceState current;
            do {
                current = state.get();
                if (current != RtpResourceState.REGISTERING && current != RtpResourceState.WAITING_MEDIA) {
                    return false;
                }
            } while (!state.compareAndSet(current, terminalState));
        }
        runCleanup(terminalCleanup);
        runCallback(code, msg, data);
        return true;
    }

    public synchronized boolean close(String reason) {
        while (true) {
            RtpResourceState current = state.get();
            if (current == RtpResourceState.CLOSED || current == RtpResourceState.FAILED
                    || current == RtpResourceState.TIMED_OUT) {
                return false;
            }
            if (state.compareAndSet(current, RtpResourceState.CLOSED)) {
                runCleanup(terminalCleanup);
                return true;
            }
        }
    }

    private void runCleanup(Runnable cleanup) {
        try {
            cleanup.run();
        } catch (Exception ignored) {
            // Cleanup is best effort; terminal callback must still be delivered.
        }
    }

    private void runCallback(int code, String msg, HookData data) {
        if (callback != null) {
            try {
                callback.run(code, msg, data);
            } catch (RuntimeException ignored) {
                // A caller callback must not break lifecycle cleanup.
            }
        }
    }

    private boolean isAllowed(RtpResourceState current, RtpResourceState target) {
        return switch (current) {
            case REGISTERING -> target == RtpResourceState.WAITING_MEDIA
                    || target == RtpResourceState.FAILED || target == RtpResourceState.CLOSED;
            case WAITING_MEDIA -> target == RtpResourceState.SUCCESS
                    || target == RtpResourceState.FAILED || target == RtpResourceState.TIMED_OUT
                    || target == RtpResourceState.CLOSED;
            case SUCCESS -> target == RtpResourceState.CLOSED;
            case FAILED, TIMED_OUT, CLOSED -> false;
        };
    }
}
