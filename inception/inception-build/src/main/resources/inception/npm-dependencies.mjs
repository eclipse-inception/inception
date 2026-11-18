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
// Listed is what the module's build output contains, not what its package.json declares: build
// tools never ship, and the runtime of a build-time dependency (Svelte's) does. The evidence:
//
// 1. The sourcemaps in the build output directory. Every bundle and stylesheet the build writes
//    has one, and it names each source file whose code was emitted, also the code bundled from
//    linked workspace packages such as inception-js-api.
// 2. The style imports of the source files named there (@use, @import, @forward of a package,
//    e.g. 'bootstrap/scss/bootstrap.scss' in a Svelte component): Sass compiles them into the
//    output, but the bundler's sourcemaps do not name them. Followed through relative imports
//    and through files of linked workspace packages.
// 3. The files the build script copies from node_modules (pdf.js's prebuilt files, Bootstrap's
//    bundle), with their dependencies and peerDependencies: a prebuilt bundle may contain those
//    (Popper in Bootstrap's), but not optional platform packages (@napi-rs/canvas for pdf.js).
//
// A package named in a sourcemap but not installed was bundled before it reached us, e.g. the
// libraries inside the vendored PDF.js viewer: it must be declared as embedded content in the
// module's META-INF/DEPENDENCIES-EMBEDDED.spdx.json. @types packages and private packages are
// never listed. Nothing is guessed: a package without a license field is written without one,
// and the legal documentation generator then asks for a concluded license.
//
// Usage: bun npm-dependencies.mjs <output file> <build output directory>

import { existsSync, mkdirSync, readdirSync, readFileSync, realpathSync as realpathOs, statSync,
  writeFileSync } from 'node:fs';
import { dirname as dirnameOs, join as joinOs, resolve as resolveOs, sep } from 'node:path';
import { fileURLToPath as fileURLToPathOs } from 'node:url';

// Paths are compared and taken apart as strings with "/" ('/node_modules/', the file name after
// the last "/"), so on Windows every path this script produces is turned into forward slashes,
// which Node's file functions accept there as well. Elsewhere a backslash is a legal file name
// character and stays.
const slash = sep === '\\' ? (p) => p.replaceAll('\\', '/') : (p) => p;
const dirname = (p) => slash(dirnameOs(p));
const join = (...p) => slash(joinOs(...p));
const resolve = (...p) => slash(resolveOs(...p));
const realpathSync = (p) => slash(realpathOs(p));
const fileURLToPath = (u) => slash(fileURLToPathOs(u));

