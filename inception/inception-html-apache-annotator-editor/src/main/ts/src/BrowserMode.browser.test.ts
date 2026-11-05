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

// Smoke test for the browser test project (see vitest.config.js): if this fails, the setup is
// broken, not the code under test.

import { describe, it, expect, afterEach } from 'vitest';
import { calculateEndOffset } from '@inception-project/inception-js-api';

describe('browser test setup', () => {
    afterEach(() => {
        document.body.replaceChildren();
    });

    it('runs in Chromium, not jsdom', () => {
        expect(navigator.userAgent).toMatch(/Chrome/);
        expect(navigator.userAgent).not.toMatch(/jsdom/);
    });

    it('lays out elements', () => {
        const box = document.createElement('div');
        box.style.cssText = 'width: 200px; font: 16px/1.5 serif;';
        box.textContent = 'Lorem ipsum dolor sit amet, consectetur adipiscing elit. '.repeat(10);
        document.body.appendChild(box);

        // jsdom reports 0 for all of these
        expect(box.getBoundingClientRect().width).toBe(200);
        expect(box.offsetHeight).toBeGreaterThan(24); // the text wraps onto several lines

        box.style.width = '400px';
        const wide = box.offsetHeight;
        box.style.width = '100px';
        expect(box.offsetHeight).toBeGreaterThan(wide);
    });

    it('scrolls a container within its scroll range', () => {
        const container = document.createElement('div');
        container.style.cssText = 'height: 100px; overflow: auto;';
        const content = document.createElement('div');
        content.style.height = '1000px';
        container.appendChild(content);
        document.body.appendChild(container);

        container.scrollTop = 5000;
        expect(container.scrollTop).toBe(container.scrollHeight - container.clientHeight);
    });

    it('loads inception-js-api as native browser modules', () => {
        // The browser rejects type-only names imported or re-exported as values, which jsdom and
        // the esbuild bundle tolerate; importing the package at all is the check
        const root = document.createElement('div');
        root.textContent = 'abc';
        expect(calculateEndOffset(root, root)).toBe(3);
    });
});
