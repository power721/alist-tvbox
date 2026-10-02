package cn.har01d.alist_tvbox.live.util;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/**
 * HTTP FLV 上游连接:阻塞读出完整包(FLV 文件头/完整 tag)。
 * 读超时与连接重置都按「连接终止」返回 null(pure_live 口径:直播 FLV 没有正常 EOF,
 * reset/截断/优雅关闭一律交给会话决定是否换源)。构造失败(非 2xx/建连失败)直接抛 IOException。
 */
public final class HttpFlvTagReader implements FlvSpliceSession.TagReader {
    private static final Logger log = LoggerFactory.getLogger(HttpFlvTagReader.class);
    private final Response response;
    private final InputStream in;
    private final FlvInputFramer framer = new FlvInputFramer();
    private final byte[] chunk = new byte[64 * 1024];
    private volatile boolean closed;

    public HttpFlvTagReader(OkHttpClient client, String url, Map<String, String> headers) throws IOException {
        Request.Builder builder = new Request.Builder().url(url);
        headers.forEach(builder::header);
        Response opened = null;
        try {
            opened = client.newCall(builder.build()).execute();
            if (!opened.isSuccessful() || opened.body() == null) {
                throw new IOException("FLV upstream HTTP " + opened.code());
            }
        } catch (IOException e) {
            if (opened != null) {
                opened.close();
            }
            throw e;
        }
        this.response = opened;
        this.in = opened.body().byteStream();
    }

    @Override
    public byte[] next() {
        if (closed) {
            return null;
        }
        try {
            while (!framer.hasNext()) {
                int read = in.read(chunk);
                if (read < 0) {
                    return null;
                }
                framer.add(chunk, 0, read);
            }
            return framer.next();
        } catch (IOException e) {
            // 读超时/连接重置/流畸形:与优雅关闭同语义,由会话决定换源
            log.debug("flv upstream ended: {} {}", e.toString(), response.request().url());
            return null;
        }
    }

    @Override
    public void close() {
        closed = true;
        response.close();
    }
}