const COPYRIGHT = /^\s*(?:copyright\b(?!\s+(?:holders?|notice|owners?|law)\b)|\(c\)\s|©)/i;
const RESERVED = /^\s*all rights reserved\.?\s*$/i;
const LICENSE_FILE = /^(?:licen[cs]e|copying)(?:[.-].*)?$/i;
const NOTICE_FILE = /^notice(?:\..*)?$/i;
const STYLE_FILE = /\.(?:svelte|scss|sass|css)$/;
const STYLE_IMPORT = /@(?:use|import|forward)\s+(['"])([^'"]+)\1/g;
const COPIED = /node_modules\/((?:@[\w.-]+\/)?[\w.-]+)/g;
const URL_SCHEME = /^[a-z][a-z0-9+.-]*:\/\//i;

const [outFile, buildDir] = process.argv.slice(2);
if (!outFile || !buildDir) {
  console.error('Usage: bun npm-dependencies.mjs <output file> <build output directory>');
  process.exit(1);
}

const moduleDir = realpathSync(process.cwd());
const result = {};
const errors = [];
const listed = new Set();
const scanned = new Set();
// Packages named in a sourcemap but not installed -> the sourcemap naming them
const notInstalled = new Map();

// 1. Sourcemaps
for (const map of files(resolve(buildDir)).filter((f) => f.endsWith('.map'))) {
  const json = JSON.parse(readFileSync(map, 'utf8'));
  for (const source of json.sources ?? []) {
    const path = sourcePath(map, json.sourceRoot, source);
    const pkgDir = path && packageDirOf(path);
    if (pkgDir) {
      addPackage(pkgDir);
    }
    else if (path === null || (path.includes('/node_modules/') && !existsSync(path))) {
      const name = packageNameIn(source);
      if (name) notInstalled.set(name, map);
    }
    else if (STYLE_FILE.test(path) && existsSync(path)) {
      scanStyles(path);
    }
  }
}

// 3. Files copied from node_modules by the build script
const copiedDirs = [];
for (const script of ['build.mjs', 'build.js'].map((f) => join(moduleDir, f)).filter(existsSync)) {
  for (const [, name] of readFileSync(script, 'utf8').matchAll(COPIED)) {
    const dir = locate(name, moduleDir);
    if (dir) {
      copiedDirs.push(dir);
      addWithDependencies(dir);
    }
    else {
      errors.push(`${script} copies from ${name}, which is not installed`);
    }
  }
}

// The evidence is complete only if every emitted script and stylesheet has a sourcemap
checkSourcemaps();

// A sourcemap a prebuilt package brought along may point into its authors' tree; the package is
// still ours if it is installed. If it is not, its code came inside another package's prebuilt
// bundle (core-js-pure inside @svgdotjs/svg.js) and must be declared as embedded content.
const embedded = declaredEmbedded();
for (const [name, map] of notInstalled) {
  const installed = locate(name, moduleDir);
  if (installed) {
    addPackage(installed);
  }
  else if (!embedded.has(name)) {
    errors.push(`${name} is bundled into ${map} but not installed - declare it as embedded `
      + 'content in the module\'s META-INF/DEPENDENCIES-EMBEDDED.spdx.json');
  }
}

if (errors.length) {
  console.error(`Unable to list the npm dependencies:\n  - ${[...new Set(errors)].join('\n  - ')}`);
  process.exit(1);
}

const sorted = Object.fromEntries(Object.keys(result).sort().map((k) => [k, result[k]]));
mkdirSync(dirname(outFile), { recursive: true });
writeFileSync(outFile, JSON.stringify(sorted, null, 2) + '\n');

// An emitted script or stylesheet without a sourcemap is fine only as the unminified twin of a
// mapped .min file, or as an unchanged copy of a file of a copied package or of the module's own
// sources (the vendored PDF.js viewer's CSS)
function checkSourcemaps() {
  const out = files(resolve(buildDir));
  const outSet = new Set(out);
  const copies = new Map();
  for (const f of [...copiedDirs.flatMap((d) => files(d)), ...files(moduleDir, true)]) {
    const name = f.slice(f.lastIndexOf('/') + 1);
    if (!copies.has(name)) copies.set(name, []);
    copies.get(name).push(f);
  }
  for (const file of out.filter((f) => /\.(?:m?js|css)$/.test(f))) {
    if (outSet.has(`${file}.map`) || outSet.has(file.replace(/\.[^.]+$/, '.map'))) continue;
    const min = file.replace(/\.([^.]+)$/, '.min.$1');
    if (min !== file && (outSet.has(`${min}.map`) || outSet.has(min.replace(/\.[^.]+$/, '.map')))) {
      continue;
    }
    const name = file.slice(file.lastIndexOf('/') + 1);
    const content = readFileSync(file);
    if ((copies.get(name) ?? []).some((c) => readFileSync(c).equals(content))) continue;
    errors.push(`${file} has no sourcemap, so what it contains cannot be listed - enable `
      + 'sourcemaps in the build');
  }
}

function addPackage(dir) {
  if (listed.has(dir)) return false;
  listed.add(dir);
  const pkg = JSON.parse(readFileSync(join(dir, 'package.json'), 'utf8'));
  if (!pkg.name?.startsWith('@types/') && !pkg.private) {
    result[`${pkg.name}@${pkg.version}`] = describe(dir, pkg);
  }
  return true;
}

// A copied prebuilt file may contain its package's dependencies and peers, never its optional
// (platform) packages
function addWithDependencies(dir) {
  if (!addPackage(dir)) return;
  const pkg = JSON.parse(readFileSync(join(dir, 'package.json'), 'utf8'));
  for (const name of Object.keys({ ...pkg.peerDependencies, ...pkg.dependencies }).sort()) {
    const dependency = locate(name, dir);
    if (dependency) addWithDependencies(dependency);
    else if (name in (pkg.dependencies ?? {})) {
      errors.push(`${pkg.name}: dependency ${name} is not installed`);
    }
  }
}

// 2. The packages a style file imports, following relative imports and linked packages' files
function scanStyles(file) {
  if (scanned.has(file)) return;
  scanned.add(file);
  // A file of an installed package: the package ships, what it imports is its own business
  const owner = packageDirOf(realpathSync(file)) ?? packageDirOf(file);
  if (owner) {
    addPackage(owner);
    return;
  }
  for (const [, , raw] of readFileSync(file, 'utf8').matchAll(STYLE_IMPORT)) {
    const tilde = raw.startsWith('~');
    const spec = raw.replace(/^~/, '');
    if (spec.startsWith('sass:') || URL_SCHEME.test(spec) || spec.startsWith('/')) continue;
    // As Sass resolves: relative to the importing file first ('mixins' is _mixins.scss next to
    // it), only then as a package
    const relative = tilde ? undefined : styleFile(resolve(dirname(file), spec));
    if (relative) {
      scanStyles(relative);
      continue;
    }
    if (spec.startsWith('.')) {
      errors.push(`${file} imports ${spec}, which does not exist`);
      continue;
    }
    const name = spec.startsWith('@') ? spec.split('/').slice(0, 2).join('/') : spec.split('/')[0];
    // From the importing file, as Node does; then from the module, whose node_modules Sass uses
    // as a load path (an import in a linked package's file may rely on it)
    const dir = locate(name, dirname(file)) ?? locate(name, moduleDir);
    if (!dir) {
      errors.push(`${file} imports styles from ${name}, which is not installed`);
    }
    else if (dir.includes('/node_modules/')) {
      addPackage(dir);
    }
    else {
      // A linked workspace package (our own code): its imported file may import further packages
      const target = styleFile(join(dir, spec.slice(name.length)));
      if (target) scanStyles(target);
    }
  }
}

// How Sass finds an imported file: as given, with an extension, as a partial, or as an index
function styleFile(path) {
  const base = path.replace(/\/$/, '');
  const name = base.slice(base.lastIndexOf('/') + 1);
  const dir = base.slice(0, base.lastIndexOf('/'));
  const candidates = [base, `${base}.scss`, `${base}.sass`, `${base}.css`,
    `${dir}/_${name}.scss`, `${dir}/_${name}.sass`, `${base}/_index.scss`, `${base}/index.scss`];
  return candidates.find((c) => existsSync(c) && statSync(c).isFile());
}

// The local path of a sourcemap source, or null for a URL that points nowhere local
// (webpack://...): esbuild writes paths relative to the map, Sass absolute file: URLs
function sourcePath(map, sourceRoot, source) {
  if (source.startsWith('file:')) return fileURLToPath(source);
  if (URL_SCHEME.test(source)) return null;
  return resolve(dirname(map), sourceRoot ?? '', source);
}

// The installed package a path belongs to: the innermost node_modules/<name> with a package.json
function packageDirOf(path) {
  const i = path.lastIndexOf('/node_modules/');
  if (i < 0) return null;
  const rest = path.slice(i + '/node_modules/'.length).split('/');
  const dir = path.slice(0, i) + '/node_modules/'
    + (rest[0].startsWith('@') ? `${rest[0]}/${rest[1]}` : rest[0]);
  return existsSync(join(dir, 'package.json')) ? realpathSync(dir) : null;
}

function packageNameIn(source) {
  const i = source.lastIndexOf('node_modules/');
  if (i < 0) return null;
  const rest = source.slice(i + 'node_modules/'.length).split('/');
  return rest[0].startsWith('@') ? `${rest[0]}/${rest[1]}` : rest[0];
}

// The npm packages the module declares as embedded content, by their purls
function declaredEmbedded() {
  const file = resolve(moduleDir, '../resources/META-INF/DEPENDENCIES-EMBEDDED.spdx.json');
  const names = new Set();
  if (!existsSync(file)) return names;
  for (const p of JSON.parse(readFileSync(file, 'utf8')).packages ?? []) {
    for (const ref of p.externalRefs ?? []) {
      const m = ref.referenceLocator?.match(/^pkg:npm\/([^@]+|%40[^@]+)(?:@.*)?$/);
      if (m) names.add(decodeURIComponent(m[1]));
    }
  }
  return names;
}

function files(dir, skipNodeModules = false) {
  if (!existsSync(dir)) return [];
  return readdirSync(dir, { withFileTypes: true }).flatMap((e) => {
    if (!e.isDirectory()) return [join(dir, e.name)];
    return skipNodeModules && e.name === 'node_modules' ? [] : files(join(dir, e.name), skipNodeModules);
  });
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
