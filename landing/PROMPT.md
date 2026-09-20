# AutoOps landing page — build prompt

The AutoOps version of the "Vesper.ai" single-viewport prompt. What changed and why is
listed at the bottom.

---

Recreate this exact single-viewport landing page for **AutoOps**. Document title:
`AutoOps — Autonomous Operations, Governed Perfectly`. `lang="en"`. One HTML file with
inline CSS and a small IIFE for the menu + animation fallback. Pure black `#000000`.
No extra sections, cards, forms, pricing tables, or footer beyond the three stats.
Do **not** add a video, WebGL, Three.js, or Lottie. Do **not** reference any external
image or CDN asset — the hero animation is drawn in a 2D canvas.

Force black immediately so the page can never flash white:

- First CSS rule: `html, body { background: #000000 !important; color: #ffffff; }`
- Body attribute: `style="background:#000;color:#fff"`
- Then again: `html, body { background: #000000; background: var(--bg, #000000); color: #ffffff; color: var(--text, #ffffff); }`

---

## Fonts (exact — the AutoOps faces)

Satoshi is the AutoOps UI face; Playfair Display italic is the emphasis face. Both are
already used by the product SPA, so the landing page and the app agree.

Self-hosted WOFF2s sitting next to `index.html`:

```css
@font-face {
  font-family: "Satoshi";
  font-style: normal;
  font-weight: 400 700;
  font-display: swap;
  src: url("satoshi.woff2") format("woff2");
}
@font-face {
  font-family: "Playfair Display";
  font-style: italic;
  font-weight: 400 700;
  font-display: swap;
  src: url("playfair-display-italic.woff2") format("woff2");
}
```

Stacks:

- UI / logo / nav / buttons / badge / lede / stats / **H1**:
  `"Satoshi", "Inter", system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif`
- **Only** the H1 phrase `governed perfectly.`:
  `"Playfair Display", "Times New Roman", Times, serif`

If those files are missing, load these two stylesheets instead:

```
https://api.fontshare.com/v2/css?f[]=satoshi@400,500,600,700&display=swap
https://fonts.googleapis.com/css2?family=Playfair+Display:ital,wght@1,400..700&display=swap
```

Body: `-webkit-font-smoothing: antialiased; -moz-osx-font-smoothing: grayscale;
text-rendering: optimizeLegibility; overflow-x: hidden; position: relative`.
`html { scroll-behavior: smooth }`. Universal reset: `box-sizing: border-box; margin: 0;
padding: 0`. Links `color: inherit; text-decoration: none`. Buttons `font-family: inherit`.

---

## Assets (there are none — every layer is drawn)

**1. Hero field — three fixed sheets, all `z-index: 0`, stacked by DOM order.**

`.hero-photo` is the base wash:

```css
background:
  radial-gradient(120% 82% at 50% 32%, rgba(120,140,180,.10) 0%, rgba(0,0,0,0) 62%),
  linear-gradient(180deg, #000000 0%, #000000 58%, #04060d 100%);
```

Entrance: `photo-in` 1.6s, `opacity 0 / scale(1.04)` → rest.

`.hero-canvas` (`#field`) is the **live ribbon**, drawn in a 2D canvas — no video, no
WebGL, no library. It fades in via `.is-lit` (`opacity 0 → 1`, 1.4s) on the first frame.

A twisted parametric band, sampled as a point cloud and accumulated into a small
offscreen buffer that is then upscaled onto the visible canvas — **the upscale is the
glow**; there is no blur filter anywhere.

- Spine, `u ∈ [0,1]` along the band, `ph = u·2π + t·0.22`:
  `sx = (u − 0.5)·2.4`, `sy = 0.30·sin(ph)`, `sz = 0.28·cos(ph)` — two lobes, one
  rising, one falling, undulating in depth.
- Twist `th = u·6.6 + t·0.28`; the cross-section rotates as it runs, which is what
  makes the pinch mid-span.
