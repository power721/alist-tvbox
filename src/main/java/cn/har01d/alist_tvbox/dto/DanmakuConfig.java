package cn.har01d.alist_tvbox.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * 直播弹幕渲染配置(Setting 表 danmaku_config 单行 JSON,web-ui 弹幕管理 tab 编辑)。
 * 经轮询接口以解析后的数值下发给 spider:rows/speed 原样,速度档换算为 duration 毫秒。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class DanmakuConfig {
    /** 总开关,关闭后后端不再连上游、spider 隐藏弹幕层 */
    private boolean enabled = true;
    /** 行数:0=自动(3-6 轨动态),1-8=固定 */
    private int rows = 0;
    /** 速度档:0=慢 1=正常 2=快 */
    private int speed = 1;
    /** 字号缩放百分比:50-200,100=标准(基准为客户端动态字号) */
    private int fontSize = 100;
    /** 不透明度百分比:10-100,100=不透明 */
    private int opacity = 100;
    /** 强制单色 #RRGGBB,空=跟随平台原色 */
    private String color = "";
    /** 是否下发实时人气值消息,关闭后 online 消息不入房间缓冲 */
    private boolean showOnline = true;

    /** 归一化非法取值,解析与更新时都调用 */
    public void normalize() {
        rows = Math.max(0, Math.min(8, rows));
        speed = Math.max(0, Math.min(2, speed));
        fontSize = Math.max(50, Math.min(200, fontSize));
        opacity = Math.max(10, Math.min(100, opacity));
        color = color != null && color.matches("#[0-9a-fA-F]{6}") ? color : "";
    }
}
