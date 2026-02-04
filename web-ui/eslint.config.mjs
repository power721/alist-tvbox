import pluginVue from 'eslint-plugin-vue'
import vueTs from '@vue/eslint-config-typescript'
import stylistic from '@stylistic/eslint-plugin'


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
    plugins: {
      '@stylistic': stylistic,
    },
    rules: {
      'vue/multi-word-component-names': 'off',
      'vue/html-indent': ['error', 2],
      'vue/max-attributes-per-line': ['error', {
        'singleline': { 'max': 5 },
        'multiline': { 'max': 1 },
      }],

      '@stylistic/indent': ['error', 2],
      '@stylistic/quotes': ['error', 'single'],
      '@stylistic/semi': ['error', 'never'],
      '@stylistic/comma-dangle': ['error', 'always-multiline'],
      '@stylistic/arrow-parens': ['error', 'always'],
      '@stylistic/object-curly-spacing': ['error', 'always'],
      '@stylistic/array-bracket-spacing': ['error', 'never'],
    },
  },
]
