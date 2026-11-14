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

import esbuild from 'esbuild'
import esbuildSvelte from 'esbuild-svelte'
import { sveltePreprocess } from 'svelte-preprocess'
import yargs from 'yargs/yargs'
import { hideBin } from 'yargs/helpers'
import { sassPlugin } from 'esbuild-sass-plugin'
import fs from 'fs-extra'

const argv = yargs(hideBin(process.argv)).argv

const dev = Boolean(argv.live || argv.once)

const packagePath = 'de/tudarmstadt/ukp/inception/diam/sidebar'

let outbase = `../../../target/js/${packagePath}`
if (dev) {
  outbase = `../../../target/classes/${packagePath}`
}

const defaults = {
  entryPoints: ['src/DiamAnnotationBrowserFactory.ts'],
  outfile: `${outbase}/DiamAnnotationBrowser.min.js`,
  mainFields: ['svelte', 'browser', 'module', 'main'],
  format: 'esm',
  platform: 'browser',
  plugins: [
    sassPlugin(),
    esbuildSvelte({
      compilerOptions: { 
        runes: true,
        dev: dev
      },
      preprocess: sveltePreprocess()
    })
  ],
  bundle: true,
  sourcemap: true,
  minify: !dev,
  target: 'es2022',
  loader: { '.ts': 'ts' },
  logLevel: 'info',
  // Ensure Svelte runtime is shared across all components: Whenever you see an 
  // "import from 'svelte'"", resolve it once and reuse that same resolution everywhere.
  alias: {
    'svelte': 'svelte'
  }
}

fs.mkdirsSync(`${outbase}`)
if (!dev) {
  fs.emptyDirSync(outbase)
}

if (argv.live) {
  const context = await esbuild.context(defaults)
  await context.watch()
} else {
  esbuild.build(defaults)
}