- Taper `pow(sin(u·π), 0.75)`; half-width `hw = 0.46·(0.14 + 0.86·taper)`.
- Whole form yaws slowly at `t·0.11`. Perspective `f = 3.3 / (3.3 + z)`.
- Lighting: key `(0.18, 0.46, −0.87)`, brightness `pow(|N·L|, 3.2)·0.40 + 0.014` —
  the high exponent is what gives the bright folded ridges.
- Edge softening `1 − pow(|v|, 3.2)`; each sample bilinearly splatted into 4 cells.
- Tone map `1 − exp(−3.4·a)` into straight-alpha white `rgb(250, 252, 255)`.
- Sampling: `NU × NV` = 460 × 120 desktop, 300 × 72 phone. Offscreen buffer is
  `min(W, 900) × 0.5` wide — deliberately small, because that is the softness.
- Placement: `cy = oh × 0.40`, `scale = ow × 0.285` on desktop; on a tall phone the
  copy owns the middle, so `cy = oh × 0.24`, `scale = ow × 0.34` and the ribbon rides high.
- Dust: `min(420, W·H/3600)` 1px specks, twinkling on `sin(t·1.6·s + p)`, drifting up
  and wrapping at the top.
- `t += 0.0075` per frame. Stops on `visibilitychange`; renders one static frame and
  stops under `prefers-reduced-motion`; re-renders on a rAF-throttled resize.

`.hero-veil` is the vignette that sinks it back to black behind the copy:

```css
background:
  radial-gradient(118% 90% at 50% 28%, rgba(0,0,0,0) 26%, rgba(0,0,0,.5) 66%, #000 100%),
  linear-gradient(180deg, rgba(0,0,0,0) 26%, rgba(0,0,0,.55) 48%, rgba(0,0,0,.93) 62%, #000 78%);
```

**2. Grain** — `.grain` at `z-index: 100`, `opacity: .045`, an inline
`feTurbulence` SVG data URI (`baseFrequency 0.85`, `numOctaves 3`, 140×140, `stitchTiles`).

**3. Favicon (exact data URI)** — the same mark as the logo, white:

```
data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='white'%3E%3Crect x='3.4' y='2.6' width='6.4' height='18.8' rx='3.2'/%3E%3Crect x='14.2' y='2.6' width='6.4' height='18.8' rx='3.2'/%3E%3Crect x='8.6' y='10.9' width='6.8' height='2.2' rx='1.1'/%3E%3C/svg%3E
```

No images. Every icon is inline SVG.

---

## Tokens (default / ~1440px)

```
--bg: #000000
--text: #ffffff
--muted: #9a9a9a
--stat: #d8d8d8
--border: rgba(255, 255, 255, 0.16)
--border-soft: rgba(255, 255, 255, 0.12)
--accent: #60a5fa        /* AutoOps blue — hover glows and the third stat icon */

--logo: 15.5px
--logo-mark: 22px
--nav: 14px
--nav-h: 40px
--btn: 13.5px
--btn-h: 40px
--hero-btn-h: 42px
--h1: 48px
--lede: 15.5px
--badge: 12.5px
--stat-size: 13.5px
--header-y: 22px
--header-x: 40px
--stats-x: 72px
--stats-y: 36px
--hero-gap: 85px
--copy-max: 860px
--lede-max: 470px
```

---

## Layer stack (back → front)

1. `html/body` black
2. `.hero-photo` → `.hero-canvas` → `.hero-veil` (all `z-index: 0`, DOM order stacks them)
3. `.page` — `position: relative; z-index: 1; display: grid; grid-template-rows: auto 1fr auto; min-height: 100vh / 100dvh`
4. `.grain` at `z-index: 100`

Markup order inside body:

```
.grain
.hero-photo
canvas#field.hero-canvas
.hero-veil
.page
  .menu-backdrop
  header.header
  main.hero#top
  footer.stats
script
```

