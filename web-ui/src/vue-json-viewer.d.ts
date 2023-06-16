declare module 'vue-json-viewer' {
  import { AllowedComponentProps, App, Component, ComponentCustomProps, VNodeProps } from 'vue'
  interface JsonViewerProps {
    value: any | Array<any> | string | number | boolean;
    expanded: boolean;
    expandDepth: number;
    copyable: boolean | object;
    sort: boolean;
    boxed: boolean;
    theme: string;
    previewMode: boolean;
    timeformat: (value: any) => string
  }
  type JsonViewerType = JsonViewerProps & VNodeProps & AllowedComponentProps & ComponentCustomProps
  const JsonViewer: Component<JsonViewerType>
  export { JsonViewer }
  const def: { install: (app: App) => void }
  export default def
}
