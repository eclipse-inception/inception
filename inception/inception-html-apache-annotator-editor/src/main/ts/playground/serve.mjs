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

// Scroll-sync playground: bundles the real entry points of the external editor loader (with the
// ViewportSyncHub), the HTML document structure and the Apache Annotator editor, rebuilds them on
// every change, and serves them together with the scroll-sync scenario documents and the driver
// page (scrollsync.html). No INCEpTION backend is involved; the driver page stubs DIAM.
//
// Run from src/main/ts (after a module build has installed node_modules):
//
//   node playground/serve.mjs [--port 8642]
//
// then open http://localhost:8642/ and reload after editing the TypeScript.

import esbuild from 'esbuild';
import esbuildSvelte from 'esbuild-svelte';
import { sveltePreprocess } from 'svelte-preprocess';
import { sassPlugin } from 'esbuild-sass-plugin';
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const tsRoot = path.resolve(here, '..');
const modules = path.resolve(tsRoot, '../../../..');
const outdir = path.resolve(tsRoot, '../../../target/playground');
const docsDir = path.join(
    modules,
    'inception-test-scenarios/src/main/resources/de/tudarmstadt/ukp/inception/testscenarios/scrollsync'
);

const portArg = process.argv.indexOf('--port');
const port = portArg >= 0 ? Number(process.argv[portArg + 1]) : 8642;

const common = {
    bundle: true,
    sourcemap: true,
    minify: false,
    target: 'es2020',
    format: 'iife',
    logLevel: 'info',
    // The sibling modules' sources resolve their packages from their own node_modules first; this
    // module's node_modules is the fallback in case a sibling has not been built yet
    nodePaths: [path.join(tsRoot, 'node_modules')],
    alias: { svelte: 'svelte' },
};

const bundles = [
    {
        entryPoints: [path.join(modules, 'inception-external-editor/src/main/ts/src/main.ts')],
        outfile: path.join(outdir, 'ExternalEditor.js'),
        globalName: 'ExternalEditor',
    },
    {
        entryPoints: [path.join(modules, 'inception-io-html/src/main/ts/src/main.ts')],
        outfile: path.join(outdir, 'HtmlDocumentStructure.js'),
    },
    {
        // Same entry point and plugins as build.mjs; emits ApacheAnnotatorEditor.css alongside
        entryPoints: [path.join(tsRoot, 'src/main.ts')],
        outfile: path.join(outdir, 'ApacheAnnotatorEditor.js'),
        globalName: 'ApacheAnnotatorEditor',
        plugins: [
            sassPlugin(),
            esbuildSvelte({
                compilerOptions: { dev: true },
                preprocess: sveltePreprocess(),
                filterWarnings: (warning) => warning.code !== 'css-unused-selector',
            }),
        ],
    },
];

fs.mkdirSync(outdir, { recursive: true });
for (const options of bundles) {
    const context = await esbuild.context({ ...common, ...options });
    await context.watch();
}

const TYPES = {
    '.html': 'text/html; charset=utf-8',
    '.js': 'text/javascript; charset=utf-8',
    '.css': 'text/css; charset=utf-8',
    '.map': 'application/json',
};

const WORDS = (
    'lorem ipsum dolor sit amet consectetur adipiscing elit sed do eiusmod tempor incididunt ut ' +
    'labore et dolore magna aliqua ut enim ad minim veniam quis nostrud exercitation ullamco'
).split(' ');

/**
 * Generate an HTML document from a spec of space-separated tokens, in document order:
 *
 *   N[:p]     section with heading <h2 id="chN"> and p paragraphs (default 4)
 *   N.M[:p]   subsection with heading <h3 id="chN-M"> (any depth: N.M.K is <h4>, ...)
 *   N=L[:p]   force heading level L (e.g. 1.1=2 makes subsection 1.1 an <h2>, a top section)
 *   ~N[:p]    section whose heading has no id, so it has no stable key
 *   @lead[:p] paragraphs before the first heading, belonging to no section (default 3)
 *   @tail[:p] paragraphs after the last section, belonging to no section; the sections are then
 *             wrapped in a <div> so the tail stays outside them (default 3)
 *   xF        scale all paragraph lengths by F (e.g. x1.5 for a wordier translation)
 *
 * Every paragraph starts with "<label> §<section> ¶<n>:" so the panes can be compared by eye.
 */
