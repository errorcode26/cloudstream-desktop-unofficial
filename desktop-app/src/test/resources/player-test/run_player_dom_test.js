/**
 * Headless Node.js V8 execution test for player-ui (player.html + player.js).
 * Validates that:
 * 1. player.js boots in a WebView2-like DOM environment without uncaught exceptions.
 * 2. Strict storage restrictions (e.g. sandboxed localStorage throwing SecurityError) do not crash the script.
 * 3. The initial 'ui_ready' handshake is dispatched to the host bridge.
 */
const fs = require('fs');
const path = require('path');
const vm = require('vm');

// Locate resources
function findResource(subpath) {
    const candidates = [
        path.resolve(__dirname, '../../main/resources/player-ui', subpath),
        path.resolve(process.cwd(), 'desktop-app/src/main/resources/player-ui', subpath),
        path.resolve(process.cwd(), 'src/main/resources/player-ui', subpath)
    ];
    for (const c of candidates) {
        if (fs.existsSync(c)) return c;
    }
    throw new Error(`Resource not found: ${subpath}`);
}

const htmlPath = findResource('player.html');
const jsPath = findResource('player.js');

const rawHtml = fs.readFileSync(htmlPath, 'utf8');
const rawJs = fs.readFileSync(jsPath, 'utf8');

// Parse declared DOM IDs from HTML
const idRegex = /id=["']([^"']+)["']/g;
const declaredIds = new Set();
let m;
while ((m = idRegex.exec(rawHtml)) !== null) {
    declaredIds.add(m[1]);
}

// Minimal Virtual DOM for V8 execution
const domElements = new Map();
function getOrCreateElement(id, tag = 'div') {
    if (domElements.has(id)) return domElements.get(id);
    const classes = new Set();
    const children = [];
    let text = '';
    const listeners = {};
    const el = {
        tagName: tag.toUpperCase(),
        id: id || '',
        classList: {
            add: (...cls) => cls.forEach(c => c && classes.add(c)),
            remove: (...cls) => cls.forEach(c => classes.delete(c)),
            toggle: (c, force) => {
                if (force === true) classes.add(c);
                else if (force === false) classes.delete(c);
                else if (classes.has(c)) classes.delete(c);
                else classes.add(c);
            },
            contains: (c) => classes.has(c),
            toString: () => Array.from(classes).join(' ')
        },
        style: (() => {
            const s = {
                setProperty: (k, v) => { s[k] = String(v); },
                removeProperty: (k) => { delete s[k]; },
                getPropertyValue: (k) => s[k] || ''
            };
            return new Proxy(s, {
                get: (target, prop) => prop in target ? target[prop] : '',
                set: (target, prop, value) => { target[prop] = String(value); return true; }
            });
        })(),
        get innerText() { return text; },
        set innerText(v) { text = String(v); },
        get textContent() { return text; },
        set textContent(v) { text = String(v); },
        get innerHTML() { return text; },
        set innerHTML(v) { text = String(v); },
        value: '100',
        min: '0',
        max: '100',
        dataset: {},
        children,
        childNodes: children,
        offsetWidth: 1920,
        offsetHeight: 1080,
        clientWidth: 1920,
        clientHeight: 1080,
        parentNode: null,
        setAttribute: (k, v) => {},
        getAttribute: (k) => null,
        hasAttribute: (k) => false,
        removeAttribute: (k) => {},
        addEventListener: (evt, fn) => {
            listeners[evt] = listeners[evt] || [];
            listeners[evt].push(fn);
        },
        removeEventListener: () => {},
        dispatchEvent: (event) => {
            const evtName = typeof event === 'string' ? event : (event && event.type) || 'click';
            const fns = listeners[evtName] || [];
            const baseEvt = typeof event === 'object' && event !== null ? event : {};
            const evtObj = Object.assign({ type: evtName, stopPropagation: () => {}, preventDefault: () => {}, target: el, currentTarget: el }, baseEvt);
            fns.forEach(fn => fn(evtObj));
        },
        click: () => {
            el.dispatchEvent('click');
        },
        getContext: () => new Proxy({
            canvas: el,
            measureText: () => ({ width: 10 }),
            createLinearGradient: () => ({ addColorStop: () => {} }),
            createPattern: () => ({})
        }, {
            get: (t, prop) => {
                if (prop in t) return t[prop];
                return () => {};
            },
            set: () => true
        }),
        appendChild: (child) => { children.push(child); return child; },
        insertBefore: (child, ref) => {
            const idx = ref ? children.indexOf(ref) : -1;
            if (idx >= 0) children.splice(idx, 0, child);
            else children.push(child);
            return child;
        },
        removeChild: (child) => child,
        querySelector: () => null,
        querySelectorAll: () => [],
        getBoundingClientRect: () => ({ left: 0, top: 0, right: 1920, bottom: 1080, width: 1920, height: 1080 }),
        closest: () => null,
        focus: () => {},
        blur: () => {}
    };
    if (id) domElements.set(id, el);
    return el;
}

