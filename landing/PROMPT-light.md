# AutoOps landing page — white/blue build prompt

The light-mode counterpart to `PROMPT.md`. Same design language, inverted palette, and
extended from one locked viewport into a full scrolling page.

`PROMPT.md` (black) stays the reference for *shape*: composition, type, motion curves,
the liquid-glass surfaces, the drawn ribbon. This file only records what changes and what
gets added. Where this file is silent, `PROMPT.md` still governs.

Output: `index-light.html`. One file, inline CSS, three IIFEs. No external image or CDN
asset except the two font stylesheets. No video, no WebGL, no Lottie, no library.

---

## 1. What inverts

The identity of the reference is not the black. It is five colour-agnostic things:

1. Single-viewport hero composition — badge → masked two-line H1 → lede → two CTAs → stats
2. Satoshi throughout, exactly one Playfair Display italic phrase
3. Liquid-glass surfaces — hairline border, gradient fill, a shine sweep on a pseudo-element
4. Staged entrance motion, `cubic-bezier(0.16, 1, 0.3, 1)`, staggered by `--d`
5. A generative canvas ribbon + grain in place of any image

All five survive. Only the paint changes.

---

## 2. Tokens

```
--bg:            #ffffff
--bg-alt:        #f8fafc     /* section wash, card fill */
--text:          #0f172a
--soft:          #475569     /* stats, list copy */
--muted:         #64748b     /* lede, card body */
--faint:         #94a3b8     /* eyebrow numerals, timestamps */
--border:        rgba(15, 23, 42, 0.10)
--border-strong: rgba(15, 23, 42, 0.14)

--accent:        #2563eb     /* ink blue — fills, links, primary action */
--accent-lift:   #3b82f6     /* gradient top */
--accent-deep:   #1d4ed8     /* gradient bottom */
--accent-glow:   #60a5fa     /* GLOW ONLY — see the contrast rule */
--accent-wash:   #eef4ff     /* tinted surface */
```

**The contrast rule.** `#60a5fa` measures ~2.2:1 on white. It fails AA for any text, icon
stroke, or small fill. In this palette it is a `box-shadow` and gradient-stop colour and
nothing else. Anything a user has to *read* uses `--accent` (`#2563eb`, 4.7:1) or darker.

Type tokens, the hero size ladder, and every breakpoint are carried over from `PROMPT.md`
unchanged. New tokens for the scrolling body:

```
--sec-y:      112px   /* section vertical rhythm */
--sec-x:      24px
--wrap:       1200px  /* content max-width */
--card-r:     16px
--panel-r:    24px
```

---

## 3. Surface translations

| Surface | Black reference | White/blue |
|---|---|---|
| Page | `#000000` | `#ffffff`, alt bands `--bg-alt` |
| Hero wash | `radial(rgba(120,140,180,.10)) + linear(#000→#04060d)` | `radial(rgba(37,99,235,.10)) + linear(#fff→#eef4ff)` |
| Hero veil | black vignette → `#000` | white vignette → `#fff`, same stops |
| Grain | `opacity .045`, normal blend | `opacity .028`, **`mix-blend-mode: multiply`** |
| Nav pill | `#050505→#2a2a2a→#4a4a4a` | `#ffffff→#f6f8fc→#eaf0fa`, hairline `rgba(15,23,42,.12)` |
| Nav shine | `rgba(255,255,255,.16)` | `rgba(37,99,235,.10)` |
| Solid CTA | white gradient, `#111` label | `#3b82f6→#2563eb→#1d4ed8`, white label |
| Ghost CTA | dark frost | white frost, `rgba(15,23,42,.14)` hairline, same `blur(16px)` |
| Badge | `#7d7d7d→#2a2a2a→#0a0a0a` | `#eaf1ff→#ffffff→#f4f7fd`, `rgba(37,99,235,.22)` hairline, `#1d4ed8` label |
| H1 emphasis | `#9a9a9a` | blue gradient clip `#2563eb → #4f46e5 55% → #0891b2` |
| Dust | `rgba(255,255,255,…)` | `rgba(37,99,235,…)` at ~0.5 the alpha |

