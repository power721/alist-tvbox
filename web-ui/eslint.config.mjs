import pluginVue from 'eslint-plugin-vue'
import vueTs from '@vue/eslint-config-typescript'


export default [
  {
    name: 'app/files-to-lint',
    files: ['**/*.{ts,mts,tsx,vue}'],
  },

  {
    name: 'app/files-to-ignore',
    ignores: ['**/dist/**', '**/dist-ssr/**', '**/coverage/**'],
  },

  ...pluginVue.configs['flat/essential'],
  ...vueTs(),
  {
    rules: {
      'vue/multi-word-component-names': 'off',
      'vue/html-indent': ['error', 2],
      'indent': ['error', 2],
      'quotes': ['error', 'single'],
      'semi': ['error', 'never'],
      'comma-dangle': ['error', 'always-multiline'],
      'arrow-parens': ['error', 'always'],
      'object-curly-spacing': ['error', 'always'],
      'array-bracket-spacing': ['error', 'never'],
      'vue/max-attributes-per-line': ['error', {
        'singleline': { 'max': 5 },
        'multiline': { 'max': 1 },
      }],
    },
  },
]
