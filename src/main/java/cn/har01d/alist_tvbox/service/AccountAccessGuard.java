package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.domain.Role;
import cn.har01d.alist_tvbox.entity.Account;
import cn.har01d.alist_tvbox.entity.DriverAccount;
import cn.har01d.alist_tvbox.entity.PikPakAccount;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * 网盘账号归属守卫:多用户下账号凭证只下发给归属人(见 docs/multi-user-design.md)。
 * <ul>
 *   <li>管理(增删改/签到):ADMIN|CLIENT 或归属人本人。</li>
 *   <li>可见:管理权限 ∪ 本人账号 ∪ 全局账号(shared=true;列表脱敏,凭证不下发)。</li>
 *   <li>凭证下发(直连 Cookie/tokenm):ADMIN|CLIENT 或 ownerUid==当前用户。
 *       全局账号(ownerUid=0)视为管理员所有,普通用户只能经服务端代理使用。</li>
 * </ul>
 */
@Component
public class AccountAccessGuard {

    /** 解析失败哨兵:-1(非管理级且无法识别身份)。fail-closed(§3.4):不再回落 uid=0 冒充管理归属,
     * canManage/canView/canUseCredentials 对 -1 天然拒绝;调用方做数据过滤时 -1 匹配不到任何归属行。 */
    public static final int UNRESOLVED_UID = -1;

    public int currentUid() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        // 会话令牌路径 TokenFilter.setDetails(userId);Basic Auth 回落 principal 解析(id 字符串)
        if (authentication != null && authentication.getDetails() instanceof Integer userId) {
            return userId;
        }
        if (authentication != null
                && authentication.getPrincipal() instanceof org.springframework.security.core.userdetails.User user) {
            try {
                return Integer.parseInt(user.getUsername());
            } catch (NumberFormatException ignored) {
            }
        }
        // 解析失败:管理级身份(ADMIN/CLIENT)保持 0(全局管理者),其余一律 -1 拒绝
        return isElevated() ? 0 : UNRESOLVED_UID;
    }

    /** 管理级视角 uid:0=全局管理(全量);非管理级返回 currentUid()(-1=解析失败,按无归属行处理)。 */
    public int effectiveUid() {
        return isElevated() ? 0 : currentUid();
    }

    /** 管理级身份(ADMIN 或服务端互访 CLIENT):等同账号全局管理者。 */
    public boolean isElevated() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return false;
        }
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            if (Role.ADMIN.name().equals(authority.getAuthority())
                    || Role.CLIENT.name().equals(authority.getAuthority())) {
                return true;
            }
        }
        return false;
    }

    public boolean canManage(int ownerUid) {
        return isElevated() || ownerUid == currentUid();
    }

    public boolean canView(int ownerUid, boolean shared) {
        return isElevated() || ownerUid == currentUid()
                || (ownerUid == 0 && shared && currentUid() != UNRESOLVED_UID);
    }

    /** 凭证可否随直链/tokenm 下发给当前请求方:只有归属人(或管理级身份)。 */
    public boolean canUseCredentials(int ownerUid) {
        return isElevated() || ownerUid == currentUid();
    }

    public void checkManage(int ownerUid) {
        if (!canManage(ownerUid)) {
            throw new BadRequestException("无权管理该账号");
        }
    }

    /** 非归属人视角的账号副本:清空全部凭证字段(cookie/token/密码/用户名/敏感附加项)。 */
    public DriverAccount sanitize(DriverAccount account) {
        if (canUseCredentials(account.getOwnerUid())) {
            return account;
        }
        DriverAccount copy = new DriverAccount();
        copy.setId(account.getId());
        copy.setType(account.getType());
        copy.setName(account.getName());
        copy.setOwnerUid(account.getOwnerUid());
        copy.setShared(account.isShared());
        copy.setDisabled(account.isDisabled());
        copy.setUseProxy(account.isUseProxy());
        copy.setMaster(account.isMaster());
        copy.setFolder(account.getFolder());
        copy.setConcurrency(account.getConcurrency());
        return copy;
    }

    public Account sanitize(Account account) {
        if (canUseCredentials(account.getOwnerUid())) {
            return account;
        }
        Account copy = new Account();
        copy.setId(account.getId());
        copy.setNickname(account.getNickname());
        copy.setOwnerUid(account.getOwnerUid());
        copy.setShared(account.isShared());
        copy.setAutoCheckin(account.isAutoCheckin());
        copy.setShowMyAli(account.isShowMyAli());
        copy.setMaster(account.isMaster());
        copy.setClean(account.isClean());
        copy.setUseProxy(account.isUseProxy());
        copy.setCheckinDays(account.getCheckinDays());
        copy.setCheckinTime(account.getCheckinTime());
        copy.setRefreshTokenTime(account.getRefreshTokenTime());
        return copy;
    }

    public PikPakAccount sanitize(PikPakAccount account) {
        if (canUseCredentials(account.getOwnerUid())) {
            return account;
        }
        PikPakAccount copy = new PikPakAccount();
        copy.setId(account.getId());
        copy.setNickname(account.getNickname());
        copy.setPlatform(account.getPlatform());
        copy.setOwnerUid(account.getOwnerUid());
        copy.setShared(account.isShared());
        copy.setMaster(account.isMaster());
        return copy;
    }
}
