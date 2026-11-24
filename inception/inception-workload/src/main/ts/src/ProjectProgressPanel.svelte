<script lang="ts">
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

    import { onMount, onDestroy, tick } from 'svelte';
    import dayjs from 'dayjs';
    // Import only the parts of ECharts the chart uses to keep the bundle small
    import * as echarts from 'echarts/core';
    import { LineChart } from 'echarts/charts';
    import {
        DataZoomComponent,
        GridComponent,
        LegendComponent,
        MarkLineComponent,
        ToolboxComponent,
        TooltipComponent,
    } from 'echarts/components';
    import { LegacyGridContainLabel } from 'echarts/features';
    import { CanvasRenderer } from 'echarts/renderers';

    echarts.use([
        LineChart,
        GridComponent,
        TooltipComponent,
        LegendComponent,
        ToolboxComponent,
        DataZoomComponent,
        MarkLineComponent,
        // grid.containLabel needs this since ECharts 6
        LegacyGridContainLabel,
        CanvasRenderer,
    ]);

    export let dataUrl: string;
    export let devMode: boolean = false;

    // Known document states (ordering for display)
    const STATE_ORDER = [
        'NEW',
        'ANNOTATION_IN_PROGRESS',
        'ANNOTATION_FINISHED',
        'CURATION_IN_PROGRESS',
        'CURATION_FINISHED',
    ];

    // Colors taken from `SourceDocumentState.getColor()` in the Java model
    const STATE_COLORS: Record<string, string> = {
        NEW: '#00000000',
        ANNOTATION_IN_PROGRESS: '#FFBF0080',
        ANNOTATION_FINISHED: '#00BF00FF',
        CURATION_IN_PROGRESS: '#FF00BF80',
        CURATION_FINISHED: '#3232FFFF',
    };

    // Default stacking order (bottom -> top). Exclude NEW from stacking.
    const STACK_ORDER = [
        'CURATION_FINISHED',
        'CURATION_IN_PROGRESS',
        'ANNOTATION_FINISHED',
        'ANNOTATION_IN_PROGRESS',
    ];

    // Component state
    let snapshots: Array<{ day: string; counts: Record<string, number> }> = [];
    let loading = false;
    let error: string | null = null;
    let range: 'max' | 'year' | 'quarter' | 'month' | 'week' = 'max';
    let projection: 'none' | 'week' | 'month' | 'quarter' | 'year' = 'quarter';
    let metric: 'documents' | 'tokens' | 'sentences' = 'documents';
    let chartDiv: HTMLDivElement | null = null;
    let chart: echarts.ECharts | null = null;
    let resizeObserver: ResizeObserver | null = null;
    let _observedEl: Element | null = null;
    let _resizeTimer: number | null = null;

    // Test mode state
    let testModeEnabled = false;
    let simulatedToday = dayjs().format('YYYY-MM-DD');

    function scheduleResize() {
        if (_resizeTimer) {
            clearTimeout(_resizeTimer);
            _resizeTimer = null;
        }
        _resizeTimer = window.setTimeout(() => {
            try {
                chart?.resize();
            } catch (e) {
                /* ignore */
            }
            _resizeTimer = null;
        }, 120);
    }

    async function load() {
        if (!dataUrl) return;
        loading = true;
        error = null;
        snapshots = [];

        try {
            let url = dataUrl;
            if (range !== 'max') {
                const from = computeFrom(range);
                const sep = url.indexOf('?') === -1 ? '?' : '&';
                url = `${url}${sep}from=${encodeURIComponent(from)}`;
            }

            if (projection && projection !== 'none') {
                const to = computeTo(projection);
                if (to) {
                    const sep = url.indexOf('?') === -1 ? '?' : '&';
                    url = `${url}${sep}to=${encodeURIComponent(to)}`;
                }
            }

            // Add simulated "now" parameter if test mode is enabled
            if (testModeEnabled && simulatedToday) {
                const nowParam = dayjs(simulatedToday).toISOString();
                const sep = url.indexOf('?') === -1 ? '?' : '&';
                url = `${url}${sep}now=${encodeURIComponent(nowParam)}`;
            }

            if (metric && metric !== 'documents') {
                const sep = url.indexOf('?') === -1 ? '?' : '&';
                url = `${url}${sep}metric=${encodeURIComponent(metric.toUpperCase())}`;
            }

            const res = await fetch(url);
            if (!res.ok) throw new Error(`${res.status} ${res.statusText}`);
            const json = await res.json();
            // Expecting an array of { day: string, counts: { <STATE>: number } }
            snapshots = json || [];
        } catch (e: any) {
            error = e?.message ?? String(e);
        } finally {
            loading = false;
        }
    }

    $: if (snapshots && snapshots.length > 0) {
        scheduleRender();
    }

    async function scheduleRender() {
        await tick();
        renderChart();
    }

    onMount(() => {
        load();

        // Setup ResizeObserver to auto-resize chart when container changes size
        try {
            if (typeof ResizeObserver !== 'undefined') {
                resizeObserver = new ResizeObserver(() => scheduleResize());
                if (chartDiv) {
                    resizeObserver.observe(chartDiv);
                    _observedEl = chartDiv;
                }
            }
        } catch (e) {
            // ignore - platform may not support ResizeObserver
        }

        // Fallback: listen to window resize events
        window.addEventListener('resize', scheduleResize);
    });

    // reload when dataUrl changes
    $: if (dataUrl) {
        load();
    }

    function computeFrom(range: string) {
        const now = dayjs();
        switch (range) {
            case 'year':
                return now.subtract(1, 'year').toISOString();
            case 'quarter':
                return now.subtract(3, 'month').toISOString();
            case 'month':
                return now.subtract(1, 'month').toISOString();
            case 'week':
                return now.subtract(1, 'week').toISOString();
            default:
                return '';
        }
    }

    function computeTo(projection: string) {
        const now = dayjs();
        switch (projection) {
            case 'year':
                return now.add(1, 'year').toISOString();
            case 'quarter':
                return now.add(3, 'month').toISOString();
            case 'month':
                return now.add(1, 'month').toISOString();
            case 'week':
                return now.add(1, 'week').toISOString();
            default:
                return '';
        }
    }

    function prepareChartData() {
        // Normalize dates to YYYY-MM-DD and build continuous date array between first and last day
        const days = snapshots.map((s) => dayjs(s.day).startOf('day'));
        const sorted = days.sort((a, b) => a.valueOf() - b.valueOf());
        if (sorted.length === 0) return { dateArr: [], series: [] };
        const start = sorted[0];
        const end = sorted[sorted.length - 1];
        const dateArr: string[] = [];
        for (let d = start; d.isBefore(end) || d.isSame(end); d = d.add(1, 'day')) {
            dateArr.push(d.format('YYYY-MM-DD'));
        }

        // build a map from day -> counts
        const map = new Map<string, Record<string, number>>();
        snapshots.forEach((s) => {
            map.set(dayjs(s.day).format('YYYY-MM-DD'), s.counts || {});
        });
        // Backward-fill all states per date and compute per-state arrays plus a total
        const allStates = STATE_ORDER.slice();
        const perStateData: Record<string, number[]> = {};
        allStates.forEach((s) => (perStateData[s] = new Array(dateArr.length).fill(0)));

        // First pass: place snapshot values at their dates
        for (let i = 0; i < dateArr.length; i++) {
            const counts = map.get(dateArr[i]);
            if (counts) {
                for (const s of allStates) {
                    if (typeof counts[s] === 'number') {
                        perStateData[s][i] = counts[s];
                    }
                }
            }
        }

        // Second pass: backward-fill from each snapshot to the previous one
        for (const s of allStates) {
            let currentValue = 0;
            for (let i = dateArr.length - 1; i >= 0; i--) {
                const counts = map.get(dateArr[i]);
                if (counts && typeof counts[s] === 'number') {
                    currentValue = counts[s];
                }
                perStateData[s][i] = currentValue;
            }
        }

        // Compute totals
        const totalArr: number[] = [];
        for (let i = 0; i < dateArr.length; i++) {
            let total = 0;
            for (const s of allStates) {
                total += perStateData[s][i];
            }
            totalArr.push(total);
        }

        // Use STACK_ORDER (bottom -> top) but ensure entries exist in STATE_ORDER and exclude NEW
        const stackOrder = STACK_ORDER.filter((s) => s !== 'NEW' && STATE_ORDER.includes(s));

        // split into past (stacked area) and future (line-only) parts
        // Use simulated today if test mode is enabled, otherwise use actual today
        const cutoff = testModeEnabled
            ? dayjs(simulatedToday).startOf('day')
            : dayjs().startOf('day');
        let lastPastIndex = -1;
        for (let i = 0; i < dateArr.length; i++) {
            const d = dayjs(dateArr[i]);
            if (d.isSame(cutoff) || d.isBefore(cutoff)) {
                lastPastIndex = i;
            }
        }

        const pastSeries: any[] = [];
        const futureSeries: any[] = [];

        for (const state of stackOrder) {
            const color = STATE_COLORS[state] || '#888888';
            // past data: values up to lastPastIndex, null afterwards (so stacking stops cleanly)
            const pastData = perStateData[state].map((v, idx) => (idx <= lastPastIndex ? v : null));
            pastSeries.push({
                name: state,
                type: 'line',
                stack: 'total',
                areaStyle: {},
                emphasis: { focus: 'series' },
                data: pastData,
                lineStyle: { color },
                itemStyle: { color },
                z: 10,
            });

            // future data: include lastPastIndex for line continuity, starts rendering after
            const futureData: Array<number | null> = new Array(dateArr.length).fill(null);

            // Ensure continuity: set the value at lastPastIndex to match the past data
            if (lastPastIndex >= 0) {
                futureData[lastPastIndex] = perStateData[state][lastPastIndex];
            }

            // gather indices where we have an explicit snapshot value for this state
            const knownIndices: number[] = [];
            for (let i = 0; i < dateArr.length; i++) {
                const counts = map.get(dateArr[i]);
                if (counts && typeof counts[state] === 'number') {
                    knownIndices.push(i);
                }
            }

            // Start from lastPastIndex + 1 for the remaining future points
            for (let i = lastPastIndex + 1; i < dateArr.length; i++) {
                // find bounding known indices
                let left = -1;
                let right = -1;
                for (let k = 0; k < knownIndices.length; k++) {
                    const ki = knownIndices[k];
                    if (ki <= i) left = ki;
                    if (ki >= i) {
                        right = ki;
                        break;
                    }
                }

                // Use step interpolation: hold value from left until we reach the next known point
                if (left >= 0) {
                    futureData[i] = perStateData[state][left];
                } else if (right >= 0) {
                    // only right known (rare) -> use right
                    futureData[i] = perStateData[state][right];
                } else {
                    futureData[i] = null;
                }
            }

            futureSeries.push({
                name: state + ' (future)',
                type: 'line',
                stack: 'future', // Use separate stack to avoid duplication with past at boundary
                data: futureData,
                showSymbol: false,
                lineStyle: { color, opacity: 0.5 },
                itemStyle: { color },
                connectNulls: true,
                areaStyle: { opacity: 0 },
                z: 20,
            });
        }

        // past total (for visual reference) and future total (line only)
        const pastTotal = totalArr.map((v, idx) => (idx <= lastPastIndex ? v : null));
        const futureTotal = totalArr.map((v, idx) => (idx >= lastPastIndex ? v : null));

        // total past: we still want a visible line on top of areas for past
        const totalPastSeries = {
            name: 'TOTAL',
            type: 'line',
            data: pastTotal,
            lineStyle: { width: 2, color: '#000' },
            itemStyle: { color: '#000' },
            showSymbol: false,
            emphasis: { focus: 'series' },
            z: 1,
        };

        const totalFutureSeries = {
            name: 'TOTAL',
            type: 'line',
            data: futureTotal,
            lineStyle: { width: 2, color: '#000', opacity: 0.5 },
            itemStyle: { color: '#000' },
            showSymbol: false,
            emphasis: { focus: 'series' },
            z: 2,
        };

        // vertical 'Today' mark line as separate invisible series with markLine
        const todayStr = testModeEnabled ? simulatedToday : dayjs().format('YYYY-MM-DD');
        const todayMarkerSeries = {
            name: 'TodayMarker',
            type: 'line',
            data: Array(dateArr.length).fill(null),
            markLine: {
                silent: true,
                data: [{ xAxis: todayStr }],
                symbol: ['none', 'diamond'],
                label: {
                    formatter: testModeEnabled ? `Today (simulated)` : 'Today',
                    position: 'end',
                },
                lineStyle: { color: '#000', type: 'dashed', width: 1 },
            },
            z: 15,
        };

        const series = [
            totalPastSeries,
            totalFutureSeries,
            ...pastSeries,
            ...futureSeries,
            todayMarkerSeries,
        ];

        return { dateArr, series, stackOrder };
    }

    function renderChart() {
        if (!chartDiv) return;
        const { dateArr, series, stackOrder } = prepareChartData();
        const displayStates = stackOrder || STATE_ORDER.filter((s) => s !== 'NEW');

        // If an existing chart instance is attached to a different DOM node, dispose it
        if (chart) {
            try {
                const dom = (chart as any).getDom?.();
                if (dom && dom !== chartDiv) {
                    chart.dispose();
                    chart = null;
                }
            } catch (e) {
                // ignore
            }
        }

        if (!chart) {
            chart = echarts.init(chartDiv);
        }

        const option = {
            tooltip: {
                trigger: 'axis',
                formatter: (params: any) => {
                    if (!params || params.length === 0) return '';
                    const date = params[0].axisValue;
                    let result = `${date}<br/>`;

                    // Group series by base name, picking non-null value from past or future
                    const stateValues = new Map<string, { marker: string; value: number }>();
                    for (const p of params) {
                        let baseName = p.seriesName;
                        if (baseName === 'TodayMarker') continue;

                        if (p.value != null && !stateValues.has(baseName)) {
                            stateValues.set(baseName, {
                                marker: p.marker,
                                value: p.value,
                            });
                        }
                    }

                    // Display in order
                    for (const [name, data] of stateValues) {
                        result += `${data.marker} ${name}: ${data.value}<br/>`;
                    }
                    return result;
                },
            },
            // place legend at top to avoid overlapping with the slider
            legend: { data: [...displayStates, 'TOTAL'], top: 8 },
            toolbox: { feature: { saveAsImage: {}, restore: {} }, right: 10 },
            // reserve space via grid so slider and legend don't overlap
            grid: {
                left: '3%',
                right: '3%',
                top: 50,
                bottom: 50,
                containLabel: true,
            },
            dataZoom: [
                { type: 'inside', xAxisIndex: 0 },
                { type: 'slider', xAxisIndex: 0, bottom: 8 },
            ],
            xAxis: { type: 'category', boundaryGap: false, data: dateArr },
            yAxis: {
                type: 'value',
                name: metric.charAt(0).toUpperCase() + metric.slice(1),
                nameLocation: 'middle',
                nameGap: 50,
            },
            color: [...displayStates.map((s) => STATE_COLORS[s] || '#888888'), '#000000'],
            series,
        };

        chart.setOption(option, { replaceMerge: ['series'] });
        // ensure correct sizing after mount
        try {
            chart.resize();
        } catch (e) {
            /* ignore */
        }
    }

    onDestroy(() => {
        if (chart) {
            chart.dispose();
            chart = null;
        }
        if (resizeObserver) {
            try {
                if (_observedEl) {
                    resizeObserver.unobserve(_observedEl);
                    _observedEl = null;
                }
                resizeObserver.disconnect();
            } catch (e) {
                /* ignore */
            }
            resizeObserver = null;
        }
        window.removeEventListener('resize', scheduleResize);
        if (_resizeTimer) {
            clearTimeout(_resizeTimer);
            _resizeTimer = null;
        }
    });

    // If the container disappears (e.g. during loading) dispose the instance to avoid stale DOM refs
    $: if (!chartDiv && chart) {
        chart.dispose();
        chart = null;
    }

    export function formatDate(iso?: string) {
        return iso ? dayjs(iso).format('YYYY-MM-DD') : '';
    }
