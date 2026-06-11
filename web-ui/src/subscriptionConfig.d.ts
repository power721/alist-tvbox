declare module '@/utils/subscriptionConfig.mjs' {
  export function parseOverride(text: string): Record<string, any> | null
  export function stringify(config: Record<string, any>): string
  export function detectFilterMode(config: Record<string, any>): 'none' | 'whitelist' | 'blacklist'
  export function disabledSiteKeys(config: Record<string, any>): string[]
  export function whitelistKeys(config: Record<string, any>): string[]
  export function disabledParseNames(config: Record<string, any>): string[]
  export function siteOverrideMap(config: Record<string, any>): Record<string, { name?: any; order?: any }>
  export function customSites(config: Record<string, any>, catalogKeys: string[]): any[]
  export function serialize(baseConfig: Record<string, any>, state: any): Record<string, any>
}