function generate(spec, label) {
    let scale = 1;
    let lead = 0;
    let tail = 0;
    const sections = [];
    for (const token of spec.trim().split(/\s+/).filter(Boolean)) {
        let m;
        if ((m = token.match(/^x(\d+(?:\.\d+)?)$/))) scale = Number(m[1]);
        else if ((m = token.match(/^@lead(?::(\d+))?$/))) lead = Number(m[1] ?? 3);
        else if ((m = token.match(/^@tail(?::(\d+))?$/))) tail = Number(m[1] ?? 3);
        else if ((m = token.match(/^(~?)(\d+(?:\.\d+)*)(?:=([1-6]))?(?::(\d+))?$/)))
            sections.push({
                keyed: !m[1],
                num: m[2],
                level: m[3] && Number(m[3]),
                paragraphs: Number(m[4] ?? 4),
            });
        else throw new Error(`Unknown token [${token}]`);
    }

    let seed = 0;
    const paragraph = (name, i) => {
        // Lengths vary per paragraph (deterministically), so sections differ in height
        const n = Math.round((40 + ((seed * 37) % 50)) * scale);
        const words = Array.from({ length: n }, (_, k) => WORDS[(seed * 7 + k) % WORDS.length]);
        seed++;
        return `<p>${label} §${name} ¶${i}: ${words.join(' ')}.</p>`;
    };

    const out = [`<html><head><meta charset="utf-8"><title>${label}: ${spec}</title></head><body>`];
    for (let i = 1; i <= lead; i++) out.push(paragraph('lead', i));
    if (tail) out.push('<div>');
    for (const s of sections) {
        const level = s.level || Math.min(6, 1 + s.num.split('.').length);
        // Dashes, not dots: an id with dots would need escaping in any #id selector
        const id = s.keyed ? ` id="ch${s.num.replaceAll('.', '-')}"` : '';
        out.push(`<h${level}${id}>Section ${s.num}${s.keyed ? '' : ' (no id)'}</h${level}>`);
        for (let i = 1; i <= s.paragraphs; i++) out.push(paragraph(s.num, i));
    }
    if (tail) out.push('</div>');
    for (let i = 1; i <= tail; i++) out.push(paragraph('tail', i));
    out.push('</body></html>');
    return out.join('\n');
}

// / -> the driver page, /dist/* -> the bundles, /docs/* -> the scenario documents, /gen?spec=...
// -> a generated document. Files are read on every request, so edits to the driver page or the
// documents also show up on reload.
const routes = [
    ['/dist/', outdir],
    ['/docs/', docsDir],
];

http.createServer((req, res) => {
    const url = new URL(req.url, 'http://localhost');
    let file;
    if (url.pathname === '/' || url.pathname === '/scrollsync.html') {
        file = path.join(here, 'scrollsync.html');
    } else if (url.pathname === '/gen') {
        try {
            const html = generate(url.searchParams.get('spec') ?? '', url.searchParams.get('label') ?? '');
            res.writeHead(200, { 'Content-Type': TYPES['.html'], 'Cache-Control': 'no-store' });
            res.end(html);
        } catch (e) {
            res.writeHead(400, { 'Content-Type': 'text/plain' });
            res.end(e.message);
        }
        return;
    } else if (url.pathname === '/docs/') {
        const names = fs.readdirSync(docsDir).filter((n) => n.endsWith('.html'));
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify(names));
        return;
    } else {
        for (const [prefix, dir] of routes) {
            if (url.pathname.startsWith(prefix)) {
                const candidate = path.resolve(dir, decodeURIComponent(url.pathname.slice(prefix.length)));
                if (candidate.startsWith(dir + path.sep)) file = candidate;
            }
        }
    }

    if (!file || !fs.existsSync(file) || !fs.statSync(file).isFile()) {
        res.writeHead(404);
        res.end('Not found');
        return;
    }

    res.writeHead(200, {
        'Content-Type': TYPES[path.extname(file)] ?? 'application/octet-stream',
        'Cache-Control': 'no-store',
    });
    fs.createReadStream(file).pipe(res);
}).listen(port, 'localhost', () => {
    console.log(`Scroll-sync playground on http://localhost:${port}/`);
});
