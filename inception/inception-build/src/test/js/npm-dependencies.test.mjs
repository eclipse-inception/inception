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
import { expect, test } from 'bun:test';
import { mkdirSync, mkdtempSync, readFileSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';

const SCRIPT = join(import.meta.dir, '../../main/resources/inception/npm-dependencies.mjs');

function write(aFile, aContent) {
  mkdirSync(dirname(aFile), { recursive: true });
  writeFileSync(aFile, typeof aContent === 'string' ? aContent : JSON.stringify(aContent));
}

// Writes a package into a node_modules tree: its package.json and any further files
function pkg(aDir, aJson, aFiles = {}) {
  write(join(aDir, 'package.json'), aJson);
  for (const [name, content] of Object.entries(aFiles)) write(join(aDir, name), content);
}

// A sourcemap in the build output, naming the given sources relative to it
function map(aModule, aFile, aSources) {
  write(join(aModule, 'target', 'js', aFile), { version: 3, sources: aSources, mappings: '' });
}

function run(aModule) {
  const out = join(aModule, 'target', 'classes', 'META-INF', 'NPM-DEPENDENCIES.json');
  const proc = Bun.spawnSync([process.execPath, SCRIPT, out, join(aModule, 'target', 'js')],
    { cwd: join(aModule, 'src', 'main', 'ts') });
  return {
    exitCode: proc.exitCode,
    stderr: proc.stderr.toString(),
    deps: proc.exitCode === 0 ? JSON.parse(readFileSync(out, 'utf8')) : undefined,
  };
}

// A module as the build leaves it: src/main/ts with node_modules and sources, the build output
// in target/js with sourcemaps
function project() {
  const root = mkdtempSync(join(tmpdir(), 'npm-deps-'));
  const module = join(root, 'module');
  const ts = join(module, 'src', 'main', 'ts');
  const nm = join(ts, 'node_modules');
  pkg(ts, {
    name: '@example/app', version: '1.0.0', license: 'Apache-2.0',
    dependencies: { 'lib-a': '^1', unused: '^1' },
    devDependencies: { tool: '^1', '@types/thing': '^1' },
  });
  // Bundled: named in the sourcemap
  pkg(join(nm, 'lib-a'), {
    name: 'lib-a', version: '1.0.0', license: 'MIT',
    author: 'Jane Doe <jane@example.org> (https://example.org)',
    repository: { type: 'git', url: 'git+https://github.com/example/lib-a.git' },
    dependencies: { 'lib-b': '^2' },
  }, {
    'index.js': '',
    LICENSE: 'MIT License\n\nCopyright (c) 2020 Jane Doe\nAll rights reserved.\n\n'
      + 'THE SOFTWARE IS PROVIDED BY THE\nCOPYRIGHT HOLDERS "AS IS"\n',
    NOTICE: 'Lib A\nCopyright The Lib A Authors\n',
  });
  // lib-a's lib-b 2 is nested; the hoisted lib-b 1 is in no output
  pkg(join(nm, 'lib-a', 'node_modules', 'lib-b'), {
    name: 'lib-b', version: '2.0.0', repository: 'github:example/lib-b',
    licenses: [{ type: 'MIT' }, { type: 'Apache-2.0' }],
  }, { 'index.js': '' });
  pkg(join(nm, 'lib-b'), { name: 'lib-b', version: '1.0.0', license: 'MIT' });
  pkg(join(nm, 'bare'), { name: 'bare', version: '1.0.0' }, { 'index.js': '' });
  pkg(join(nm, 'priv'), { name: 'priv', version: '1.0.0', private: true }, { 'index.js': '' });
  // Declared, but nothing of it is emitted
  pkg(join(nm, 'unused'), { name: 'unused', version: '1.0.0', license: 'MIT' });
  // Build tools, also with a native optional package
  pkg(join(nm, 'tool'), { name: 'tool', version: '1.0.0', license: 'MIT',
    optionalDependencies: { 'tool-native': '^1' } });
  pkg(join(nm, 'tool-native'), { name: 'tool-native', version: '1.0.0', license: 'MIT' });
  pkg(join(nm, '@types', 'thing'), { name: '@types/thing', version: '1.0.0', license: 'MIT' },
    { 'index.d.ts': '' });
  // Styles compiled from a package: imported from a component through a partial that Sass
  // resolves relative to it ('partial' is src/_partial.scss), and from a linked workspace package
  pkg(join(nm, 'style-pkg'), { name: 'style-pkg', version: '1.0.0', license: 'MIT' },
    { 'scss/_x.scss': '' });
  pkg(join(nm, 'style-pkg2'), { name: 'style-pkg2', version: '1.0.0', license: 'MIT' },
    { 'scss/_y.scss': '' });
  pkg(join(root, 'linked'), { name: '@example/linked', version: '1.0.0', license: 'MIT' },
    { 'style/_colors.scss': "@use 'style-pkg2/scss/y';\n" });
  mkdirSync(join(nm, '@example'), { recursive: true });
  symlinkSync(join(root, 'linked'), join(nm, '@example', 'linked'));
  write(join(ts, 'src', 'Comp.svelte'), "<style lang=\"scss\">\n  @use 'partial';\n"
    + "  @use '~@example/linked/style/colors';\n</style>\n");
  write(join(ts, 'src', '_partial.scss'), "@use 'style-pkg/scss/x';\n");
  // Copied by the build script: with its dependency and peer, not its optional platform package
  pkg(join(nm, 'prebuilt'), { name: 'prebuilt', version: '1.0.0', license: 'MIT',
    dependencies: { 'dep-d': '^1' }, peerDependencies: { 'peer-p': '^1', 'peer-absent': '^1' },
    optionalDependencies: { 'opt-native': '^1' } }, { 'dist/file.js': 'prebuilt code' });
  pkg(join(nm, 'dep-d'), { name: 'dep-d', version: '1.0.0', license: 'ISC' });
  pkg(join(nm, 'peer-p'), { name: 'peer-p', version: '1.0.0', license: 'ISC' });
  pkg(join(nm, 'opt-native'), { name: 'opt-native', version: '1.0.0', license: 'MIT' });
  write(join(ts, 'build.mjs'), "fs.copySync('node_modules/prebuilt/dist', outbase)\n");
  // The build output
  write(join(module, 'target', 'js', 'app.min.js'), '');
  map(module, 'app.min.js.map', ['../../src/main/ts/node_modules/lib-a/index.js',
    '../../src/main/ts/node_modules/lib-a/node_modules/lib-b/index.js',
    '../../src/main/ts/node_modules/bare/index.js', '../../src/main/ts/node_modules/priv/index.js',
    '../../src/main/ts/src/Comp.svelte']);
  write(join(module, 'target', 'js', 'app.min.css'), '');
  map(module, 'app.min.css.map', ['../../src/main/ts/src/Comp.svelte']);
  // Without a map, but allowed: the unminified twin, and an unchanged copy of a copied file
  write(join(module, 'target', 'js', 'app.css'), '');
  write(join(module, 'target', 'js', 'file.js'), 'prebuilt code');
  return module;
}

test('lists what the build output contains and nothing that only builds', () => {
  const { exitCode, stderr, deps } = run(project());

  expect(stderr).toBe('');
  expect(exitCode).toBe(0);
  // Not: the declared but unused package, the build tools and their native package, @types, the
  // hoisted lib-b 1, the private package, the copied package's optional package, the absent peer,
  // and the linked workspace package (our own code; only what its styles import is listed)
  expect(Object.keys(deps)).toEqual(['bare@1.0.0', 'dep-d@1.0.0', 'lib-a@1.0.0', 'lib-b@2.0.0',
    'peer-p@1.0.0', 'prebuilt@1.0.0', 'style-pkg2@1.0.0', 'style-pkg@1.0.0']);
});

test('describes each package from its own files without guessing', () => {
  const { deps } = run(project());

  expect(deps['lib-a@1.0.0']).toEqual({
    licenses: 'MIT',
    repository: 'https://github.com/example/lib-a',
    publisher: 'Jane Doe',
    email: 'jane@example.org',
    url: 'https://example.org',
    copyright: 'Copyright (c) 2020 Jane Doe All rights reserved.',
    noticeText: 'Lib A\nCopyright The Lib A Authors',
  });
  // The old forms of the license field, and a repository shorthand
  expect(deps['lib-b@2.0.0'].licenses).toBe('(MIT OR Apache-2.0)');
  expect(deps['lib-b@2.0.0'].repository).toBe('https://github.com/example/lib-b');
  // No license field: no license, rather than a guess
  expect(deps['bare@1.0.0']).toEqual({});
});

test('requires code bundled before it reached us to be declared as embedded content', () => {
  const module = project();
  // Inside a prebuilt package: its sourcemap points into its authors' tree
  map(module, 'vendor.min.js.map', ['webpack://vendor/./node_modules/foreign/index.js']);
  write(join(module, 'target', 'js', 'vendor.min.js'), '');

  const failed = run(module);
  expect(failed.exitCode).toBe(1);
  expect(failed.stderr).toContain('foreign is bundled into');
  expect(failed.stderr).toContain('DEPENDENCIES-EMBEDDED.spdx.json');

  write(join(module, 'src', 'main', 'resources', 'META-INF', 'DEPENDENCIES-EMBEDDED.spdx.json'),
    { packages: [{ name: 'foreign', externalRefs: [{ referenceCategory: 'PACKAGE-MANAGER',
      referenceType: 'purl', referenceLocator: 'pkg:npm/foreign@1.0.0' }] }] });
  expect(run(module).exitCode).toBe(0);
});

test('fails on emitted code without a sourcemap', () => {
  const module = project();
  write(join(module, 'target', 'js', 'other.min.js'), '');

  const { exitCode, stderr } = run(module);

  expect(exitCode).toBe(1);
  expect(stderr).toContain('other.min.js has no sourcemap');
});