// Seed all HTML declared IDs
declaredIds.forEach(id => getOrCreateElement(id));

const postMessagesSent = [];
const eventListeners = {};

// Sandbox environment simulating WebView2
const mockWindow = {
    console,
    Date,
    Math,
    JSON,
    Array,
    Object,
    String,
    Number,
    Boolean,
    RegExp,
    Set,
    Map,
    parseInt,
    parseFloat,
    isNaN,
    isFinite,
    performance: (typeof performance !== 'undefined' ? performance : { now: () => Date.now() }),
    document: {
        readyState: 'complete',
        title: 'CloudStream Player',
        body: getOrCreateElement('body', 'body'),
        documentElement: getOrCreateElement('html', 'html'),
        getElementById: (id) => {
            if (declaredIds.has(id)) return domElements.get(id);
            return null;
        },
        querySelector: (sel) => {
            if (sel.startsWith('#')) {
                const id = sel.substring(1);
                if (declaredIds.has(id)) return domElements.get(id);
                return null;
            }
            return getOrCreateElement('', sel.replace(/[^a-zA-Z0-9_-]/g, ''));
        },
        querySelectorAll: () => [],
        createElement: (tag) => getOrCreateElement('', tag),
        createTextNode: (t) => ({ textContent: t }),
        addEventListener: (event, fn) => {
            eventListeners[event] = eventListeners[event] || [];
            eventListeners[event].push(fn);
        },
        removeEventListener: () => {}
    },
    chrome: {
        webview: {
            postMessage: (msg) => postMessagesSent.push(msg),
            addEventListener: (event, fn) => {
                eventListeners[event] = eventListeners[event] || [];
                eventListeners[event].push(fn);
            }
        }
    },
    addEventListener: (event, fn) => {
        eventListeners[event] = eventListeners[event] || [];
        eventListeners[event].push(fn);
    },
    removeEventListener: () => {},
    setTimeout: (fn) => setTimeout(fn, 0),
    clearTimeout: (id) => clearTimeout(id),
    setInterval: () => 1,
    clearInterval: () => {},
    requestAnimationFrame: (fn) => setTimeout(fn, 0),
    cancelAnimationFrame: () => {},
    innerWidth: 1920,
    innerHeight: 1080,
    // Use a functional in-memory localStorage mock — strict Windows sandboxes block real
    // localStorage, but player.js wraps most calls in try/catch; this lets the DOM render paths
    // execute fully without SecurityError interrupting probing card reconciliation.
    localStorage: (() => {
        const store = {};
        return {
            getItem: (k) => Object.prototype.hasOwnProperty.call(store, k) ? store[k] : null,
            setItem: (k, v) => { store[k] = String(v); },
            removeItem: (k) => { delete store[k]; },
            clear: () => { for (const k in store) delete store[k]; },
            key: (i) => Object.keys(store)[i] || null,
            get length() { return Object.keys(store).length; }
        };
    })()
};
mockWindow.window = mockWindow;

const context = vm.createContext(mockWindow);

// Execute player.js in V8
try {
    vm.runInContext(rawJs, context, { filename: 'player.js' });
} catch (err) {
    console.error('[FAIL] player.js threw fatal exception on boot:\n', err);
    process.exit(1);
}

// Verify 'ui_ready' handshake
const uiReady = postMessagesSent.find(m => m && m.type === 'ui_ready');
if (!uiReady) {
    console.error('[FAIL] player.js booted but failed to dispatch "ui_ready" to window.chrome.webview');
    process.exit(2);
}

