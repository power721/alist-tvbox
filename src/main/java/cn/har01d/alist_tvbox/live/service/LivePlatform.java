package cn.har01d.alist_tvbox.live.service;

import cn.har01d.alist_tvbox.tvbox.CategoryList;
import cn.har01d.alist_tvbox.tvbox.MovieList;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public interface LivePlatform {
    String getType();

    String getName();

    /** 播放流量经本服务代理中转(直播续租代理或 /p 通用代理),平台管理页展示用。 */
    default boolean isProxied() {
        return false;
    }

    /**
     * 播放条目按代理模式拼装:proxy=单线路全代理(断流由代理续租,用户无感);
     * dual=双线路兼容两类内核的失败自动兜底——线路1「直连优先」=分集内同档画质「直连,代理」
     * 相邻交错,「播放失败切下一集」型内核(如 OK 影视)直连失败自动落到同档代理条目(续租);
     * 线路2「代理」=纯代理全档,FongMi(点播错误切线路 fallbackToNextLine)直连失败自动切到
     * 该线路续播。下播时逐档降级直至全部失败;条目标签追加·直连/·代理便于手动选集区分;
     * 代理条目缺失(探针等无代理实例/降级)时回落直连全档单线路;网页端恒走纯代理(浏览器 CORS)。
     *
     * @return {vod_play_from, vod_play_url}
     */
    default String[] buildPlayLines(List<String> directEntries, List<String> proxyEntries, String proxyMode) {
        if ("dual".equals(proxyMode) && !directEntries.isEmpty() && !proxyEntries.isEmpty()) {
            List<String> interleaved = new ArrayList<>(directEntries.size() * 2);
            for (int i = 0; i < directEntries.size(); i++) {
                interleaved.add(relabel(directEntries.get(i), "·直连"));
                if (i < proxyEntries.size()) {
                    interleaved.add(relabel(proxyEntries.get(i), "·代理"));
                }
            }
            return new String[]{"直连优先$$$代理",
                    String.join("#", interleaved) + "$$$" + String.join("#", proxyEntries)};
        }
        List<String> entries = proxyEntries.isEmpty() ? directEntries : proxyEntries;
        return new String[]{"线路1", String.join("#", entries)};
    }

    /** 条目标签追加后缀,保持 label$url 结构。 */
    private static String relabel(String entry, String suffix) {
        int sep = entry.indexOf('$');
        return sep < 0 ? entry : entry.substring(0, sep) + suffix + entry.substring(sep);
    }

    MovieList home() throws IOException;

    CategoryList category() throws IOException;

    MovieList list(String id, String ac, String sort, Integer pg) throws IOException;

    MovieList search(String wd) throws IOException;

    MovieList detail(String tid, String client) throws IOException;

    default String playCount(int view) {
        if (view >= 10000) {
            return (view / 10000) + "万";
        } else if (view >= 1000) {
            return (view / 1000) + "千";
        } else {
            return view + "";
        }
    }

    default String playCount(String count) {
        if (count == null || count.isBlank()) {
            return null;
        }
        int view = Integer.parseInt(count);
        return playCount(view);
    }
}
