// LRC -> JIZURA lyric source. Taken from jizura-sync (MIT, Saqoosha) web/lyrics.js, made a classic script.
// https://github.com/Saqoosha/jizura-sync
/** A silent stretch at least this long becomes a JIZURA [間奏] cut (graphics, no lyric). */
const INTERLUDE_MIN_S = 6;

/**
 * LRC -> JIZURA lyric source. JIZURA reads the same `[mm:ss.xx]` tags, but a few characters in
 * the line text are markup to it (`|` note, `*emphasis*`, `/` manual split, a leading `#` is a
 * comment) -- those are swapped for their full-width forms so real lyrics are shown verbatim.
 * A trailing `!` is left alone on purpose: JIZURA reads it as an "impact" line, which suits
 * a line the singer actually shouts.
 *
 * @param {string} lrc
 * @returns {{lyrics: string, lines: number, parts: number}}
 */
function lrcToJizura(lrc) {
    const rows = [];
    for (const raw of lrc.replace(/\r/g, '').split('\n')) {
        const m = raw.match(/^((?:\[\d+:\d+(?:[.:]\d+)?\])+)(.*)$/);
        if (!m) continue;                                   // [ar:] / [ti:] headers and junk
        const times = [...m[1].matchAll(/\[(\d+):(\d+(?:[.:]\d+)?)\]/g)].map((t) => +t[1] * 60 + parseFloat(t[2].replace(':', '.')));
        const text = escapeMarkup(m[2].trim());
        for (const t of times) rows.push({ t, text });
    }
    rows.sort((a, b) => a.t - b.t);

    // 1. sung lines, each remembering the blank row / long silence in front of it
    const sung = [];
    let blank = false, interlude = null;
    rows.forEach((r, i) => {
        if (r.text) { sung.push({ t: r.t, text: r.text, blank, interlude }); blank = false; interlude = null; return; }
        if (sung.length) blank = true;
        const next = rows[i + 1];
        if (next && next.t - r.t >= INTERLUDE_MIN_S && interlude === null) interlude = r.t;
    });

    // 2. part breaks. An empty row is how JIZURA marks a part, and 統一感 keeps one small set of
    // layouts / motions per part -- so a song with no breaks is one part and looks monotonous.
    const breaks = partBreaks(sung);

    // 3. JIZURA source
    const out = [];
    sung.forEach((s, i) => {
        if (i > 0 && breaks[i]) out.push('');
        if (s.interlude !== null) out.push(`${tag(s.interlude)}[間奏]`);
        out.push(`${tag(s.t)}${s.text}`);
    });
    if (interlude !== null) out.push('', `${tag(interlude)}[間奏]`);
    return { lyrics: out.join('\n'), lines: sung.length, parts: 1 + breaks.filter((b, i) => b && i > 0).length };
}

/** A part longer than this is split at its longest line interval. */
const MAX_PART_LINES = 8;
/**
 * A part shorter than this is merged into a neighbour. 統一感 picks a fresh palette per part, so
 * a string of 1–2 line parts reads as random again -- which is what repeats of short phrases
 * ("Come on now, follow my lead") produced before this floor existed.
 */
const MIN_PART_LINES = 3;
/** A repeat shorter than this is a hook line, not a section (Lemon repeats 「今でもあなたはわたしの光」 alone). */
const MIN_REPEAT_RUN = 2;

// Break strength: which break survives when a too-short part has to be merged away.
// A [間奏] is a part boundary to JIZURA no matter what, so it is never removed.
const INTERLUDE = 4, BLANK = 3, PAUSE = 2, REPEAT = 2, LENGTH = 1;

/**
 * breaks[i] = a new part starts at sung line i. Signals, strongest first:
 *  - a long silence ([間奏]) or a timed blank row in the LRC (the source itself marks a pause)
 *  - a start-to-start interval far above the song's norm (sources that mark nothing)
 *  - a run of lines that repeats earlier lines (サビ): both copies become parts of their own
 * then parts under MIN_PART_LINES are merged away (weakest break first), and parts still over
 * MAX_PART_LINES are split at their longest interval -- for songs sung without a pause and
 * without exact repeats.
 */
