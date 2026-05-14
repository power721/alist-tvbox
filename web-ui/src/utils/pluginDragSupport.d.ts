declare module '@/utils/pluginDragSupport.mjs' {
  export const MOBILE_BROWSER_USER_AGENT: RegExp

  export function isMobileBrowserUserAgent(userAgent?: string): boolean

  export function isPluginDragEnabledForUserAgent(userAgent?: string): boolean
}
