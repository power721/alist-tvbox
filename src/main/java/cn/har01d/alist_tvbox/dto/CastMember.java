package cn.har01d.alist_tvbox.dto;

import lombok.Data;

/** 演职人员(媒体详情页卡):头像 + 姓名 + 角色/职务。 */
@Data
public class CastMember {
    private String name;
    /** 演员=饰演角色;导演/编剧=职务 */
    private String role;
    private String avatar;

    public CastMember() {
    }

    public CastMember(String name, String role, String avatar) {
        this.name = name;
        this.role = role;
        this.avatar = avatar;
    }
}
