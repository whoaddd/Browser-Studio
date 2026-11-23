package com.rb.data.session;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class LatestFrameTest {
    @Test void slowViewersReceiveLatestAndDoNotStealFrames() throws Exception {
        LatestFrame frames = new LatestFrame();
        frames.publish(new byte[]{1}); frames.publish(new byte[]{2});
        var a = frames.after(0, 1); var b = frames.after(0, 1);
        assertEquals(2, a.sequence()); assertEquals(a.sequence(), b.sequence());
        assertArrayEquals(new byte[]{2}, a.jpeg());
        assertNull(frames.after(2, 1));
    }
    @Test void dimensionsStayWithTheirFrameAcrossViewportChanges() throws Exception {
        LatestFrame frames = new LatestFrame();
        frames.publish(new byte[]{1}, 1920, 1080);
        var first = frames.after(0, 1);
        frames.publish(new byte[]{2}, 390, 700);
        var next = frames.after(first.sequence(), 1);
        assertEquals(1920, first.width());
        assertEquals(1080, first.height());
        assertEquals(390, next.width());
        assertEquals(700, next.height());
    }
    @Test void waitingViewerWakesOnNewFrame() throws Exception {
        LatestFrame frames = new LatestFrame();
        var result = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try { return frames.after(0, 2000); } catch (InterruptedException e) { throw new RuntimeException(e); }
        });
        frames.publish(new byte[]{3});
        assertArrayEquals(new byte[]{3}, result.get(2, java.util.concurrent.TimeUnit.SECONDS).jpeg());
    }
}
