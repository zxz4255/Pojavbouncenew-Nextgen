/* SPDX-License-Identifier: GPL-3.0-or-later */
// Browser integration test with a simulated LiquidBounce REST backend. It tests the
// real Svelte title and ClickGUI, not an Android WebView or a Minecraft server.
// Start Vite first, then: CHROMIUM_EXECUTABLE_PATH=/usr/bin/chromium npm run test:hello
import {chromium} from 'playwright';
import assert from 'node:assert/strict';
import {mkdir} from 'node:fs/promises';

const base = process.env.HELLO_TEST_BASE_URL || 'http://127.0.0.1:5173';
const executablePath = process.env.CHROMIUM_EXECUTABLE_PATH || undefined;
const screenshotDir = process.env.HELLO_TEST_OUTPUT;
if (screenshotDir) await mkdir(screenshotDir, {recursive: true});
const browser = await chromium.launch({headless: true, executablePath, args: ['--no-sandbox', '--disable-dev-shm-usage']});
const json = (route, payload, status = 200) => route.fulfill({status, contentType: 'application/json', body: JSON.stringify(payload)});
const noContent = route => route.fulfill({status: 204, body: ''});
const name = 'HelloScreen';
const keyBind = {boundKey: 'key.keyboard.unknown', action: 'Toggle', modifiers: []};

async function setup(state, width = 1280, height = 720) {
    const context = await browser.newContext({viewport: {width, height}, deviceScaleFactor: 1});
    const page = await context.newPage();
    const pageErrors = [];
    page.on('pageerror', error => pageErrors.push(error.message));
    await page.route('**/api/v1/**', async route => {
        const request = route.request();
        const url = new URL(request.url());
        const path = url.pathname.replace('/api/v1/client', '');
        if (path === '/theme/liquidbounce') return json(route, {colors: {accent: 0xff4677ff, tint: 0xff20232e}});
        if (path === '/localStorage/all') return request.method() === 'GET' ? json(route, {items: []}) : noContent(route);
        if (path === '/virtualScreen') {
            if (request.method() === 'POST') { state.acknowledgements++; return noContent(route); }
            return json(route, {name: state.screen, showingSplash: state.splash});
        }
        if (path === '/hello-screen') return json(route, {animationEnabled: state.enabled, showBuild: state.showBuild, build: '#174'});
        if (path === '/session') return json(route, {username: 'Test player', service: 'Offline', avatar: '', online: false});
        if (path === '/accounts') return json(route, []);
        if (path === '/update') return json(route, {});
        if (path === '/screen') { state.screenRequests++; return noContent(route); }
        if (path === '/modules') return json(route, [{name, category: 'Render', keyBind, enabled: state.enabled,
            description: 'Hello title animation', hidden: true, aliases: [], tag: null}]);
        if (path === '/modules/categories') return json(route, [{name: 'Render', icon: null}]);
        if (path === '/module/HelloScreen') return json(route, {name, category: 'Render', keyBind, enabled: state.enabled,
            description: 'Hello title animation', hidden: true, aliases: [], tag: null});
        if (path === '/modules/toggle') { state.enabled = !state.enabled; return noContent(route); }
        if (path === '/modules/settings') {
            const module = url.searchParams.get('name');
            if (request.method() === 'PUT') {
                const body = request.postDataJSON();
                state.showBuild = body.value.find(value => value.name === 'ShowBuild')?.value ?? state.showBuild;
                return noContent(route);
            }
            return json(route, module === name ? {name, valueType: 'TOGGLEABLE', value: [
                {name: 'ShowBuild', valueType: 'BOOLEAN', value: state.showBuild},
                {name: 'Build', valueType: 'TEXT', value: '#174'}
            ]} : {name: 'ClickGUI', valueType: 'TOGGLEABLE', value: [{name: 'Scale', valueType: 'FLOAT', value: 1}]});
        }
        if (path === '/window') return json(route, {width, height, scaledWidth: width, scaledHeight: height, scaleFactor: 2, guiScale: 2});
        if (path === '/info') return json(route, {os: 'Linux', gameVersion: 'test', clientVersion: 'test'});
        if (path === '/hud-editor' || path === '/typing') return noContent(route);
        return json(route, {});
    });
    await page.goto(`${base}/#/title`, {waitUntil: 'domcontentloaded'});
    await page.addStyleTag({content: "body { background: url('/backgrounds/background.png') center / cover fixed !important; }"});
    return {context, page, pageErrors};
}

