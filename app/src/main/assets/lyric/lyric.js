// StaaaaandBy lyric video page. Adapted from jizura-sync (MIT, Saqoosha) web/app.js:
// the Spotify polling and the UI are gone; the Android side pushes the song and the
// playback anchor through `window.lyric`, and this page only draws.
//
// JIZURA's renderer is a pure function of time: `frame(ctx, plan, t)` draws the instant `t`
// of a plan built once per song. Syncing is handing it the extrapolated playback position
// every animation frame. Seeks, pauses and track changes need no extra state.
(() => {
    'use strict';
    const J = window.J;
    const canvas = document.getElementById('view');
    const ctx = canvas.getContext('2d');
    const renderer = new J.Renderer();

    // Characters JIZURA's HUD / title cards draw besides the lyrics (same set as its editor UI).
    const HUD_CHARS = '0123456789:./-_()【】・No.LYRICRECUNTITLEDXYlinebpminterlude—─／ ';

    // JIZURA only knows a handful of aspect keys and falls back to 16:9 for anything else.
    // Its planner and layouts work from W/H alone, so teach designSize any "w:h" key: the
    // short side stays 1080 (its design scale) and the long side follows the screen. Then the
    // plan is built for the screen's exact shape and fills it with no letterbox.
    const baseDesignSize = J.designSize;
    J.designSize = (aspect) => {
        const m = /^(\d+):(\d+)$/.exec(aspect || '');
        if (!m) return baseDesignSize(aspect);
        const w = +m[1], h = +m[2];
        if (w >= h) return [Math.round(1080 * w / h / 2) * 2, 1080];
        return [1080, Math.round(1080 * h / w / 2) * 2];
    };

    // Adaptive quality. The phone throttles its clocks hard when it gets hot on the Qi pad
    // (measured: 1.1 GHz instead of 3+, frames 8-60 ms instead of 2 ms). When frames run long,
    // draw at 1 CSS px per canvas px (a quarter of the pixels) and at 30 fps; when they are
    // quick again, go back up. Text this large still reads fine at half resolution.
    let DPR_CAP = 2;
    let MAX_FPS = 60;
    const SLOW_MS = 12, FAST_MS = 4;
    let slowWindows = 0, fastWindows = 0;
    // Draw at most MAX_FPS often. The Flip's screen runs at 120 Hz, but JIZURA plans its motion at
    // 60 fps, so drawing every refresh only doubles the GPU work (and the heat on the Qi pad).
    const minFrameMs = () => 1000 / MAX_FPS - 1;

    let song = null;             // { key, title, artist, durationMs, lyrics }
    let look = null;             // JIZURA random look (omakase) on top of the default project
    let plan = null;
    let planAspect = null;       // JIZURA aspect key the plan was built for
    let anchor = null;           // { positionMs, playing, speed, at: performance.now() }
    let lastT = -1;
    let lastDrawAt = 0;
    // frame-time stats, logged every 10 s (visible in logcat through the WebChromeClient)
    let statFrames = 0, statDraw = 0, statMax = 0, statSince = performance.now(), statTicks = 0;

    function hashString(s) {
        let h = 0x811c9dc5;
        for (let i = 0; i < s.length; i++) h = Math.imul(h ^ s.charCodeAt(i), 0x01000193);
        return h >>> 0;
    }

    /** The window's own aspect as a reduced "w:h" key (1080x2520 → "3:7"), so the frame fills the screen. */
    function windowAspect() {
        const w = Math.max(1, Math.round(window.innerWidth)), h = Math.max(1, Math.round(window.innerHeight));
        const gcd = (a, b) => (b ? gcd(b, a % b) : a);
        const g = gcd(w, h);
        return `${w / g}:${h / g}`;
    }

    /** A random look seeded by the track, so a song always opens the same way. */
    function rollLook(seed) {
        const rnd = seed == null ? Math.random : J.rng(seed);
        const base = Object.assign(J.defaultProject(), { extra: true, typo: true, kinetic: true });
        return J.omakase(base, rnd);
    }

    function buildPlan() {
        if (!song) { plan = null; return; }
        const project = Object.assign(J.defaultProject(), {
            extra: true, typo: true, kinetic: true, horror: false,
            title: song.title, artist: song.artist, lyrics: song.lyrics,
            aspect: (planAspect = windowAspect()), fps: 60,
        }, look);
        // per-part palettes of layouts / motions; without it every cut is an independent draw
        project.unify = true;
        // JIZURA's HUD (title bar, REC, timecode, LYRIC n/m) is off: the standby screen has its own clock and track info.
        // koma 0 = draw every frame: the random look may pick a 12 / 8 fps hand-drawn stepping, which reads as stutter here.
        project.fx = Object.assign({}, project.fx, { hud: 'off', koma: 0, onTwos: false });
        // LRC times are the whole timing: no BPM grid to snap to.
        project.timing = Object.assign({}, project.timing, { bpm: 0, snap: false });
        project.enabled = Object.assign({}, J.defaultProject().enabled, look.enabled);
        plan = J.plan(project, song.durationMs ? { duration: song.durationMs / 1000 } : null);
        lastT = -1;
        sizeCanvas();
        J.ensureFonts(song.lyrics + song.title + song.artist + HUD_CHARS, J.fontsOfPlan(plan))
            .then(() => { lastT = -1; })      // redraw once the real fonts are in
            .catch(() => {});
    }

    function sizeCanvas() {
        if (!plan) return;
        const ar = plan.W / plan.H;
        let cssW = window.innerWidth, cssH = cssW / ar;
        if (cssH > window.innerHeight) { cssH = window.innerHeight; cssW = cssH * ar; }
        const dpr = Math.min(DPR_CAP, window.devicePixelRatio || 1);
        const pw = Math.round(Math.min(plan.W, cssW * dpr)), ph = Math.round(pw / ar);
        if (canvas.width !== pw || canvas.height !== ph) { canvas.width = pw; canvas.height = ph; lastT = -1; }
        canvas.style.width = `${cssW}px`;
        canvas.style.height = `${cssH}px`;
    }

    function positionMs() {
        if (!anchor) return 0;
        const run = anchor.playing ? (performance.now() - anchor.at) * anchor.speed : 0;
        return anchor.positionMs + run;
    }

    function tick() {
        requestAnimationFrame(tick);
        statTicks++;
        const now = performance.now();
        if (now - statSince >= 10000) {
            if (statFrames) {
                const avg = statDraw / statFrames;
                console.log(`perf: ${(statTicks * 1000 / (now - statSince)).toFixed(1)} ticks/s, drew ${statFrames}, ` +
                    `draw avg ${avg.toFixed(1)} ms, max ${statMax.toFixed(1)} ms, canvas ${canvas.width}x${canvas.height}, fps cap ${MAX_FPS}`);
                adapt(avg, statFrames, statTicks * 1000 / (now - statSince));
            }
            statFrames = 0; statDraw = 0; statMax = 0; statTicks = 0; statSince = now;
        }
        if (!plan || !song) return;
        if (now - lastDrawAt < minFrameMs()) return;
        const t = Math.min(Math.max(0, positionMs() / 1000), plan.duration - 1e-3);
        // Paused: the picture does not change, so do not burn the GPU redrawing it.
        if (t === lastT) return;
        lastT = t;
        lastDrawAt = now;
        const t0 = performance.now();
        // fast: skip JIZURA's blur filters and glow. Measured on a Galaxy Z Flip7: with them the
        // page managed 4-6 fps (60-140 ms per frame); without them 110+ fps at full resolution.
        renderer.frame(ctx, plan, t, { scale: canvas.width / plan.W, fast: true, noHud: true });
        const dt = performance.now() - t0;
        statFrames++; statDraw += dt; if (dt > statMax) statMax = dt;
    }

    /**
     * Step quality down after 2 slow 10 s windows, back up after 3 quick ones.
     * A window is slow when frames took long to draw OR few of them made it to the screen
     * (the compositor, not this script, is the bottleneck on a throttled phone); windows with
     * hardly any draws (paused, track change) are ignored rather than resetting the count.
     */
    function adapt(avgMs, drawn, ticksPerS) {
        if (drawn < 20) return;
        const slow = avgMs > SLOW_MS || ticksPerS < 20;
        const fast = avgMs < FAST_MS && ticksPerS > 50;
        if (slow) { slowWindows++; fastWindows = 0; } else if (fast) { fastWindows++; slowWindows = 0; }
        if (slowWindows >= 2 && (DPR_CAP > 1 || MAX_FPS > 30)) {
            DPR_CAP = 1; MAX_FPS = 30; slowWindows = 0;
            console.log('perf: frames are slow, dropping to half resolution / 30 fps');
            sizeCanvas();
        } else if (fastWindows >= 3 && (DPR_CAP < 2 || MAX_FPS < 60)) {
            DPR_CAP = 2; MAX_FPS = 60; fastWindows = 0;
            console.log('perf: frames are quick, back to full resolution / 60 fps');
            sizeCanvas();
        }
    }

    let resizeTimer = 0;
    window.addEventListener('resize', () => {
        sizeCanvas();
        // A different window shape may want a different JIZURA aspect, which is a new plan.
        clearTimeout(resizeTimer);
        resizeTimer = setTimeout(() => { if (song && windowAspect() !== planAspect) buildPlan(); }, 300);
    });

    window.lyric = {
        /** @param {{key: string, title: string, artist: string, durationMs: number, lrc: string}} s */
        setSong(s) {
            if (song && song.key === s.key) return;
            const { lyrics } = window.lrcToJizura(s.lrc);
            song = { key: s.key, title: s.title, artist: s.artist, durationMs: s.durationMs, lyrics };
            look = rollLook(hashString(s.key));
            buildPlan();
        },
        clear() {
            song = null; plan = null; lastT = -1;
            ctx.clearRect(0, 0, canvas.width, canvas.height);
        },
        /** @param {{positionMs: number, playing: boolean, speed: number}} a */
        setAnchor(a) {
            anchor = { positionMs: a.positionMs, playing: !!a.playing, speed: a.speed || 1, at: performance.now() };
        },
        /** A fresh random look for the current song. */
        reroll() { if (!song) return; look = rollLook(null); buildPlan(); },
    };

    requestAnimationFrame(tick);
})();
