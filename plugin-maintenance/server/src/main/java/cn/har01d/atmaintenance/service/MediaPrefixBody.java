package cn.har01d.atmaintenance.service;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** Cancels upstream delivery at the retained-body cap, including servers ignoring Range. */
final class MediaPrefixBody implements HttpResponse.BodySubscriber<byte[]> {
    private final int limit;
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private final CompletableFuture<byte[]> result = new CompletableFuture<>();
    private Flow.Subscription subscription;
    private boolean truncated;

    MediaPrefixBody(int limit) { this.limit = limit; }
    boolean truncated() { return truncated; }
    public CompletionStage<byte[]> getBody() { return result; }
    public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
    public void onNext(List<ByteBuffer> buffers) {
        for (ByteBuffer buffer : buffers) {
            int count = Math.min(buffer.remaining(), limit - output.size());
            byte[] bytes = new byte[count];
            buffer.get(bytes);
            output.writeBytes(bytes);
            if (output.size() == limit) {
                truncated = true;
                subscription.cancel();
                result.complete(output.toByteArray());
                return;
            }
        }
        subscription.request(1);
    }
    public void onError(Throwable error) { result.completeExceptionally(error); }
    public void onComplete() { result.complete(output.toByteArray()); }
}
