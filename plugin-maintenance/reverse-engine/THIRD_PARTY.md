# Reverse Analysis Dependencies

- `chrome-remote-interface@0.34.0`: Andrea Cardaci, MIT. Supplies flattened CDP
  sessions for task-owned page, iframe and worker targets. The temporary browser's
  debugging endpoint is loopback-only; it is never a user's existing browser.
- `playwright-core@1.58.2`: Microsoft, Apache-2.0. Dependency source and license are
  retained in the installed package. Chromium is selected by the administrator;
  this package does not download a browser.
- `webcrack@2.15.1`: j4k0xb, MIT. The exact package and transitive dependencies are
  locked in `package-lock.json`. Install using `npm ci --ignore-scripts`; the native
  isolated-vm install script is not executed. The product supplies its own offline
  Chromium sandbox and never uses webcrack's default Node sandbox.
- Design reference: `zhizhuodemao/js-reverse-mcp`, commit
  `bf7dc506e8743ba1ec5bd3325c91818b9192e40e`, Apache-2.0. Its script registry,
  asynchronous initiator stacks, XHR breakpoint and scope observation approach is
  adapted through standard CDP APIs. No upstream MCP server or source file is
  bundled, and no global MCP configuration is changed.

The earlier studied webcrack commit is a research reference, not the identity of
the npm package used here. Recovery output is diagnostic material, not a patch or
a proof of semantic equivalence. Original script identities are retained.