</script>

<div class="progress-panel">
    <div class="d-flex flex-row align-items-center mb-2">
        <label for="range-select" class="me-2">Range</label>
        <select id="range-select" class="form-select" bind:value={range} on:change={load}>
            <option value="week">Week</option>
            <option value="month">Month</option>
            <option value="quarter">Quarter</option>
            <option value="year">Year</option>
            <option value="max">Max</option>
        </select>

        <label for="projection-select" class="ms-3 me-2">Projection</label>
        <select id="projection-select" class="form-select" bind:value={projection} on:change={load}>
            <option value="week">Week</option>
            <option value="month">Month</option>
            <option value="quarter">Quarter</option>
            <option value="year">Year</option>
            <option value="none">None</option>
        </select>

        <label for="metric-select" class="ms-3 me-2">Metric</label>
        <select
            id="metric-select"
            class="form-select"
            bind:value={metric}
            on:change={load}
            title="Documents counts one per doc; Tokens/Sentences weight each doc by its count in the search index (unindexed and deleted docs contribute zero)."
        >
            <option value="documents">Documents</option>
            <option value="tokens">Tokens</option>
            <option value="sentences">Sentences</option>
        </select>
    </div>

    {#if devMode}
        <div
            class="d-flex flex-row align-items-center mb-2 p-2 bg-warning bg-opacity-10 border border-warning rounded"
        >
            <label class="form-check-label me-3">
                <input
                    type="checkbox"
                    class="form-check-input me-1"
                    bind:checked={testModeEnabled}
                    on:change={load}
                />
                Test Mode
            </label>
            {#if testModeEnabled}
                <label for="simulated-today" class="me-2">Simulated Today:</label>
                <input
                    type="date"
                    id="simulated-today"
                    class="form-control"
                    style="width: auto;"
                    bind:value={simulatedToday}
                    on:change={load}
                />
                <button
                    class="btn btn-sm btn-outline-secondary ms-2"
                    on:click={() => {
                        simulatedToday = dayjs().format('YYYY-MM-DD');
                        load();
                    }}
                >
                    Reset
                </button>
                <span class="ms-3 badge bg-warning text-dark">Simulating: {simulatedToday}</span>
            {/if}
        </div>
    {/if}
    <div class="chart-wrapper" style="height:480px; width:100%; margin-bottom:0.5rem;">
        <div bind:this={chartDiv} class="chart-canvas" style="height:100%; width:100%;"></div>

        {#if loading || error || snapshots.length === 0}
            <div class="chart-overlay">
                {#if loading}
                    <div class="spinner-border text-muted" role="status">
                        <span class="visually-hidden">Loading...</span>
                    </div>
                {:else if error}
                    <div class="text-danger">Error: {error}</div>
                {:else}
                    <div class="text-muted">No progress data</div>
                {/if}
            </div>
        {/if}
    </div>
</div>

<style>
    .progress-panel {
        padding: 0.5rem;
    }
    .text-danger {
        color: #b00020;
    }
    .chart-wrapper {
        position: relative;
    }
    .chart-overlay {
        position: absolute;
        left: 0;
        top: 0;
        right: 0;
        bottom: 0;
        display: flex;
        align-items: center;
        justify-content: center;
        pointer-events: none;
    }
</style>