console.log('[SUCCESS] player.js booted cleanly in V8 and dispatched "ui_ready"');

// Test inbound lifecycle messages
function deliverMessage(payload) {
    const list = eventListeners['message'] || [];
    list.forEach(fn => fn({ data: payload }));
}

try {
    deliverMessage({
        type: 'app_state_update',
        volume: 100,
        isMuted: false,
        isAppLoading: false,
        loadingStatusText: null
    });
    deliverMessage({
        type: 'metadata_update',
        value: {
            title: 'Hero (2022)',
            isProbing: false,
            links: [{ index: 0, name: '1080p', quality: 1080, url: 'http://test' }],
            episodes: [{ id: '1', name: 'Episode 1', season: 1, episode: 1, data: '1' }]
        }
    });
    deliverMessage({
        type: 'state_update',
        positionMs: 15000,
        durationMs: 7200000,
        isPlaying: true,
        isLoading: false
    });
    if (typeof mockWindow.__dismissProbingOverlay === 'function') {
        mockWindow.__dismissProbingOverlay();
    }

    // Verify showControls() and interactive button/slider event dispatch
    if (typeof mockWindow.showControls === 'function') {
        mockWindow.showControls();
    }
    const buttonsToTest = [
        'playPauseBtn',
        'muteBtn',
        'speedBtn',
        'settingsBtn',
        'episodesBtn',
        'audioSubsBtn',
        'btnResetSubtitles',
        'closeSettingsBtn',
        'closeEpisodesBtn'
    ];
    for (const btnId of buttonsToTest) {
        const el = domElements.get(btnId);
        if (el && typeof el.click === 'function') {
            el.click();
        }
    }
    const subSlider = domElements.get('subPosSlider');
    if (subSlider && typeof subSlider.dispatchEvent === 'function') {
        subSlider.dispatchEvent('input');
    }

    // ── Test Seek Preview Thumbnails Engine ──
    const sampleVtt = `WEBVTT

00:00:00.000 --> 01:00:00.000
sprite.jpg#xywh=0,0,160,90

01:00:00.000 --> 02:00:00.000
sprite.jpg#xywh=160,0,160,90
`;
    mockWindow.loadSeekThumbnailsVtt(sampleVtt, 'https://example.com/thumbnails/');
    if (!mockWindow._seekThumbnailCues || mockWindow._seekThumbnailCues.length !== 2) {
        throw new Error('VTT cues length mismatch: expected 2, got ' + (mockWindow._seekThumbnailCues && mockWindow._seekThumbnailCues.length));
    }
    const tile = mockWindow.getSeekThumbnailTileAt(5000);
    if (!tile || tile.x !== 0 || tile.y !== 0 || tile.w !== 160 || tile.h !== 90) {
        throw new Error('Tile lookup mismatch at 5000ms: ' + JSON.stringify(tile));
    }
    const tileHour = mockWindow.getSeekThumbnailTileAt(3600000);
    if (!tileHour || tileHour.x !== 160 || tileHour.y !== 0 || tileHour.w !== 160 || tileHour.h !== 90) {
        throw new Error('Tile lookup mismatch at 3600000ms: ' + JSON.stringify(tileHour));
    }

    const seekWrap = domElements.get('seekWrap');
    const seekTooltip = domElements.get('seekTooltip');
    const seekThumbCard = domElements.get('seekThumbCard');
    if (seekWrap && seekTooltip) {
        seekWrap.dispatchEvent({ type: 'mousemove', clientX: 960, stopPropagation: () => {}, preventDefault: () => {} });
        if (!seekTooltip.classList.contains('visible')) {
            throw new Error('seekTooltip missing visible class after mousemove');
        }
        if (!seekTooltip.classList.contains('has-thumb')) {
            throw new Error('seekTooltip missing has-thumb class when thumbnail is present');
        }
        if (seekThumbCard.style.display !== 'block') {
            throw new Error('seekThumbCard display is not block: ' + seekThumbCard.style.display);
        }
        seekWrap.dispatchEvent({ type: 'mouseleave', stopPropagation: () => {}, preventDefault: () => {} });
        if (seekTooltip.classList.contains('visible')) {
            throw new Error('seekTooltip retained visible class after mouseleave');
        }

        // Test Trickplay Thumbnail Response
        mockWindow.playerUpdate(JSON.stringify({
            type: 'seek_thumbnail_response',
            timeMs: 3600000,
            dataUri: 'data:image/jpeg;base64,/9j/fakejpegdata'
        }));
    }

    // ── Regression Test: Graphic Tech Badges, Probing Badges & Pause Rating Badges ──
    deliverMessage({
        type: 'metadata_update',
        value: {
            title: 'Sample Series - S1E1',
            isProbing: true,
            badgesBaseUrl: 'file:///C:/badges/Badges-Black-White/',
            rating: 8.1,
            ratingBadges: [
                { source: 'imdb', scoreText: '8.7', iconUrl: 'file:///C:/badges/rating_imdb.png' },
                { source: 'tmdb', scoreText: '8.4', iconUrl: 'file:///C:/badges/rating_tmdb.png' }
            ],
            links: [
                {
                    index: 0,
                    name: '[TB+] Sample.2160p.REMUX.DV.HEVC.Atmos',
                    quality: 2160,
                    isActive: true,
                    url: 'http://cdn.example.invalid/ep1.mkv',
                    techTags: [
                        { text: 'TorBox', type: 'format', badgeFile: 'torbox.png', badgeUrl: 'file:///C:/badges/Badges-Black-White/torbox.png' },
                        { text: 'HEVC', type: 'codec', badgeFile: 'hevc.png', badgeUrl: 'file:///C:/badges/Badges-Black-White/hevc.png' }
                    ]
                }
            ],
            episodes: [
                { id: 'ep1', title: 'Episode 1', season: 1, episode: 1, isActive: true, watchedPercentage: 85.0, isSeen: true },
                { id: 'ep2', title: 'Episode 2', season: 1, episode: 2, isActive: false, watchedPercentage: 50.0, isSeen: false }
            ]
        }
    });

    const serversHtml = domElements.get('serversList').innerHTML;
    if (!serversHtml.includes('class="srv-badge-img"') || !serversHtml.includes('torbox.png') || !serversHtml.includes('hevc.png')) {
        throw new Error('Server card failed to render graphic badge images: ' + serversHtml);
    }

    const probingList = domElements.get('linkProbingList');
    // Verify renderTechTagsHtml generates compact probing badge markup directly.
    // Probing card reconciliation uses insertBefore; children[] is populated but not reflected
    // in the virtual DOM's innerHTML getter — call renderTechTagsHtml directly instead.
    const sampleLink = { techTags: [
        { text: 'TorBox', type: 'format', badgeFile: 'torbox.png', badgeUrl: 'file:///C:/badges/Badges-Black-White/torbox.png' },
        { text: 'HEVC', type: 'codec', badgeFile: 'hevc.png', badgeUrl: 'file:///C:/badges/Badges-Black-White/hevc.png' }
    ]};
    const probingTagsHtml = typeof mockWindow.renderTechTagsHtml === 'function'
        ? mockWindow.renderTechTagsHtml(sampleLink, { compact: true, maxTags: 5 })
        : '';
    if (!probingTagsHtml.includes('srv-badge-img probing') || !probingTagsHtml.includes('torbox.png') || !probingTagsHtml.includes('hevc.png')) {
        throw new Error('Probing card failed to render graphic badge images: ' + probingHtml);
    }

    const brandRatingsHtml = domElements.get('pauseInfoBrandRatings').innerHTML;
    if (!brandRatingsHtml.includes('rating_imdb.png') || !brandRatingsHtml.includes('8.7') || !brandRatingsHtml.includes('rating_tmdb.png')) {
        throw new Error('Pause overlay failed to render attributed brand rating badges: ' + brandRatingsHtml);
    }

    // Verify badge <img> error cleanly degrades into CSS cube badge without leaving broken image
    let replacedSlotHtml = '';
    const fakeSlot = { set innerHTML(v) { replacedSlotHtml = v; } };
    mockWindow.__handleBadgeImgError({
        alt: 'HEVC',
        dataset: { badgeText: 'HEVC', badgeType: 'codec' },
        classList: { contains: () => false },
        closest: () => fakeSlot
    });
    if (!replacedSlotHtml.includes('srv-cube-badge') || !replacedSlotHtml.includes('tag-codec') || !replacedSlotHtml.includes('HEVC')) {
        throw new Error('Badge image error fallback failed: ' + replacedSlotHtml);
    }

    // Verify Next Episode click does not fake 100% watched when below 80%
    deliverMessage({ type: 'state_update', positionMs: 120000, durationMs: 1440000, isPlaying: true, isLoading: false });
    mockWindow.triggerNextEpisode();

    // ── Regression Test: Movie Probing Stability Across Link Batches & Title Suffixes ──
    deliverMessage({
        type: 'metadata_update',
        value: {
            title: 'Sample Feature - Sample Feature',
            isProbing: true,
            currentLinkIndex: -1,
            backdropUrl: 'file:///C:/cache/backdrop.jpg',
            logoUrl: 'file:///C:/cache/logo.png',
            links: [
                { index: 0, name: '[Mirror 1080p] Stream A', quality: 1080, isActive: false, url: 'http://cdn.example.invalid/sf1.mkv' }
            ],
            episodes: []
        }
    });
    const probingBadge = domElements.get('probingSourceCountBadge');
    const probingPlayBtn = domElements.get('probingPlayBtn');
    const probingStatus = domElements.get('linkProbingStatus');
    if (probingBadge.style.display !== 'inline-flex' || probingBadge.textContent !== '1 source') {
        throw new Error('Probing source badge missing on first link batch: ' + probingBadge.style.display + ' / ' + probingBadge.textContent);
    }
    if (probingPlayBtn.style.display !== 'inline-flex') {
        throw new Error('Skip Loading button hidden after links arrived: ' + probingPlayBtn.style.display);
    }
    // Simulate targetEpisodeData clearing (title suffix stripped) + 100 discovered sources while active link probing begins
    const batch100 = Array.from({ length: 100 }, (_, i) => ({
        index: i,
        name: `[Mirror 1080p] Source ${i}`,
        quality: 1080,
        isActive: i === 0,
        url: `http://cdn.example.invalid/sf_${i}.mkv`
    }));
    deliverMessage({
        type: 'metadata_update',
        value: {
            title: 'Sample Feature',
            isProbing: true,
            currentLinkIndex: 0,
            backdropUrl: 'file:///C:/cache/backdrop.jpg',
            logoUrl: 'file:///C:/cache/logo.png',
            links: batch100,
            episodes: []
        }
    });
    if (probingBadge.style.display !== 'inline-flex' || probingBadge.textContent !== '100 sources') {
        throw new Error('Probing source badge destabilized after title normalization: ' + probingBadge.style.display + ' / ' + probingBadge.textContent);
    }
    if (probingPlayBtn.style.display !== 'inline-flex') {
        throw new Error('Skip Loading button flickered hidden on title normalization: ' + probingPlayBtn.style.display);
    }
    if (!probingStatus.textContent || probingStatus.textContent.includes('Discovering streaming sources')) {
        throw new Error('Probing status regressed to Discovering while active link probing: ' + probingStatus.textContent);
    }

    // ── Regression Test: Hold-to-Speed preserves 1.0x instead of shifting to 1.25x ──
    deliverMessage({ type: 'state_update', positionMs: 60000, durationMs: 1440000, isPlaying: true, isLoading: false });
    const speedSlider = domElements.get('speedSlider');
    speedSlider.value = '1.0';
    mockWindow.applySpeed(1.0);
    const zoneRight = domElements.get('zoneRight');
    zoneRight.dispatchEvent({ type: 'mousedown' });
    setTimeout(() => {
        try {
            zoneRight.dispatchEvent({ type: 'mouseup' });
            if (Math.abs(mockWindow.currentSpeed - 1.0) > 0.01) {
                console.error('[FAIL] Hold-to-speed corrupted speed from 1.0x to ' + mockWindow.currentSpeed);
                process.exit(4);
            }
            console.log('[SUCCESS] All inbound player messages, badges, and hold-speed regression checks passed');
            process.exit(0);
        } catch (asyncErr) {
            console.error('[FAIL] Async hold-to-speed check failed:\n', asyncErr);
            process.exit(5);
        }
    }, 10);
} catch (err) {
    console.error('[FAIL] player.js threw unhandled error during message lifecycle:\n', err);
    process.exit(3);
}