---

## Header — 3-column grid

`display: grid; grid-template-columns: 1fr auto 1fr; align-items: center;`
Padding: `var(--header-y) var(--header-x) 10px`. `z-index: 50; position: relative`.

**Left — logo** `a.logo.appear.appear--scale` → `#top`, `aria-label="AutoOps"`.
`display: inline-flex; align-items: center; gap: 9px; justify-self: start;
font-size: var(--logo); font-weight: 600; letter-spacing: -0.03em; color: #fff`.

Mark SVG 22×22, `viewBox="0 0 24 24"`, `fill="currentColor"` — two lanes and one bridge,
the orchestration mark. No rotation:

- `rect x="3.4" y="2.6" width="6.4" height="18.8" rx="3.2"`
- `rect x="14.2" y="2.6" width="6.4" height="18.8" rx="3.2"`
- `rect x="8.6" y="10.9" width="6.8" height="2.2" rx="1.1"`

Wordmark: `Auto` + `<span class="logo-suffix">Ops</span>` at `font-weight: 400`.

**Center — nav** `#site-nav`, `aria-label="Primary"`, `display: flex; align-items: center;
gap: 8px; justify-self: center`. These are the SPA's real routes, not anchors.

| Label | href | appear class |
|---|---|---|
| Platform | `/features` | `appear--scale` |
| Docs | `/docs` | `appear--soft` |
| Pricing | `/pricing` | `appear--scale` |
| Sign in | `/login` | `appear--soft` |

Each link is a **liquid-metal pill**: `height: var(--nav-h); padding: 0 18px;
border-radius: 7px; overflow: hidden; position: relative;`
`border: 1px solid rgba(198,198,198,0.55)`
`background: linear-gradient(105deg, #050505 0%, #2a2a2a 48%, #4a4a4a 100%)`
color `#f3f3f3`, `font-size: var(--nav)`, weight 400, `letter-spacing: -0.01em`,
`white-space: nowrap`. Transition: `background / border-color / box-shadow 0.35s ease`.

Shine `::before`: `linear-gradient(115deg, transparent 30%, rgba(255,255,255,0.16) 50%,
transparent 70%)`, idle `translateX(-120%)`, hover `translateX(120%)` over `0.6s ease`.

Hover: border `rgba(235,235,235,0.9)`, gradient `#111 → #3a3a3a 45% → #6a6a6a`,
glow `0 0 18px rgba(200,210,230,0.18)`.

**Right — header CTA:** `a.btn.btn-solid.header-cta.appear.appear--scale` "Start Free"
→ `/signup`, `justify-self: end`.

**Burger** (hidden ≥901px, `display: none` → `display: grid` on phone): 42×42,
`border-radius: 6px`, `border: 1px solid var(--border)`, `background: rgba(8,8,8,0.55)`,
`z-index: 60`, `aria-controls="site-nav"`, `aria-expanded="false"`, label "Open menu".
Three white bars 16×1.5px, gap 5px, `border-radius: 1px`. Hover: border
`rgba(255,255,255,0.32)`, bg `rgba(255,255,255,0.05)`. Open (`body.menu-open`):
bar 1 `translateY(6.5px) rotate(45deg)`, bar 2 `opacity: 0`,
bar 3 `translateY(-6.5px) rotate(-45deg)`, 0.25s / 0.2s.

---

## Buttons (shared liquid-glass language)

`.btn`: `position: relative; isolation: isolate; overflow: hidden; display: inline-flex;
align-items: center; justify-content: center; height: var(--btn-h); padding: 0 16px;
border-radius: 6px; font-size: var(--btn); font-weight: 500; letter-spacing: -0.02em;
line-height: 1; white-space: nowrap; cursor: pointer`. Transitions 0.35s on background,
border, shadow, color, filter.