async function inkPixels(page) {
    return await page.locator('.hello-layer canvas').evaluate(canvas => {
        const rgba = canvas.getContext('2d').getImageData(0, 0, canvas.width, canvas.height).data;
        let count = 0, subtitleRight = 0;
        for (let y = 0; y < canvas.height; y++) {
            for (let x = 0; x < canvas.width; x++) {
                if (rgba[(y * canvas.width + x) * 4 + 3] <= 5) continue;
                count++;
                // The main image ends at x=764; ink beyond it belongs only to the subtitle.
                if (x >= 764) subtitleRight++;
            }
        }
        return {count, subtitleRight, cornerAlpha: rgba[3]};
    });
}

try {
    const state = {screen: 'title', splash: true, enabled: true, showBuild: true, acknowledgements: 0, screenRequests: 0};
    const {context, page, pageErrors} = await setup(state);
    await page.locator('.title-screen').waitFor({state: 'visible', timeout: 20000});
    await page.locator('.hello-layer').waitFor({state: 'attached'});
    await page.waitForTimeout(350);
    assert.equal(await page.locator('.hello-layer canvas.visible').count(), 0, 'animation started beneath splash');
    assert.equal(state.acknowledgements, 0, 'acknowledged title before splash was gone');
    state.splash = false;
    await page.locator('.hello-layer canvas.visible').waitFor({state: 'visible', timeout: 10000});
    const start = Date.now();
    await page.waitForTimeout(450);
    const early = await inkPixels(page);
    assert(early.count > 100, `early strokes did not render (${early.count})`);
    assert.equal(early.subtitleRight, 0, 'subtitle appeared before the main handwriting finished');
    assert.equal(early.cornerAlpha, 0, 'canvas added a background');
    assert.equal(await page.locator('.hello-layer').evaluate(el => getComputedStyle(el).pointerEvents), 'none');
    assert(state.acknowledgements >= 1, 'mounted Web title did not acknowledge its route');
    if (screenshotDir) await page.screenshot({path: `${screenshotDir}/01-writing.png`});
    // Test that the transparent overlay never steals clicks from the actual Web menu.
    await page.getByText('Singleplayer', {exact: true}).click();
    assert.equal(state.screenRequests, 1, 'title button did not receive a click through the overlay');
    await page.waitForTimeout(1850);
    const middle = await inkPixels(page);
    assert(middle.count > early.count, 'handwriting did not progress');
    assert(middle.subtitleRight > 100, 'subtitle did not start after the main handwriting');
    if (screenshotDir) await page.screenshot({path: `${screenshotDir}/02-subtitle.png`});
    await page.locator('.hello-layer .credit.visible').waitFor({state: 'attached', timeout: 5000});
    const duration = Date.now() - start;
    assert(duration >= 2400 && duration < 5000, `unexpected animation duration ${duration}ms`);
    assert.equal(await page.locator('.hello-layer .build').innerText(), '#174', 'build fixture was not displayed');
    await page.waitForTimeout(750);
    assert(parseFloat(await page.locator('.credit').evaluate(el => getComputedStyle(el).opacity)) > .95, 'credit did not fade in');
    if (screenshotDir) await page.screenshot({path: `${screenshotDir}/03-credit.png`});

    // The overlay component remains mounted across router changes, so returning to title must explicitly replay it.
    state.screen = 'clickgui';
    await page.evaluate(() => { location.hash = '#/clickgui'; });
    await page.locator('.hello-layer').waitFor({state: 'detached', timeout: 10000});
    state.screen = 'title';
    await page.evaluate(() => { location.hash = '#/title'; });
    await page.locator('.hello-layer canvas.visible').waitFor({state: 'visible', timeout: 10000});
    await page.waitForTimeout(350);
    assert((await inkPixels(page)).count > 0, 'welcome animation did not replay after returning to title');
    state.screen = 'clickgui';
    await page.evaluate(() => { location.hash = '#/clickgui'; });
    await page.locator('.hello-layer').waitFor({state: 'detached', timeout: 10000});
    await page.locator('.panel .expand-toggle').click({timeout: 10000});
    const helloModule = page.locator('.panel .module').first();
    await helloModule.locator('.expand-arrow').click();
    await helloModule.locator('.build-info').waitFor({state: 'visible'});
    assert.equal((await helloModule.locator('.build-info').innerText()).replace(/\s/g, ''), 'Build#174');
    assert.equal(await helloModule.locator('.build-info input').count(), 0, 'build number is editable');
    await helloModule.locator('.name').first().click();
    assert.equal(state.enabled, false, 'module switch did not disable animation');
    await page.waitForTimeout(250);
    assert.equal(await helloModule.locator('.name').first().evaluate(el => el.classList.contains('enabled')), false,
        'out-of-game module state was not refreshed in ClickGUI');
    await helloModule.locator('.switch-container').filter({hasText: /Show\s*Build/i}).click();
    await page.waitForTimeout(300);
    assert.equal(state.showBuild, false, 'ShowBuild setting did not update');
    state.screen = 'title';
    await page.evaluate(() => { location.hash = '#/title'; });
    await page.locator('.hello-layer .credit.visible').waitFor({state: 'attached', timeout: 10000});
    assert.equal(await page.locator('.hello-layer canvas.visible').count(), 0, 'disabled animation played');
    assert.equal(await page.locator('.hello-layer .build').count(), 0, 'disabled build label was shown');
    assert.deepEqual(pageErrors, [], `uncaught browser errors: ${pageErrors.join('; ')}`);
    await context.close();

    // A fresh WebView with the module disabled must immediately show the cut-out credit, not Hello.
    // If an Android first-launch splash status stays stale, the Web title still falls back
    // to the animation instead of leaving the entire overlay blank forever.
    const staleSplash = {screen: 'title', splash: true, enabled: true, showBuild: false, acknowledgements: 0, screenRequests: 0};
    const startupFallback = await setup(staleSplash, 800, 400);
    await startupFallback.page.locator('.hello-layer canvas.visible').waitFor({state: 'visible', timeout: 15000});
    assert(staleSplash.acknowledgements >= 1, 'mounted title was not acknowledged after splash timeout');
    assert.deepEqual(startupFallback.pageErrors, [], `uncaught startup fallback errors: ${startupFallback.pageErrors.join('; ')}`);
    await startupFallback.context.close();

    const disabled = {screen: 'title', splash: false, enabled: false, showBuild: false, acknowledgements: 0, screenRequests: 0};
    const mobile = await setup(disabled, 800, 400);
    await mobile.page.locator('.hello-layer .credit.visible').waitFor({state: 'attached', timeout: 20000});
    assert.equal(await mobile.page.locator('.hello-layer canvas.visible').count(), 0);
    assert.equal(await mobile.page.locator('.hello-layer .build').count(), 0);
    if (screenshotDir) await mobile.page.screenshot({path: `${screenshotDir}/04-mobile-disabled.png`});
    assert.deepEqual(mobile.pageErrors, [], `uncaught mobile viewport errors: ${mobile.pageErrors.join('; ')}`);
    await mobile.context.close();
    console.log(`Web title and ClickGUI tests passed. Animated ${duration}ms; ink pixels ${early.count} -> ${middle.count}.`);
} finally {
    await browser.close();
}