Two treatments do **not** survive a straight inversion and are called out because getting
them wrong is what makes a light port look cheap:

- **The shine sweep.** White-on-white is invisible. On the nav pill and every light
  surface the sweep is blue-tinted. It stays white only on the solid blue CTA, where
  white *is* the contrast.
- **The grain.** Additive white noise on white reads as dust on a scanned page. It must
  multiply, and at roughly half the reference opacity.

---

## 4. The ribbon — the one real inversion

The reference ribbon is **additive light**: it accumulates brightness into a small
offscreen buffer, tone-maps `1 − exp(−3.4·a)`, and writes straight-alpha white
`rgb(250,252,255)`. The upscale is the glow. On white this renders as nothing.

The light version keeps **every line of the geometry and lighting maths** — spine, twist,
taper, yaw, perspective, key light `(0.18, 0.46, −0.87)`, exponent `3.2`, edge softening,
bilinear splat, buffer size, sample counts. Only the write-out changes:

```
d  = 1 − exp(−3.4 · a)          // unchanged density
α  = d · 0.62                    // ink is lighter than light was
rgb = lerp(#93b4f5, #1e40af, d)  // pale haze at the edges, deep ink at the ridges
```

So the lit ridges become the *darkest* blue rather than the brightest white. The form
reads identically; the medium changes from light to ink. The upscale still supplies the
softness — there is still no blur filter anywhere.

Dust specks become `rgba(37,99,235, 0.30·tw)`, same drift and wrap.

---

## 5. One viewport → a scrolling page

The reference locks the desktop to a single frame (`html, body { overflow: hidden }` at
≥901px). That lock is **deleted**. Three consequences:

**5.1 The hero layers stop being `position: fixed`.**

They become `position: absolute; inset: 0` inside `.hero-shell`
(`position: relative; overflow: hidden; min-height: 100dvh`). Two reasons:

- Fixed layers would bleed the ribbon behind every section below the fold.
- The React port lives inside transformed ancestors, where `fixed inset-0` does not
  resolve against the viewport. Absolute-in-shell ports without surprises.

The canvas therefore sizes to `shell.clientWidth/clientHeight`, not `window.inner*`, and
tracks it with a `ResizeObserver`.

**5.2 Below-fold motion switches trigger.**

The hero keeps the `--d`-staggered `.appear` classes fired on load. Everything below the
fold uses the same keyframes driven by an IntersectionObserver that adds `.in-view` —
identical semantics to `frontend/src/components/Reveal.jsx`, which the React port reuses
verbatim. `threshold: 0.15`, fire once, `--d` honoured as `transition-delay`.

**5.3 The header becomes sticky.**

`position: sticky; top: 0`. Transparent over the hero; on scroll past 24px it gains
`.is-stuck` → `background: rgba(255,255,255,0.72)`, `backdrop-filter: blur(14px)`, and a
`--border` bottom hairline. 0.3s ease.

---

## 6. Sections

Order, and where the copy comes from. **All copy is lifted from the existing SPA — none
of it is invented.**

| # | Section | Source |
|---|---|---|
| 1 | Hero + stats strip | `PROMPT.md`, unchanged copy |
| 2 | Orchestrate marquee | `sections/OrchestrateMarquee.jsx` |
| 3 | Workflow designer | `sections/WorkflowDesigner.jsx` |
| 4 | Observability | `sections/Observability.jsx` |
| 5 | Capabilities (9 cards) | `sections/Capabilities.jsx` |
| 6 | Enterprise | `sections/Enterprise.jsx` |
| 7 | Final CTA | `sections/FinalCTA.jsx` |
| 8 | Footer | `components/Footer.jsx` |

**Section vocabulary** — every section below the hero is built from the same four parts,
so the page reads as one system:

1. **Eyebrow pill** — `--accent` label, `--accent-wash` fill, `rgba(37,99,235,.20)` hairline,
   `border-radius: 999px`, 12px, letter-spacing `0.02em`.
2. **Heading** — Satoshi 600, `clamp(30px, 3.2vw, 44px)`, `letter-spacing: -0.035em`,
   `--text`. At most one Playfair italic phrase per page below the hero — the hero owns
   that gesture and it cheapens if repeated.
