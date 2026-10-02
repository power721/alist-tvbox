package cn.har01d.alist_tvbox.live.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.util.function.LongSupplier;

/**
 * 向下游持续输出一条 FLV 流,同时在底下替换到期的上游 URL(pure_live 3.2.11 FlvSpliceSession 移植,
 * issue #35:斗鱼匿名原画 URL 带 expire=300,CDN 在签发 300 秒后断开连接)。
 *
 * <p>在租约到期前(默认留 45 秒)解析下一条同线同档 URL 并建连,当前连接继续转发;
 * 切换发生在新连接第一条「旧连接还没播到」的关键帧上:旧流转发到该时间戳为止,新流从该关键帧起接续。
 * 同房两条连接的时间戳属同一条时间线,下游看到的是一条不间断的流;新连接在另一条时间线上时整体平移衔接。
 * 旧流先于新 URL 就绪而断开时,至多跳过一个 GOP。解码器配置变化在切换关键帧前重发。
 */
public final class FlvSpliceSession {
    private static final Logger log = LoggerFactory.getLogger(FlvSpliceSession.class);
    /** 等旧流追到新关键帧的最长时间。 */
    static final long HANDOVER_TIMEOUT_MS = 10_000;
    /** 新连接首条视频时间戳与已播位置相差超过此窗即视为另一条时间线,需要平移。 */
    static final long ALIGNMENT_WINDOW_MS = 60_000;
    /** 在新连接上搜寻关键帧的预算。 */
    private static final long KEYFRAME_SEARCH_TIMEOUT_MS = 15_000;
    /** 提前换链余量:留足建连+关键帧搜寻+旧流排空的时间(pure_live _leaseRefreshLead)。 */
    private static final long RENEW_LEAD_MS = 45_000;

    /** 一个带租约的 FLV 源:refreshAtEpochMs 为建议换链时刻(毫秒),null=无租约(播到断为止)。 */
    public record Lease(String url, Long refreshAtEpochMs) {
    }

    /** 一条上游连接:首个包为 FLV 文件头,其后为完整 tag;null=流终止。 */
    public interface TagReader extends AutoCloseable {
        byte[] next();

        @Override
        void close();
    }

    public interface Opener {
        TagReader open(String url) throws IOException;
    }

    /** 为同一房间/线路/画质解析下一条流地址。 */
    public interface Renewer {
        Lease renew(String currentUrl) throws Exception;
    }

    private final Opener opener;
    private final Renewer renewer;
    private final LongSupplier clockMs;
    private Lease source;
    private TagReader reader;
    private int offset;
    private Long lastVideo;
    private Long lastAudio;
    private byte[] videoConfig;
    private byte[] audioConfig;
    private volatile boolean cancelled;
    private int switches;

    public FlvSpliceSession(Lease initial, Opener opener, Renewer renewer, LongSupplier clockMs) {
        this.source = initial;
        this.opener = opener;
        this.renewer = renewer;
        this.clockMs = clockMs;
    }

    public int getSwitches() {
        return switches;
    }

    public void cancel() {
        cancelled = true;
        dropReader();
    }

    /** 驱动会话直至换链彻底失败或被取消;向 out 写出的每个包都会 flush。 */
    public void run(OutputStream out) throws IOException {
        reader = opener.open(source.url());
        byte[] header = reader.next();
        if (header == null) {
            throw new IOException("empty FLV upstream");
        }
        out.write(header);
        out.flush();
        try {
            while (!cancelled) {
                boolean ended = pumpUntil(source.refreshAtEpochMs(), out);
                if (cancelled) {
                    return;
                }
                if (!handover(ended, out)) {
                    if (ended) {
                        // 换链失败且旧流已断:结束会话,交由播放器自身的恢复逻辑兜底
                        return;
                    }
                    // 保留仍在工作的连接骑到断为止,下一次切断即结束会话
                    source = new Lease(source.url(), null);
                }
            }
        } finally {
            dropReader();
        }
    }

    /** 转发当前连接直至 deadline(null=无期限);返回 true=流终止。 */
    private boolean pumpUntil(Long deadline, OutputStream out) throws IOException {
        while (!cancelled) {
            if (deadline != null && clockMs.getAsLong() >= deadline) {
                return false;
            }
            byte[] tag = reader.next();
            if (tag == null) {
                return true;
            }
            forward(tag, out);
        }
        return false;
    }

