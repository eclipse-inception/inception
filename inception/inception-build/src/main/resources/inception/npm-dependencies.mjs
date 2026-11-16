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

// Writes NPM-DEPENDENCIES.json: the npm packages a module ships, with their licenses.
//
// These are the dependencies of the package in the working directory and, transitively, theirs -
// resolved the way Node and the bundlers do, from each package's real location upwards through
// node_modules. Followed are dependencies and the installed peerDependencies (e.g. Bootstrap's
// Popper) and optionalDependencies. Packages reachable only from devDependencies are never
// visited; @types packages are skipped, since they hold only type declarations. Nothing is guessed: a package without a license field is written without
// one, and the legal documentation generator then asks for a concluded license.
//
// Usage: bun npm-dependencies.mjs <output file>

import { existsSync, mkdirSync, readdirSync, readFileSync, realpathSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';

const COPYRIGHT = /^\s*(?:copyright\b(?!\s+(?:holders?|notice|owners?|law)\b)|\(c\)\s|©)/i;
const RESERVED = /^\s*all rights reserved\.?\s*$/i;
const LICENSE_FILE = /^(?:licen[cs]e|copying)(?:[.-].*)?$/i;
const NOTICE_FILE = /^notice(?:\..*)?$/i;

const outFile = process.argv[2];
if (!outFile) {
  console.error('Usage: bun npm-dependencies.mjs <output file>');
  process.exit(1);
}

const result = {};
const errors = [];
const visited = new Set();

visit(realpathSync(process.cwd()));

if (errors.length) {
  console.error(`Unable to list the npm dependencies:\n  - ${errors.join('\n  - ')}`);
  process.exit(1);
}

const sorted = Object.fromEntries(Object.keys(result).sort().map((k) => [k, result[k]]));
mkdirSync(dirname(outFile), { recursive: true });
writeFileSync(outFile, JSON.stringify(sorted, null, 2) + '\n');

function visit(dir) {
  if (visited.has(dir)) return;
  visited.add(dir);

  const pkg = JSON.parse(readFileSync(join(dir, 'package.json'), 'utf8'));
  if (pkg.name?.startsWith('@types/')) return;
  if (!pkg.private) result[`${pkg.name}@${pkg.version}`] = describe(dir, pkg);

  const optional = { ...pkg.peerDependencies, ...pkg.optionalDependencies };
  for (const name of Object.keys({ ...optional, ...pkg.dependencies }).sort()) {
    const dependency = locate(name, dir);
    if (dependency) {
      visit(dependency);
    }
    else if (!(name in optional) || name in (pkg.dependencies ?? {})) {
      errors.push(`${pkg.name}: dependency ${name} is not installed`);
    }
  }
}

// The nearest node_modules/<name> going up from the requiring package, as Node resolves it
function locate(name, fromDir) {
  for (let dir = fromDir; ; dir = dirname(dir)) {
    const candidate = join(dir, 'node_modules', name);
    if (existsSync(join(candidate, 'package.json'))) return realpathSync(candidate);
    if (dirname(dir) === dir) return null;
  }
}

function describe(dir, pkg) {
  const author = person(pkg.author);
  const files = readdirSync(dir).sort();
  const entry = {
    licenses: license(pkg),
    repository: repositoryUrl(pkg.repository),
    publisher: author.name,
    email: author.email,
    url: author.url,
    copyright: copyright(dir, files),
    noticeText: files.filter((f) => NOTICE_FILE.test(f)).map((f) => read(dir, f).trim())[0],
  };
  return Object.fromEntries(Object.entries(entry).filter(([, v]) => v));
}

// "MIT", "(MIT OR Apache-2.0)", or the old forms { type } and [{ type }, ...]
function license(pkg) {
  const declared = pkg.license ?? pkg.licenses;
  if (typeof declared === 'string') return declared;
  if (Array.isArray(declared)) {
    const ids = declared.map((l) => (typeof l === 'string' ? l : l?.type)).filter(Boolean);
    return ids.length > 1 ? `(${ids.join(' OR ')})` : ids[0];
  }
  return declared?.type;
}

// "user/repo", "github:user/repo", "git+https://host/user/repo.git", "git@host:user/repo.git", ...
function repositoryUrl(repository) {
  const url = typeof repository === 'string' ? repository : repository?.url;
  if (!url) return undefined;
  const shorthand = url.match(/^(?:(github|gitlab|bitbucket):)?([\w.-]+\/[\w.-]+)$/);
  if (shorthand) {
    const host = { gitlab: 'gitlab.com', bitbucket: 'bitbucket.org' }[shorthand[1]] ?? 'github.com';
    return `https://${host}/${shorthand[2]}`;
  }
  return url
    .replace(/^git\+/, '')
    .replace(/^git:\/\//, 'https://')
    .replace(/^ssh:\/\/(?:git@)?/, 'https://')
    .replace(/^git@([^:]+):/, 'https://$1/')
    .replace(/^http:\/\/github\.com\//, 'https://github.com/')
    .replace(/\.git$/, '');
}

// "Name <email> (url)" or { name, email, url }
function person(author) {
  if (!author) return {};
  if (typeof author === 'object') return author;
  const m = author.match(/^([^<(]*?)\s*(?:<([^>]*)>)?\s*(?:\(([^)]*)\))?\s*$/);
  return m ? { name: m[1] || undefined, email: m[2], url: m[3] } : { name: author };
}

// The copyright lines of the package's license file, which permissive licenses such as MIT
// require to be passed on
function copyright(dir, files) {
  const licenseFile = files.find((f) => LICENSE_FILE.test(f));
  if (!licenseFile) return undefined;
  // "All rights reserved." on a line of its own belongs to the copyright line before it
  const lines = read(dir, licenseFile).split(/\r?\n/);
  const notices = lines
    .map((l, i) => (COPYRIGHT.test(l) && RESERVED.test(lines[i + 1] ?? '')
      ? `${l.trim()} ${lines[i + 1].trim()}`
      : l.trim()))
    .filter((l, i) => COPYRIGHT.test(lines[i]));
  return [...new Set(notices)].join(' ') || undefined;
}

function read(dir, file) {
  return readFileSync(join(dir, file), 'utf8');
}