Shine `::after`: `linear-gradient(115deg, transparent 20%, rgba(255,255,255,0.45) 48%,
transparent 76%)`, idle `translateX(-130%)`, hover `translateX(130%)` in `0.65s ease`.

**Solid:**
`background: linear-gradient(180deg, #ffffff 0%, #e7e7e7 48%, #cfcfcf 100%)`
`color: #111; border: 1px solid #fff`
`box-shadow: inset 0 1px 0 rgba(255,255,255,0.95)`
Hover: `#fff → #f3f6ff 42% → #d5def2`, border `#f2f6ff`,
`inset 0 1px 0 #fff, 0 0 22px rgba(120,180,255,0.35), 0 8px 18px rgba(255,255,255,0.12)`

Hero solid hover glow is slightly stronger:
`0 0 26px rgba(120,180,255,0.4), 0 8px 18px rgba(255,255,255,0.14)`.

**Ghost (header-level):**
`background: linear-gradient(135deg, rgba(255,255,255,0.1), rgba(0,0,0,0.45) 50%, rgba(160,175,200,0.08))`
`color: #fff; border: 1px solid rgba(198,198,198,0.45)`
`box-shadow: inset 0 1px 0 rgba(255,255,255,0.12)`
Hover: `rgba(190,220,255,0.18) → rgba(0,0,0,0.35) 48% → rgba(160,200,230,0.16)`,
border `rgba(210,230,255,0.75)`,
`inset 0 1px 0 rgba(255,255,255,0.22), 0 0 20px rgba(96,165,250,0.22)`

**Hero ghost** (stronger frost):
`background: linear-gradient(135deg, rgba(255,255,255,0.12), rgba(0,0,0,0.5) 46%, rgba(150,170,200,0.1))`
`border: 1px solid rgba(198,198,198,0.55)`
`backdrop-filter: blur(16px); -webkit-backdrop-filter: blur(16px)`
Hover glow `0 0 24px rgba(96,165,250,0.28)`, border `rgba(210,230,255,0.8)`.

Hero action buttons: height `var(--hero-btn-h)`, padding `0 18px`.

---

## Hero (bottom-centered, NOT vertically centered)

`.hero`: `display: flex; align-items: flex-end; justify-content: center;
padding: 8px 24px var(--hero-gap); min-height: 0`.

`.hero-copy`: `position: relative; z-index: 1; flex-direction: column;
align-items: center; text-align: center; max-width: var(--copy-max); width: 100%`.

**Badge** `.badge.appear.appear--pop` — text `Automate. Orchestrate. Operate.` (the
product tagline, verbatim)
`inline-flex; gap: 8px; margin-bottom: 22px; padding: 9px 15px; border: 0; border-radius: 5px`
`background: linear-gradient(90deg, #7d7d7d 0%, #2a2a2a 52%, #0a0a0a 100%)`
color `#f2f2f2`, `font-size: var(--badge)`, weight 400, `letter-spacing: -0.01em`.

Sparkle SVG 18×20, white, `filter: drop-shadow(0 0 3px rgba(255,255,255,0.45))`, path:

```
M12 2.6C12.55 2.6 12.88 3.15 13.08 4.7c.62 4.7 1.52 5.6 6.22 6.22 1.55.2 2.1.53 2.1 1.08s-.55.88-2.1 1.08c-4.7.62-5.6 1.52-6.22 6.22-.2 1.55-.53 2.1-1.08 2.1s-.88-.55-1.08-2.1c-.62-4.7-1.52-5.6-6.22-6.22C3.15 12.88 2.6 12.55 2.6 12s.55-.88 2.1-1.08c4.7-.62 5.6-1.52 6.22-6.22C11.12 3.15 11.45 2.6 12 2.6Z
```

**H1** two masked lines, Satoshi 500, `letter-spacing: -0.045em`, `line-height: 1.12`,
`#fff`, column, centered:

