package cn.har01d.atmaintenance.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

final class LimitedHttpBody implements HttpResponse.BodySubscriber<byte[]> {
    private final int limit;
    private final String errorCode;
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private final CompletableFuture<byte[]> result = new CompletableFuture<>();
    private Flow.Subscription subscription;

    LimitedHttpBody(int limit) { this(limit, "AT_RESPONSE_TOO_LARGE"); }
    LimitedHttpBody(int limit, String errorCode) {
        this.limit = limit;
        this.errorCode = errorCode;
    }
    public CompletionStage<byte[]> getBody() { return result; }
    public void onSubscribe(Flow.Subscription subscription) {
        this.subscription = subscription;
        subscription.request(1);
    }
    public void onNext(List<ByteBuffer> buffers) {
        for (ByteBuffer buffer : buffers) {
            if (buffer.remaining() > limit - output.size()) {
                subscription.cancel();
                result.completeExceptionally(new IOException(errorCode));
                return;
            }
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            output.writeBytes(bytes);
        }
        subscription.request(1);
    }
    public void onError(Throwable error) { result.completeExceptionally(error); }
    public void onComplete() { result.complete(output.toByteArray()); }
}
