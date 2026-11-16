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
import { mkdirSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const SCRIPT = join(import.meta.dir, '../../main/resources/inception/npm-dependencies.mjs');

// Writes a package into a node_modules tree: its package.json and any further files
function pkg(aDir, aJson, aFiles = {}) {
  mkdirSync(aDir, { recursive: true });
  writeFileSync(join(aDir, 'package.json'), JSON.stringify(aJson));
  for (const [name, content] of Object.entries(aFiles)) writeFileSync(join(aDir, name), content);
}

function run(aRoot) {
  const out = join(aRoot, 'out', 'NPM-DEPENDENCIES.json');
  const proc = Bun.spawnSync([process.execPath, SCRIPT, out], { cwd: aRoot });
  return {
    exitCode: proc.exitCode,
    stderr: proc.stderr.toString(),
    deps: proc.exitCode === 0 ? JSON.parse(readFileSync(out, 'utf8')) : undefined,
  };
}

function project() {
  const root = mkdtempSync(join(tmpdir(), 'npm-deps-'));
  const nm = join(root, 'node_modules');
  pkg(root, {
    name: '@example/app', version: '1.0.0', license: 'Apache-2.0',
    dependencies: { 'lib-a': '^1', '@types/thing': '^1', bare: '^1' },
    devDependencies: { tool: '^1' },
    optionalDependencies: { 'opt-native': '^1', 'opt-absent': '^1' },
  });
  pkg(join(nm, 'lib-a'), {
    name: 'lib-a', version: '1.0.0', license: 'MIT',
    author: 'Jane Doe <jane@example.org> (https://example.org)',
    repository: { type: 'git', url: 'git+https://github.com/example/lib-a.git' },
    dependencies: { 'lib-b': '^2', priv: '^1' },
    peerDependencies: { 'peer-p': '^1', 'peer-absent': '^1' },
  }, {
    LICENSE: 'MIT License\n\nCopyright (c) 2020 Jane Doe\nAll rights reserved.\n\n'
      + 'THE SOFTWARE IS PROVIDED BY THE\nCOPYRIGHT HOLDERS "AS IS"\n',
    NOTICE: 'Lib A\nCopyright The Lib A Authors\n',
  });
  // lib-a needs lib-b 2, nested in its own node_modules; the hoisted lib-b 1 is used by nobody
  pkg(join(nm, 'lib-a', 'node_modules', 'lib-b'), {
    name: 'lib-b', version: '2.0.0', repository: 'github:example/lib-b',
    licenses: [{ type: 'MIT' }, { type: 'Apache-2.0' }],
  });
  pkg(join(nm, 'lib-b'), { name: 'lib-b', version: '1.0.0', license: 'MIT' });
  pkg(join(nm, 'priv'), { name: 'priv', version: '1.0.0', private: true,
    dependencies: { 'lib-c': '^1' } });
  pkg(join(nm, 'lib-c'), { name: 'lib-c', version: '1.0.0', license: { type: 'BSD-3-Clause' } });
  pkg(join(nm, 'peer-p'), { name: 'peer-p', version: '1.0.0', license: 'ISC' });
  pkg(join(nm, '@types', 'thing'), { name: '@types/thing', version: '1.0.0', license: 'MIT',
    dependencies: { 'types-only': '^1' } });
  pkg(join(nm, 'types-only'), { name: 'types-only', version: '1.0.0', license: 'MIT' });
  pkg(join(nm, 'tool'), { name: 'tool', version: '1.0.0', license: 'MIT',
    optionalDependencies: { 'tool-native': '^1' } });
  pkg(join(nm, 'tool-native'), { name: 'tool-native', version: '1.0.0', license: 'MIT' });
  pkg(join(nm, 'opt-native'), { name: 'opt-native', version: '1.0.0', license: 'MIT' });
  pkg(join(nm, 'bare'), { name: 'bare', version: '1.0.0' });
  return root;
}

test('lists what ships and nothing that only builds', () => {
  const { exitCode, stderr, deps } = run(project());

  expect(stderr).toBe('');
  expect(exitCode).toBe(0);
  // Not: the dev-only tool and its native package, @types packages and their dependencies, the
  // unused hoisted lib-b 1, the private package (but its dependency lib-c), absent optionals/peers
  expect(Object.keys(deps)).toEqual(['@example/app@1.0.0', 'bare@1.0.0', 'lib-a@1.0.0',
    'lib-b@2.0.0', 'lib-c@1.0.0', 'opt-native@1.0.0', 'peer-p@1.0.0']);
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
  expect(deps['lib-c@1.0.0'].licenses).toBe('BSD-3-Clause');
  // No license field: no license, rather than a guess
  expect(deps['bare@1.0.0']).toEqual({});
});

test('fails when a dependency is not installed', () => {
  const root = project();
  pkg(root, { name: '@example/app', version: '1.0.0', dependencies: { absent: '^1' } });

  const { exitCode, stderr } = run(root);

  expect(exitCode).toBe(1);
  expect(stderr).toContain('@example/app: dependency absent is not installed');
});