- Line 1: `Autonomous operations,`
- Line 2: `<em>governed perfectly.</em>`

`.headline-line`: `display: block; overflow: hidden; padding: 0.06em 0.15em 0.14em`.
`em`: Playfair Display italic 400, `font-size: 1.08em`, `letter-spacing: -0.03em`,
color **`#9a9a9a`** (not white).

**Lede** `.lede.appear.appear--soft`, `max-width: var(--lede-max)`, `margin-top: 18px`,
color `#9a9a9a`, `15.5px`, weight 400, `line-height: 1.55`, `letter-spacing: -0.015em`:

`Design workflows, run jobs on your own infrastructure, and let AI agents execute them — with approvals, audit and tenant isolation built into the runtime.`

**Actions** `.hero-actions`: flex, wrap, center, gap `10px`, `margin-top: 26px`.

1. Solid `.appear.appear--btn`: `Start Free` → `/signup`
2. Ghost `.appear.appear--side`: `Book a demo` → `/book-demo`

---

## Stats footer

`.stats`: flex, `align-items: center; justify-content: space-between; gap: 24px;`
padding `0 var(--stats-x) var(--stats-y)` and
`padding-bottom: max(var(--stats-y), env(safe-area-inset-bottom))`, color `#d8d8d8`.

Each `.stat.appear.appear--stat`: `inline-flex; align-items: center; gap: 14px;
font-size: var(--stat-size); letter-spacing: -0.015em; white-space: nowrap`.
Icon 20×20. Wide icon 38×21.

These are **capability claims, not metrics** — no invented numbers. Swap in real
figures only when there are real figures to swap in.

**1. Dual-pill / workflow icon** (`viewBox="0 0 24 24"`):
Left rect `x=3.4 y=2.6 w=7.2 h=18.8 rx=3.6` fill linear `#ffffff@0.38 → #3a3a3a@0.62`
(`x1=3 y1=2 x2=14 y2=22`).
Right rect `x=13.4 y=2.6` same size, inverted `#3a3a3a@0.38 → #ffffff@0.62`.
Connector `x=9.2 y=10.9 w=5.6 h=2.2 rx=1.1` fill `#4a4a4a`.
Label: `Workflows, jobs and runs in one engine`

**2. Governance tile:** white rounded square `x=2.4 y=2.4 w=19.2 h=19.2 rx=6.2` fill
`#ffffff`. Check `#111` stroke-width `1.85` round: `M7.6 12.3l3.1 3.1 5.7-6.2`.
Label: `Approvals, audit and compliance built in`

**3. Three-node run chain** (`viewBox="0 0 40 22"`, class `stat-icon-wide`):
connector `M10.2 11h20` stroke `#3f3f3f` width 1.4; then circles r=6.2/6.4 at
cx 10.2 (`#2b2b2b`, `#6a6a6a` ring, `#e8e8e8` core), cx 20.2 (`#ffffff`, `#111` core),
cx 30.2 (`#60a5fa`, `#ffffff` core).
Label: `Self-hosted — your infrastructure, your data`

---

## Entrance motion (exact)

`.appear` resting opacity is **1** (so the page is never blank if animations fail).
`animation-duration: 1.05s; animation-fill-mode: both;
animation-timing-function: cubic-bezier(0.16, 1, 0.3, 1);
animation-delay: var(--d, 0.08s)`.
`both` applies the 0% keyframe during the delay, so they still hide then fade in when
animations run.

