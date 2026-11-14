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
import sveltePreprocess from 'svelte-preprocess'
import yargs from 'yargs/yargs'
import { hideBin } from 'yargs/helpers'
import { sassPlugin } from 'esbuild-sass-plugin'
import fs from 'fs-extra'

const argv = yargs(hideBin(process.argv)).argv

const dev = Boolean(argv.live || argv.once)

const packagePath = 'de/tudarmstadt/ukp/clarin/webanno/brat/resource'

let outbase = `../../../target/js/${packagePath}`
if (dev) {
  outbase = `../../../target/classes/${packagePath}`
}

const defaults = {
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
  },
  plugins: [
    sassPlugin(),
    esbuildSvelte({
      compilerOptions: { dev: dev },
      preprocess: sveltePreprocess(),
      filterWarnings: (warning) => {
        // Ignore warnings about unused CSS selectors in Svelte components which appear as we import
        // Bootstrap CSS files. We do not use all selectors in the files and thus the warnings are
        // expected.
        if (warning.code === 'css-unused-selector') {
          return false
        }
      }
    })
  ]
}

fs.mkdirsSync(`${outbase}`)
if (!dev) {
  fs.emptyDirSync(outbase)
}

if (argv.live) {
  const context1 = await esbuild.context(Object.assign({
    entryPoints: ['src/brat.ts'],
    outfile: `${outbase}/brat.min.js`,
    globalName: 'Brat'
  }, defaults))
  const context2 = await esbuild.context(Object.assign({
    entryPoints: ['src/brat_curation.ts'],
    outfile: `${outbase}/brat_curation.min.js`,
    globalName: 'BratCuration'
  }, defaults))
  await Promise.all([context1.watch(), context2.watch()])
} else {
  esbuild.build(Object.assign({
    entryPoints: ['src/brat.ts'],
    outfile: `${outbase}/brat.min.js`,
    globalName: 'Brat'
  }, defaults))
  esbuild.build(Object.assign({
    entryPoints: ['src/brat_curation.ts'],
    outfile: `${outbase}/brat_curation.min.js`,
    globalName: 'BratCuration'
  }, defaults))
}
