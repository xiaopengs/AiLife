package com.ailife.track;

/** Runtime status snapshot (contracts/api.md TrackStatus). */
public class TrackStatus {
    public enum State { IDLE, CONNECTING, CONNECTED, DEGRADED, SUSPENDED }

    public State state = State.IDLE;
    public long pendingCount = 0;
    public long pendingBytes = 0;
    public double health = 1.0;
    /** 0 normal, 1 ramping (1/4 or 1/2), 2 cache-only. */
    public int degradeLevel = 0;
    public long sentCount = 0;
    public long droppedCount = 0;
    public String channel = "provider";

    @Override
    public String toString() {
        return "TrackStatus{state=" + state + ", pending=" + pendingCount
                + " (" + pendingBytes + "B), health=" + health
                + ", degrade=" + degradeLevel + ", sent=" + sentCount
                + ", dropped=" + droppedCount + ", channel=" + channel + '}';
    }
}
