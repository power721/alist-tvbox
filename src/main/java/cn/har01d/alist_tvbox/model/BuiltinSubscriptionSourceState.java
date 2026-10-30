package cn.har01d.alist_tvbox.model;

public class BuiltinSubscriptionSourceState {
    private String name;
    private Boolean enabled;
    private Boolean t4Visible;
    private Integer sortOrder;
    private String extend;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    /** T4 接口可见性(null = 未设置,按默认可见处理;序列化进 builtin_subscription_sources JSON)。 */
    public Boolean getT4Visible() {
        return t4Visible;
    }

    public void setT4Visible(Boolean t4Visible) {
        this.t4Visible = t4Visible;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

    public String getExtend() {
        return extend;
    }

    public void setExtend(String extend) {
        this.extend = extend;
    }
}
