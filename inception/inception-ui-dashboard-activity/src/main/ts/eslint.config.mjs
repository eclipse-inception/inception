/*
 * Licensed to the Technische Universität Darmstadt under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The Technische Universität Darmstadt
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// =======================================================================
// Canonical source:
// /inception/inception-build/src/main/resources/inception/eslint.config.mjs
// Per-module copies under */src/main/ts/eslint.config.mjs are managed by
// update-bun-lock.sh -- edit the canonical file rather than the copies.
// =======================================================================

import { defineConfig, globalIgnores } from "eslint/config";
import neostandard, { plugins } from "neostandard";

// eslint-plugin-svelte is only a dependency of the modules that use Svelte
function isInstalled(aModule) {
  try {
    import.meta.resolve(aModule);
    return true;
  } catch {
    return false;
  }
}

const svelte = isInstalled("eslint-plugin-svelte")
  ? (await import("eslint-plugin-svelte")).default
  : undefined;

export default defineConfig([
  globalIgnores(["dist/", "pdfjs-web/"]),

  // Formatting is left to Prettier, so the neostandard style rules are off
  ...neostandard({ ts: true, noStyle: true, env: ["browser"] }),

  ...(svelte
    ? [
        ...svelte.configs.recommended,
        {
          files: ["**/*.svelte", "**/*.svelte.ts"],
          languageOptions: {
            parserOptions: {
              parser: plugins["typescript-eslint"].parser,
            },
          },
        },
      ]
    : []),

  {
    rules: {
      "no-unused-expressions": "off",
      "@typescript-eslint/no-unused-expressions": "off",
    },
  },
]);