function partBreaks(sung) {
    const n = sung.length, br = new Array(n).fill(0);
    const set = (i, s) => { if (i > 0 && i < n) br[i] = Math.max(br[i], s); };
    const gap = partBreakGap(sung);
    sung.forEach((s, i) => {
        if (s.interlude !== null) set(i, INTERLUDE);
        if (s.blank) set(i, BLANK);
        if (i > 0 && s.t - sung[i - 1].t >= gap) set(i, PAUSE);
    });

    // repeats: rep[i] = index of the first line with the same text, or -1
    const norm = (t) => t.replace(/[\s、。，．,.!！?？…・「」『』（）()"'“”‘’~〜ー―-]/g, '').toLowerCase();
    const first = new Map(), rep = sung.map((s, i) => {
        const k = norm(s.text);
        if (k.length < 2) return -1;
        if (first.has(k)) return first.get(k);
        first.set(k, i);
        return -1;
    });
    for (let a = 0; a < n;) {
        if (rep[a] < 0) { a++; continue; }
        let b = a;
        while (b + 1 < n && rep[b + 1] === rep[b] + 1) b++;   // the copy continues line by line
        if (b - a + 1 >= MIN_REPEAT_RUN) {
            const len = b - a + 1;
            for (const i of [a, b + 1, rep[a], rep[a] + len]) set(i, REPEAT);
        }
        a = b + 1;
    }

    // merge parts that are too short into the neighbour behind the weaker break
    for (;;) {
        const starts = [0, ...br.flatMap((s, i) => (s ? [i] : []))];
        let fixed = false;
        for (let p = 0; p < starts.length && !fixed; p++) {
            const a = starts[p], b = p + 1 < starts.length ? starts[p + 1] : n;   // part = a..b-1
            if (b - a >= MIN_PART_LINES) continue;
            const before = a > 0 && br[a] < INTERLUDE ? br[a] : Infinity;        // break opening this part
            const after = b < n && br[b] < INTERLUDE ? br[b] : Infinity;         // break closing it
            if (before === Infinity && after === Infinity) continue;            // walled in by [間奏]
            br[before <= after ? a : b] = 0;
            fixed = true;
        }
        if (!fixed) break;
    }

    // length cap: split at the longest interval that leaves both halves at least MIN_PART_LINES
    const split = (a, b) => {                                  // part = lines a..b inclusive
        if (b - a + 1 <= MAX_PART_LINES) return;
        let k = -1, best = -1;
        for (let j = a + MIN_PART_LINES; j <= b - MIN_PART_LINES + 1; j++) { const d = sung[j].t - sung[j - 1].t; if (d > best) { best = d; k = j; } }
        if (k < 0) return;
        br[k] = LENGTH;
        split(a, k - 1);
        split(k, b);
    };
    let start = 0;
    for (let i = 1; i <= n; i++) if (i === n || br[i]) { split(start, i - 1); start = i; }
    return br.map(Boolean);
}

/**
 * Start-to-start interval that counts as a part break: 1.8x the song's median line interval,
 * never under 4 s. Relative, because line length follows the song's tempo. Checked against
 * LRCLIB files whose blank rows mark parts: finds 9 of 12 marked breaks over 4 songs; a song
 * sung without pauses (Shape of You) has no interval that long -- the repeat and length rules
 * in partBreaks() cover it.
 */
function partBreakGap(textRows) {
    const iv = textRows.slice(1).map((r, i) => r.t - textRows[i].t).sort((a, b) => a - b);
    if (!iv.length) return Infinity;
    return Math.max(4, 1.8 * iv[iv.length >> 1]);
}

function escapeMarkup(s) {
    return s.replace(/^#/, '＃').replace(/\|/g, '｜').replace(/\*/g, '＊').replace(/\//g, '／');
}

function tag(t) {
    const m = Math.floor(t / 60), s = t - m * 60;
    return `[${String(m).padStart(2, '0')}:${s.toFixed(2).padStart(5, '0')}]`;
}

window.lrcToJizura = lrcToJizura;