On each element's own `animationend`, add `.is-in` (`animation: none; opacity: 1;
transform: none; clip-path: none; filter: none`). Same for `.hero-photo.is-in`.

JS fallback: after two `requestAnimationFrame`s, if `el.getAnimations()` has nothing
`running` or `finished`, add `.is-in` to every `.appear` and `.hero-photo`.

| Element | Modifier | `--d` |
|---|---|---|
| Logo | `appear--scale` | 0.08s |
| Nav 1 Platform | `appear--scale` | 0.16s |
| Nav 2 Docs | `appear--soft` | 0.28s |
| Nav 3 Pricing | `appear--scale` | 0.40s |
| Nav 4 Sign in | `appear--soft` | 0.52s |
| Header CTA + burger | `appear--scale` | 0.34s |
| Badge | `appear--pop` | 0.22s |
| H1 line 1 | `appear--mask` | 0.42s |
| H1 line 2 | `appear--mask` | 0.62s |
| Lede | `appear--soft` | 0.82s, duration **1.25s** |
| Solid CTA | `appear--btn` | 0.96s |
| Ghost CTA | `appear--side` | 1.10s |
| Stat 1 | `appear--stat` | 1.12s |
| Stat 2 | `appear--stat` | 1.28s |
| Stat 3 | `appear--stat` | 1.44s |

Keyframes (all end at opacity 1 / identity transform):

- `in-scale`: `opacity 0, scale(0.84)` → 1
- `in-soft`: `opacity 0, translateY(14px)` → 0
- `in-mask`: `opacity 0, translateY(40%)` → 0 (clipped by `.headline-line` overflow)
- `in-pop`: 0 `scale(0.9)` → 70% `scale(1.03)` → 100% `scale(1)`
- `in-btn`: `translateY(18px) scale(0.94)` → rest
- `in-side`: `translateX(22px)` → 0
- `in-stat`: `translateY(20px)` → 0
- `in-star` on `.badge-star`, `0.9s`, delay `0.28s`, both: `scale(0.2) rotate(-50deg)` → 65% `scale(1.2) rotate(8deg)` → rest
- `in-em` on `h1 em`, `1.2s`, delay `0.72s`, both: `opacity 0.35; filter: blur(4px)` → sharp
- `photo-in` on `.hero-photo`, `1.6s`, both: `opacity 0; scale(1.04)` → rest

`prefers-reduced-motion: reduce`: `transition: none !important; animation: none !important`
on `*, *::before, *::after`. Force `.appear, .hero-photo, .hero h1 em, .badge-star` to
`opacity: 1; transform: none; clip-path: none; filter: none`.

---

## Responsive (copy these breakpoints)

**≥1600:** logo 17 / mark 24 / nav 15 / nav-h 44 / btn 15 / btn-h 44 / hero-btn 48 /
h1 **64** / lede 18 / badge 13.5 / stat 15 / header 28×64 / stats 96×44 / copy 980 /
lede-max 540. Nav pad `0 20px`. Badge mb 26, lede mt 22, actions mt 30 gap 12.
Icons 22, wide 45×24.

**≥1920:** logo 18 / mark 26 / nav 16 / nav-h 48 / btn 16 / btn-h 48 / hero-btn 52 /
h1 **76** / lede 20 / badge 14.5 / stat 16 / header 32×80 / stats 120×52 / copy 1120 /
lede-max 620. Nav gap 10, pad `0 22px`. Buttons pad `0 22px`. Badge pad `10px 15px`.
Wide icon 48×26.

**≥2560:** h1 **88**, lede 22, header-x 120, stats-x 160, copy 1280, lede-max 680.

**1280–1599:** h1 54, lede 16, header-x 48, stats-x 80, copy 900.

**901–1279:** logo 15, nav 13, nav-h 36, btn 13, btn-h 38, hero-btn 40, h1 **42**,
lede 15, badge 12, stat 12.5, header 16×28, stats 36×28, hero-gap 64, copy 760,
lede-max 440. Nav pad `0 14px`. Badge mb 16, lede mt 14, actions mt 20.

**≥901 and max-height 850:** header-y 14, stats-y 24, hero-gap 48, h1 40;
badge mb 12, lede mt 12, actions mt 16.

**≥901 and max-height 720:** h1 34, lede 14, hero-gap 32, stats-y 18, nav-h 30,
btn-h 34, hero-btn 36, badge mb 8.

**≥901 desktop lock:** `html, body { height: 100%; overflow: hidden }`.
`.page { height: 100vh / 100dvh; overflow: hidden }`. One frame, **no scroll**.

**≤900 phone:** no 100vh lock; `html/body` `height: auto; overflow-y: auto`.
Header `grid-template-columns: 1fr auto auto`, gap 8, safe-area padding.
Logo / CTA / burger `z-index: 80`. Burger shown.

Full-screen menu: `.menu-backdrop` `display: block; position: fixed; inset: 0;
z-index: 40; background: rgba(8,8,8,0.42)`, idle `opacity: 0; visibility: hidden`.
Open: opacity 1 + **`backdrop-filter: blur(24px)`**, 0.28s. Nav becomes full-viewport
column, `z-index: 45`, transparent, centered, gap 12, padding `96px 22px 32px` with
`padding-top: max(96px, calc(env(safe-area-inset-top) + 88px))`. Links full-width,
height 56, `font-size: 19px`, `border-radius: 10px`. Escape / backdrop / nav click /
resize ≥901 closes. Toggle `aria-expanded` and label Open/Close menu.
`body.menu-open { overflow: hidden }`.

Hero pad `20px 20px 64px`, still `align-items: flex-end`. Stats **column**, centered,
gap 16, `white-space: normal`. Copy/lede max-width 100%. Tokens: logo 16, btn 15 / 46,
hero-btn 48, h1 **36**, lede 16.5, badge 13.5, stat 15, header 16×18, stats 20×28,
hero-gap 36.

**≤560:** h1 34, lede 16, header-x 16. Hero actions **column**, buttons `width: 100%`.

---

## JS (two IIFEs, only this)

**Entrance + menu:**

1. Each `.appear` → own `animationend` → add `is-in` (`once: true`).
2. If animations are not running after two rAFs, force `.is-in` on all `.appear` and `.hero-photo`.
3. Burger toggles `body.menu-open`.
4. Nav links, the backdrop and Escape close the menu.
5. Resize to `(min-width: 901px)` closes the menu.

**The field:** the ribbon + dust loop described under *Assets*. It returns immediately if
the canvas or its 2D context is missing, so the page degrades to the base wash alone.

---

## What changed from the source prompt, and why

| Source | AutoOps | Reason |
|---|---|---|
| Inter + Instrument Serif | Satoshi + Playfair Display italic | The SPA already ships these two faces; the landing page and the app now use the same type. |
| `Train AI agents on your workflows in minutes.` | `Autonomous operations, governed perfectly.` | The real product headline, lifted from `frontend/src/sections/Hero.jsx`. |
| Badge `Operational AI Infrastructure` | `Automate. Orchestrate. Operate.` | The real AutoOps tagline, already in `frontend/index.html`. |
| Hero `.mp4` on a CloudFront URL | The same ribbon, drawn live in a 2D canvas | Video and WebGL were both off the table and the source asset belongs to someone else. Renders at ~16.7 ms/frame at 1440×900, ships as zero bytes of asset. |
| Rotated dual-pill logo mark | Two lanes + one bridge, unrotated | Reads as orchestration and does not copy another brand's mark. |
| Nav anchors `#benefits` `#how-it-works` `#faqs` `#pricing` | `/features` `/docs` `/pricing` `/login` | Those sections do not exist here; these are real SPA routes. |
| CTAs → `#start` / `#demo` | `/signup` / `/book-demo` | Real routes in `frontend/src/App.jsx`. |
| Stats `4.2M+ workflows`, `92% reduction`, `180+ teams` | Three capability statements | **No invented metrics.** Replace with real figures only when real figures exist. |
| Neutral `rgba(186,208,255)` glows | AutoOps blue `#60a5fa` / cyan `#22d3ee` | Ties the black page back to the product palette. |
