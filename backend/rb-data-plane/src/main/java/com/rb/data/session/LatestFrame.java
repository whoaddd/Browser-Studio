package com.rb.data.session;

/** Latest-only broadcast: a slow viewer never queues frames or steals another viewer's frames. */
public final class LatestFrame {
    public record Frame(long sequence, byte[] jpeg, int width, int height) {}
    private Frame latest;
    public synchronized void publish(byte[] jpeg) {
        publish(jpeg, 1280, 720);
    }
    public synchronized void publish(byte[] jpeg, int width, int height) {
        latest = new Frame(latest == null ? 1 : latest.sequence() + 1, jpeg, width, height);
        notifyAll();
    }
    public synchronized Frame after(long sequence, long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000;
        while (latest == null || latest.sequence() <= sequence) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) return null;
            java.util.concurrent.TimeUnit.NANOSECONDS.timedWait(this, remaining);
        }
        return latest;
    }
}
