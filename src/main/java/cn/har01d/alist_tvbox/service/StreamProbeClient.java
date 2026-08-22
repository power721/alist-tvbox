package cn.har01d.alist_tvbox.service;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/**
 * 字节级流探测客户端:对取链得到的直链发小段 Range 请求,验证 CDN 真出流。
 * <p>
 * 解析级验证(AList getFile)只证明"取得到链接",不证明"播得动" —— 和谐资源的常见形态正是
 * 目录在、解析过、拉流 403/HTML。接口抽象仅为单测可注入桩,生产用 {@link Default}。
 */
public interface StreamProbeClient {

    /**
     * @return 探测结果(状态码/Content-Type/截断的响应体);网络异常直接上抛,由调用方分类
     */
    ProbeResult fetch(String url, String userAgent, int maxBytes, int timeoutSeconds) throws Exception;

    record ProbeResult(int status, String contentType, byte[] body) {
    }

    /** OkHttp 实现:Range 定长读取(服务端无视 Range 时也不吞整个文件),不跟随重定向。 */
    class Default implements StreamProbeClient {
        private final OkHttpClient client = new OkHttpClient();

        @Override
        public ProbeResult fetch(String url, String userAgent, int maxBytes, int timeoutSeconds) throws Exception {
            Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", userAgent)
                    .header("Range", "bytes=0-" + Math.max(0, maxBytes - 1))
                    .build();
            OkHttpClient timed = client.newBuilder()
                    .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
                    .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
                    .callTimeout(timeoutSeconds, TimeUnit.SECONDS)
                    .followRedirects(false)
                    .build();
            try (Response response = timed.newCall(request).execute()) {
                return new ProbeResult(response.code(), response.header("Content-Type", ""), readCapped(response, maxBytes));
            }
        }

        /** 定长读取:非 Range 感知的服务端会回全量响应,必须客户端截断。 */
        private static byte[] readCapped(Response response, int maxBytes) throws IOException {
            if (response.body() == null) {
                return new byte[0];
            }
            InputStream in = response.body().byteStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int remaining = Math.max(0, maxBytes);
            int read;
            while (remaining > 0 && (read = in.read(buffer, 0, Math.min(buffer.length, remaining))) != -1) {
                out.write(buffer, 0, read);
                remaining -= read;
            }
            return out.toByteArray();
        }
    }
}