    private boolean handover(boolean oldEnded, OutputStream out) throws IOException {
        Lease next;
        TagReader newReader;
        try {
            next = renewer.renew(source.url());
            if (next == null || next.url() == null || next.url().isBlank()) {
                throw new IllegalStateException("no lease resolved");
            }
            newReader = opener.open(next.url());
        } catch (Exception e) {
            log.debug("flv relay renew failed: {}", e.toString());
            return false;
        }
        if (cancelled) {
            newReader.close();
            return false;
        }
        byte[] keyframe = null;
        byte[] newVideoConfig = null;
        byte[] newAudioConfig = null;
        Integer newOffset = null;
        try {
            if (newReader.next() == null) {
                throw new IOException("empty FLV upstream");
            }
            long searchEnd = clockMs.getAsLong() + KEYFRAME_SEARCH_TIMEOUT_MS;
            while (keyframe == null) {
                if (clockMs.getAsLong() > searchEnd) {
                    throw new IOException("no keyframe on the new FLV upstream");
                }
                byte[] tag = newReader.next();
                if (tag == null) {
                    throw new IOException("new FLV upstream ended before a keyframe");
                }
                if (FlvTag.isVideoConfig(tag)) {
                    newVideoConfig = tag;
                    continue;
                }
                if (FlvTag.isAudioConfig(tag)) {
                    newAudioConfig = tag;
                    continue;
                }
                if (FlvTag.type(tag) != FlvTag.VIDEO) {
                    continue;
                }
                int raw = FlvTag.timestamp(tag);
                if (newOffset == null) {
                    newOffset = lastVideo == null || Math.abs(raw - lastVideo) <= ALIGNMENT_WINDOW_MS
                            ? 0 : (int) (lastVideo + 1 - raw);
                }
                if (FlvTag.isKeyframe(tag) && (lastVideo == null || raw + newOffset > lastVideo)) {
                    keyframe = tag;
                }
            }
        } catch (Exception e) {
            log.debug("flv relay new upstream unusable: {}", e.toString());
            newReader.close();
            return false;
        }
        int off = newOffset;
        int switchAt = FlvTag.timestamp(keyframe) + off;

        if (!oldEnded) {
            // 旧流转发到新关键帧为止:音频越过切换点直接丢弃,视频到达即停
            long until = clockMs.getAsLong() + HANDOVER_TIMEOUT_MS;
            while (!cancelled) {
                if (clockMs.getAsLong() >= until) {
                    break;
                }
                byte[] tag = reader.next();
                if (tag == null) {
                    break;
                }
                int type = FlvTag.type(tag);
                int ts = FlvTag.timestamp(tag) + offset;
                if (type == FlvTag.VIDEO && ts >= switchAt) {
                    break;
                }
                if (type == FlvTag.AUDIO && ts >= switchAt) {
                    continue;
                }
                forward(tag, out);
            }
        }
        dropReader();
        if (cancelled) {
            newReader.close();
            return false;
        }

        reader = newReader;
        offset = off;
        source = next;
        switches++;
        if (newVideoConfig != null && (videoConfig == null || !FlvTag.samePayload(newVideoConfig, videoConfig))) {
            forward(FlvTag.withTimestamp(newVideoConfig, switchAt - off), out);
        }
        if (newAudioConfig != null && (audioConfig == null || !FlvTag.samePayload(newAudioConfig, audioConfig))) {
            forward(FlvTag.withTimestamp(newAudioConfig, switchAt - off), out);
        }
        forward(keyframe, out);
        return true;
    }

    private void forward(byte[] tag, OutputStream out) throws IOException {
        int type = FlvTag.type(tag);
        // onMetaData 等脚本标签只在首条连接转发:重发时间戳平移后只会误导下游
        if (type == FlvTag.SCRIPT && switches > 0) {
            return;
        }
        int ts = FlvTag.timestamp(tag) + offset;
        if (FlvTag.isVideoConfig(tag)) {
            videoConfig = tag;
        } else if (FlvTag.isAudioConfig(tag)) {
            audioConfig = tag;
        } else if (type == FlvTag.VIDEO) {
            if (lastVideo != null && ts < lastVideo) {
                return;
            }
            lastVideo = (long) ts;
        } else if (type == FlvTag.AUDIO) {
            if (lastAudio != null && ts <= lastAudio) {
                return;
            }
            lastAudio = (long) ts;
        }
        out.write(offset == 0 ? tag : FlvTag.withTimestamp(tag, ts));
        out.flush();
    }

    private void dropReader() {
        TagReader current = reader;
        reader = null;
        if (current != null) {
            current.close();
        }
    }

    // ---------------------------------------------------------------------------
    // 租约判定(pure_live DouyuSite LivePlayLeaseMetadata/appliesTo 同款)
    // ---------------------------------------------------------------------------

    /** 纯 http(s) 的 .flv 地址且带正 expire 租约参数才需要中继(虎牙签名 FLV 不带 expire,不在其列)。 */
    public static boolean appliesTo(String url) {
        return expireSeconds(url) != null;
    }

    /** expire 租约秒数;非 http(s)/非 .flv/无 expire/非正数一律 null。 */
    public static Integer expireSeconds(String url) {
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
            if (!scheme.equals("http") && !scheme.equals("https")) {
                return null;
            }
            String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase();
            if (!path.endsWith(".flv")) {
                return null;
            }
            String query = uri.getRawQuery();
            if (query == null) {
                return null;
            }
            for (String param : query.split("&")) {
                if (param.startsWith("expire=")) {
                    int value = Integer.parseInt(param.substring(7));
                    return value > 0 ? value : null;
                }
            }
        } catch (Exception ignored) {
            // 畸形地址按无租约处理
        }
        return null;
    }

    /** 建议换链时刻 = 签发时刻 + expire - min(expire/4, 45s)(短租约保留四分之三寿命);无租约返回 null。 */
    public static Long refreshAtEpochMs(String url, long issuedAtEpochMs) {
        Integer expire = expireSeconds(url);
        if (expire == null) {
            return null;
        }
        long lead = Math.min(expire * 1000L / 4, RENEW_LEAD_MS);
        return issuedAtEpochMs + expire * 1000L - lead;
    }
}
