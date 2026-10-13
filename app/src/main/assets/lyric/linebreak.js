// Line breaking for lyrics, replacing JIZURA's character-count split.
//
// JIZURA's splitLines balances lines by glyph count and only mildly prefers a space or a
// hiragana→kanji edge, so it breaks English words in the middle and Japanese phrases at odd
// places. This version breaks only at word boundaries (Intl.Segmenter), prefers breaking right
// after a Japanese particle (〜を / 〜は / 〜に …) or punctuation, never starts a line with a
// particle or punctuation, and balances the lines by display width (a Latin letter counts as
// about half a CJK glyph). Falls back to JIZURA's own split when the segmenter is missing.
(() => {
    'use strict';
    const PARTICLES = new Set(['は', 'が', 'を', 'に', 'で', 'と', 'の', 'も', 'へ', 'や', 'か', 'ね', 'よ', 'な', 'から', 'まで', 'って', 'など', 'とか', 'だけ', 'しか', 'ほど', 'より', 'けど', 'のに', 'ので', 'って', 'わ', 'さ', 'ぞ', 'ぜ']);
    const PUNCT_END = /[、。,.!?！？…・：:;；）)」』】〕〉》"'’”]$/;
    const NO_LINE_START = /^[んぁぃぅぇぉっゃゅょゎァィゥェォッャュョヮヵヶー、。,.!?！？…・：:;；）)」』】〕〉》]/;
    const LATIN = /[A-Za-zÀ-ɏ0-9]/;
    const KATA_END = /[ァ-ヺー]$/, KATA_START = /^[ァ-ヺー]/;

    let segmenters = {};
    const segmenterFor = (locale) => {
        if (typeof Intl === 'undefined' || !Intl.Segmenter) return null;
        return segmenters[locale] || (segmenters[locale] = new Intl.Segmenter(locale, { granularity: 'word' }));
    };

    const glyphWidth = (ch) => (/\s/.test(ch) ? 0.3 : LATIN.test(ch) ? 0.55 : 1);
    const width = (s) => { let w = 0; for (const ch of s) w += glyphWidth(ch); return w; };

    /**
     * @param {string} text
     * @param {number} maxPer   JIZURA's budget: glyphs per line (CJK glyphs)
     * @param {string} locale   'ja' | 'en' | …
     * @param {(text: string, maxPer: number) => string} fallback
     */
    function breakLines(text, maxPer, locale, fallback) {
        const s = String(text);
        const seg = segmenterFor(locale || 'ja');
        if (!seg) return fallback(s, maxPer);
        const total = width(s);
        if (total <= maxPer) return s;

        // word segments and the break candidates between them
        const segs = [...seg.segment(s)].map(x => x.segment);
        if (segs.length <= 1) return s;
        const starts = []; let pos = 0;
        for (const sg of segs) { starts.push(pos); pos += sg.length; }
        const cands = [];   // { at: index into s, score }
        for (let i = 1; i < segs.length; i++) {
            const prev = segs[i - 1], next = segs[i];
            const at = starts[i];
            if (/^\s+$/.test(next)) continue;              // break after the space, not before it
            let score = 0;
            if (/^\s+$/.test(prev)) score += 5;              // after a space
            else if (PUNCT_END.test(prev)) score += 5;      // after punctuation
            else if (PARTICLES.has(prev)) score += 4;       // after a particle: 〜を | 〜は |
            else if (locale === 'ja' || locale === 'auto') score += 1;   // any other word boundary
            if (PARTICLES.has(next) || NO_LINE_START.test(next)) score -= 8;   // never start with は / っ / 。
            if (KATA_END.test(prev) && KATA_START.test(next)) score -= 8;      // ICU splits サタ|デー|ナイト: one word in a lyric
            if (score < 0) continue;
            cands.push({ at, score });
        }
        if (!cands.length) return s;

        // how many lines, by display width; then pick the breaks that balance the lines best
        const n = Math.min(Math.ceil(total / maxPer), cands.length + 1);
        if (n <= 1) return s;
        const avg = total / n;
        const lineW = (a, b) => width(s.slice(a, b).trim());
        // DP over candidates: best[k][j] = min cost using k lines ending at candidate j (or the end)
        const ends = cands.map(c => c.at).concat([s.length]);
        const bonus = (j) => (j < cands.length ? cands[j].score : 0) * Math.max(1, avg * 0.25);
        const cost = (a, b, bonusAtB) => { const w = lineW(a, b); const over = w > maxPer * 1.3 ? (w - maxPer) * 4 : 0; return (w - avg) * (w - avg) + over - bonusAtB; };
        const INF = 1e18;
        let prevRow = ends.map((e, j) => cost(0, e, bonus(j)));
        let back = [prevRow.map(() => -1)];
        for (let k = 2; k <= n; k++) {
            const row = ends.map(() => INF), bk = ends.map(() => -1);
            for (let j = 0; j < ends.length; j++) {
                for (let i = 0; i < j; i++) {
                    if (i >= cands.length) continue;
                    if (prevRow[i] >= INF) continue;
                    const c = prevRow[i] + cost(ends[i], ends[j], bonus(j));
                    if (c < row[j]) { row[j] = c; bk[j] = i; }
                }
            }
            prevRow = row; back.push(bk);
        }
        const last = ends.length - 1;
        if (prevRow[last] >= INF) return fallback(s, maxPer);
        const cuts = []; let j = last;
        for (let k = n - 1; k >= 1; k--) { j = back[k][j]; if (j < 0) break; cuts.unshift(ends[j]); }
        const out = []; let a = 0;
        for (const c of cuts) { out.push(s.slice(a, c).trim()); a = c; }
        out.push(s.slice(a).trim());
        return out.filter(Boolean).join('\n');
    }

    window.standbyBreakLines = breakLines;
})();
