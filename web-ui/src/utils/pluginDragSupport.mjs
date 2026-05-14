export const MOBILE_BROWSER_USER_AGENT = /Android|webOS|iPhone|iPad|iPod|BlackBerry|IEMobile|Opera Mini/i

export const isMobileBrowserUserAgent = (userAgent = '') => MOBILE_BROWSER_USER_AGENT.test(userAgent)

export const isPluginDragEnabledForUserAgent = (userAgent = '') => !isMobileBrowserUserAgent(userAgent)
