declare module '@/utils/subscriptionConfig.mjs' {
  export function parseOverride(text: string): Record<string, any> | null
  export function stringify(config: Record<string, any>): string
  export function detectFilterMode(config: Record<string, any>): string
  export function disabledSiteKeys(config: Record<string, any>): string[]
  export function whitelistKeys(config: Record<string, any>): string[]
  export function disabledParseNames(config: Record<string, any>): string[]
  export function siteOverrideMap(config: Record<string, any>): Record<string, any>
  export function customSites(config: Record<string, any>, catalogKeys: string[]): any[]
  export function buildHeaderRows(config: Record<string, any>): Array<{ host: string; pairs: Array<{ name: string; value: string }> }>
  export function buildLiveRows(config: Record<string, any>): any[]
  export function buildDohRows(config: Record<string, any>): Array<{ name: string; url: string; ips: string[] }>
  export function buildProxyRows(config: Record<string, any>): Array<{ name: string; hosts: string[]; urls: string[] }>
  export function buildRulesRows(config: Record<string, any>): Array<{ name: string; hosts: string[]; regex: string[]; script: string[]; exclude: string[] }>
  export function serialize(baseConfig: Record<string, any>, state: Record<string, any>): Record<string, any>
}