3. **Body** — `--muted`, 16px, `line-height: 1.65`, `max-width: 46ch`.
4. **Card** — `--bg-alt` fill, `--border` hairline, `--card-r`. Hover: border
   `rgba(37,99,235,.40)`, `translateY(-4px)`, `0 12px 28px rgba(15,23,42,.06)`.

**Marquee** — the reference forbids external assets, and the brand PNGs live in the SPA at
`/public/assets`, not next to this file. The static proof therefore uses monogram tiles
(initials in a rounded square). The React port swaps in the real `<img>` chips from
`OrchestrateMarquee.jsx`. Track duplicated once, `32s linear infinite`, edge-masked, paused
on hover.

**Observability** — the throughput chart, KPI tiles, and log lines are an *interface
illustration*, drawn from the same shapes the SPA already renders. They are never
presented as claims. No number in a heading, stat, or eyebrow is invented — that rule from
`PROMPT.md` holds for the whole page.

---

## 7. Icons

Every icon inline SVG. The three stats icons keep their reference geometry with inverted
fills:

1. **Dual-pill / workflow** — left rect `#2563eb@0.42 → #93c5fd@0.68`, right rect the
   inverse, connector `#1d4ed8`.
2. **Governance tile** — square `x=2.4 y=2.4 w=19.2 h=19.2 rx=6.2` fill `#2563eb`, check
   stroke `#ffffff` width `1.85` round.
3. **Three-node run chain** (`viewBox="0 0 40 22"`) — connector `#cbd5e1` width 1.4;
   circles at cx 10.2 (`#e2e8f0`, `#94a3b8` ring, `#475569` core), cx 20.2 (`#0f172a`,
   `#ffffff` core), cx 30.2 (`#2563eb`, `#ffffff` core).

Favicon: the same two-lane mark, `fill='%232563eb'`.

---

## 8. Motion

Hero keyframes, names, durations, delays and the `--d` ladder: unchanged from `PROMPT.md`
§ *Entrance motion*.

Added for the scrolling body:

- `.reveal` — `opacity: 0; transform: translateY(28px)`, transition
  `0.7s cubic-bezier(0.22, 1, 0.36, 1)`, `--d` as `transition-delay`. `.in-view` clears it.
- Marquee `marquee 32s linear infinite`.
- DAG edges `dash 1s linear infinite`, `stroke-dasharray: 6 6`.
- Chart bars grow on reveal, 40ms stagger.

`prefers-reduced-motion: reduce` kills every transition and animation, forces `.appear`
and `.reveal` to their rest state, stops the rAF loop, and renders the ribbon as one
static frame. Same as the reference.

---

## 9. Accessibility floor

- Body copy `--muted` on `#ffffff` — 4.8:1. Passes AA.
- `--accent` on `#ffffff` — 4.7:1. Passes AA for body text.
- White on `--accent` — 4.7:1. The solid CTA passes.
- `--faint` is 2.9:1 and is restricted to decorative numerals and timestamps that are
  duplicated in adjacent text.
- Every decorative layer — grain, wash, canvas, veil, watermark — carries `aria-hidden`.
- The burger toggles `aria-expanded` and swaps its label between Open/Close menu.
- Focus is never suppressed; interactive elements take a `2px` `--accent` outline at
  `2px` offset.

---

## 10. Port contract

What the React phase takes from this file, so nothing is re-derived by eye:

- § 2 tokens → `src/index.css` `:root`, plus `brand` colours in `tailwind.config.js`.
- § 3 surfaces → `@layer components` classes `.btn`, `.btn-solid`, `.btn-ghost`,
  `.pill-nav`, `.eyebrow`, `.card`, `.grain`. The shine needs `::before`/`::after`, which
  is why these are CSS classes and not Tailwind strings.
- § 4 ribbon → `src/components/FieldCanvas.jsx`, the loop moved into `useEffect` with the
  visibility, reduced-motion and resize handling intact.
- § 6 vocabulary → `PrimaryButton`, `GhostButton`, `Pill`, `SectionHeading` in
  `src/components/ui.jsx`.
- § 8 reveal → the existing `src/components/Reveal.jsx`, unchanged.
