package cn.har01d.alist_tvbox.live.service;

import cn.har01d.alist_tvbox.tvbox.CategoryList;
import cn.har01d.alist_tvbox.tvbox.MovieList;

import java.io.IOException;
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
     * dual=「直连$$$代理」双线路,客户端默认直连平台 CDN(零服务器带宽),直连失败/断流
     * 由播放器自动切换代理线路续播(FongMi 点播错误自动 fallbackToNextLine)。
     * 代理条目缺失(探针等无代理实例/降级)时回落直连单线路。
     *
     * @return {vod_play_from, vod_play_url}
     */
    default String[] buildPlayLines(List<String> directEntries, List<String> proxyEntries, String proxyMode) {
        if ("dual".equals(proxyMode) && !directEntries.isEmpty() && !proxyEntries.isEmpty()) {
            return new String[]{"直连$$$代理",
                    String.join("#", directEntries) + "$$$" + String.join("#", proxyEntries)};
        }
        List<String> entries = proxyEntries.isEmpty() ? directEntries : proxyEntries;
        return new String[]{"线路1", String.join("#", entries)};
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
