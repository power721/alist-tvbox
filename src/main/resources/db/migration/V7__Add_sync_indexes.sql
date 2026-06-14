-- 添加远程同步功能需要的索引，优化查询性能

-- Account 按 nickname 查询（用于同步时匹配）
CREATE INDEX IF NOT EXISTS idx_account_nickname ON account(nickname);

-- DriverAccount 按 type + username 查询（主查询条件）
CREATE INDEX IF NOT EXISTS idx_driver_account_type_username ON driver_account(type, username);

-- DriverAccount 按 type + name 查询（fallback 查询条件）
CREATE INDEX IF NOT EXISTS idx_driver_account_type_name ON driver_account(type, name);

-- Share 按 type + share_id 查询（用于同步时匹配）
CREATE INDEX IF NOT EXISTS idx_share_type_shareid ON share(type, share_id);

-- Subscription 按 url 查询（用于同步时匹配）
CREATE INDEX IF NOT EXISTS idx_subscription_url ON subscription(url);

-- Plugin 按 externalId 查询（主查询条件）
CREATE INDEX IF NOT EXISTS idx_plugin_external_id ON plugin(external_id);

-- Plugin 按 url 查询（fallback 查询条件）
CREATE INDEX IF NOT EXISTS idx_plugin_url ON plugin(url);

-- PluginFilter 按 url 查询（用于同步时匹配）
CREATE INDEX IF NOT EXISTS idx_plugin_filter_url ON plugin_filter(url);

-- PikPakAccount 按 username 查询（用于同步时匹配）
CREATE INDEX IF NOT EXISTS idx_pikpak_account_username ON pikpak_account(username);

-- Site 按 url 查询（用于同步时匹配）
CREATE INDEX IF NOT EXISTS idx_site_url ON site(url);
