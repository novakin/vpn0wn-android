import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import * as fontkit from 'fontkit';
import { Resvg } from '@resvg/resvg-js';

const root = path.dirname(fileURLToPath(import.meta.url));
const out = path.join(root, 'assets');
fs.mkdirSync(out, { recursive: true });
const colors = {
  background: '#071220', secondaryBackground: '#0A1727', surface: '#162436',
  accent: '#2ABFF7', secondaryAccent: '#68C6FD', border: '#233B54', activeBorder: '#3E5B78',
  text: '#FAFAFA', secondaryText: '#C9D5E3', mutedText: '#94AECB',
};
const font = fontkit.openSync(path.join(root, 'fonts', 'Sora-Variable.ttf'));
const fonts = new Map();
const escapeXml = value => String(value).replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('"', '&quot;');
const num = value => Math.round(value * 10000) / 10000;
const family = weight => {
  if (!fonts.has(weight)) fonts.set(weight, font.getVariation({ wght: weight }));
  return fonts.get(weight);
};

function text(value, x, baseline, size = 18, weight = 400, fill = colors.text, anchor = 'start', tracking = 0) {
  const activeFont = family(weight);
  const layout = activeFont.layout(value);
  const scale = size / activeFont.unitsPerEm;
  const width = layout.positions.reduce((sum, pos) => sum + pos.xAdvance * scale, 0) + Math.max(0, layout.glyphs.length - 1) * tracking;
  let cursor = x - (anchor === 'middle' ? width / 2 : anchor === 'end' ? width : 0);
  let markup = '';
  layout.glyphs.forEach((glyph, i) => {
    const pos = layout.positions[i];
    markup += `<path d="${glyph.path.toSVG()}" transform="translate(${num(cursor + pos.xOffset * scale)} ${num(baseline - pos.yOffset * scale)}) scale(${num(scale)} ${num(-scale)})"/>`;
    cursor += pos.xAdvance * scale + tracking;
  });
  return `<g fill="${fill}" aria-label="${escapeXml(value)}">${markup}</g>`;
}

const frame = 'M34 8H62A24 24 0 0 1 86 32V80A24 24 0 0 1 62 104H34A24 24 0 0 1 10 80V32A24 24 0 0 1 34 8Z M41 29H55A8 8 0 0 1 63 37V75A8 8 0 0 1 55 83H41A8 8 0 0 1 33 75V37A8 8 0 0 1 41 29Z';
const slash = 'M11 106H23L86 6H74Z';
const microFrame = 'M5.5 1H10.5A3.5 3.5 0 0 1 14 4.5V11.5A3.5 3.5 0 0 1 10.5 15H5.5A3.5 3.5 0 0 1 2 11.5V4.5A3.5 3.5 0 0 1 5.5 1Z M6.5 4H9.5A1.5 1.5 0 0 1 11 5.5V10.5A1.5 1.5 0 0 1 9.5 12H6.5A1.5 1.5 0 0 1 5 10.5V5.5A1.5 1.5 0 0 1 6.5 4Z';
const microSlash = 'M2 15H4L14 1H12Z';
const gradient = '<linearGradient id="brand-gradient" gradientUnits="userSpaceOnUse" x1="0" y1="8" x2="0" y2="104"><stop offset="0" stop-color="#68C6FD"/><stop offset="1" stop-color="#2ABFF7"/></linearGradient>';

function symbol(fill = 'url(#brand-gradient)', micro = false) {
  return `<g fill="${fill}"><path fill-rule="evenodd" d="${micro ? microFrame : frame}"/><path d="${micro ? microSlash : slash}"/></g>`;
}
function mark(x, y, height, fill = 'url(#brand-gradient)', micro = false) {
  const scale = height / (micro ? 16 : 112);
  return `<g transform="translate(${num(x)} ${num(y)}) scale(${num(scale)})">${symbol(fill, micro)}</g>`;
}
function svg(width, height, body, title, desc = '') {
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}" role="img" aria-labelledby="title desc"><title id="title">${escapeXml(title)}</title><desc id="desc">${escapeXml(desc)}</desc><defs>${gradient}</defs>${body}</svg>`;
}
function save(name, width, height, body, title, desc = '', png = false) {
  const source = svg(width, height, body, title, desc);
  fs.writeFileSync(path.join(out, `${name}.svg`), source);
  if (png) fs.writeFileSync(path.join(out, `${name}.png`), new Resvg(source).render().asPng());
  return source;
}

function wordmark(fill = colors.text, symbolFill = 'url(#brand-gradient)') {
  const size = 180;
  const baseline = 154;
  const scale = size / 1000;
  const activeFont = family(800);
  const xHeight = activeFont.glyphForCodePoint(110).bbox.maxY * scale;
  const symbolScale = xHeight * 1.13 / 96;
  const symbolTop = baseline + 3 - 104 * symbolScale;
  const gaps = [9, 10, 15, 17, 10];
  let cursor = 0;
  let body = '';
  [...'vpnØwn'].forEach((char, i) => {
    if (char === 'Ø') {
      // Align using visible slash bounds (11..86), rather than the empty viewBox margins.
      body += `<g transform="translate(${num(cursor - 11 * symbolScale)} ${num(symbolTop)}) scale(${num(symbolScale)})">${symbol(symbolFill)}</g>`;
      cursor += 75 * symbolScale;
    } else {
      const glyph = activeFont.glyphForCodePoint(char.codePointAt(0));
      body += `<path fill="${fill}" d="${glyph.path.toSVG()}" transform="translate(${num(cursor - glyph.bbox.minX * scale)} ${baseline}) scale(${scale} ${-scale})"/>`;
      cursor += (glyph.bbox.maxX - glyph.bbox.minX) * scale;
    }
    cursor += gaps[i] || 0;
  });
  return { body, width: cursor, height: 206 };
}

for (const [name, fill] of [['symbol', 'url(#brand-gradient)'], ['symbol-solid', colors.accent], ['symbol-white', colors.text], ['symbol-navy', colors.background]]) {
  save(name, 96, 112, symbol(fill), 'vpnØwn symbol', 'Rounded rectangular Ø with an open counter and a diagonal slash.');
}
save('symbol-micro', 16, 16, symbol(colors.accent, true), 'vpnØwn 16px symbol', 'Optically enlarged counter; solid cyan; both slash terminals retained.', true);
save('symbol-micro-navy', 16, 16, symbol(colors.background, true), 'vpnØwn 16px navy symbol', 'Optical monochrome master for light backgrounds.', true);
save('symbol-micro-white', 16, 16, symbol(colors.text, true), 'vpnØwn 16px white symbol', 'Optical monochrome master for dark backgrounds.');
for (const size of [24, 32]) save(`symbol-${size}`, size, size, mark((size - size * 96 / 112) / 2, 0, size, colors.accent), `vpnØwn ${size}px symbol`, '', true);

for (const [name, fill] of [['wordmark-dark', colors.text], ['wordmark-light', colors.background]]) {
  const logo = wordmark(fill);
  save(name, Math.ceil(logo.width + 8), logo.height, `<g transform="translate(4 0)">${logo.body}</g>`, 'vpnØwn', 'Outlined Sora ExtraBold letters with a custom cyan Ø.');
  save(`${name}-wireguard`, Math.ceil(logo.width + 8), 263, `<g transform="translate(4 0)">${logo.body}</g>${text('WIREGUARD', logo.width / 2 + 4, 246, 23, 500, fill, 'middle', 5)}`, 'vpnØwn WireGuard', 'Optional WireGuard descriptor.');
}
for (const [name, fill] of [['wordmark-mono-white', colors.text], ['wordmark-mono-navy', colors.background]]) {
  const logo = wordmark(fill, fill);
  save(name, Math.ceil(logo.width + 8), logo.height, `<g transform="translate(4 0)">${logo.body}</g>`, 'vpnØwn', 'Monochrome outlined wordmark with the same custom Ø.');
}

save('app-icon', 1024, 1024, `<rect width="1024" height="1024" rx="200" fill="${colors.secondaryBackground}"/>${mark(512 - 650 * 96 / 112 / 2, 187, 650)}`, 'vpnØwn app icon', 'Gradient Ø on a solid navy rounded-square background.', true);

let board = `<rect width="1600" height="1200" fill="${colors.background}"/>`;
board += `<rect x="64" y="74" width="302" height="302" rx="52" fill="${colors.secondaryBackground}" stroke="${colors.border}" stroke-width="2"/>${mark(122, 110, 230)}`;
board += text('Primary app icon', 215, 410, 18, 500, colors.secondaryText, 'middle');
const hero = wordmark();
const heroScale = 675 / hero.width;
board += `<g transform="translate(444 113) scale(${num(heroScale)})">${hero.body}</g>`;
board += text('WIREGUARD', 782, 327, 22, 500, colors.mutedText, 'middle', 5);
board += `<path d="M1198 102V374" stroke="${colors.border}" stroke-width="1"/>`;
for (const [i, line] of ['Privacy.', 'Your way.', 'Always on.'].entries()) board += text(line, 1240, 157 + i * 46, 35, 600);
board += `<path d="M1240 278H1284" stroke="${colors.accent}" stroke-width="3"/>`;
board += text('Secure connections', 1240, 323, 20, 400, colors.secondaryText);
board += text('for a more open internet.', 1240, 355, 20, 400, colors.secondaryText);
board += `<path d="M64 445H1536" stroke="${colors.border}"/>`;

const centers = [248, 616, 984, 1352];
board += `<circle cx="248" cy="553" r="87" fill="none" stroke="${colors.activeBorder}" stroke-width="1.5"/>${mark(197, 493, 120)}`;
board += mark(565, 493, 120, colors.accent);
board += `<rect x="897" y="466" width="174" height="174" rx="34" fill="${colors.secondaryBackground}" stroke="${colors.border}"/>${mark(933, 493, 120, colors.text)}`;
board += `<rect x="1265" y="466" width="174" height="174" rx="34" fill="${colors.text}"/>${mark(1301, 493, 120, colors.background)}`;
const variantLabels = [['Circle', 'Profile & social'], ['Solid cyan', 'Flat brand mark'], ['Mono / dark', 'Same master geometry'], ['Mono / light', 'Same master geometry']];
variantLabels.forEach(([label, caption], i) => {
  board += text(label, centers[i], 688, 20, 600, colors.text, 'middle');
  board += text(caption, centers[i], 717, 16, 400, colors.mutedText, 'middle');
});
board += `<path d="M64 751H1536" stroke="${colors.border}"/>`;
board += text('Brand palette', 64, 798, 26, 600);
board += text('Gradient: top #68C6FD to bottom #2ABFF7', 1536, 795, 17, 400, colors.mutedText, 'end');
const swatches = [
  ['background', 'Background'], ['secondaryBackground', 'Secondary background'], ['surface', 'Surface'], ['accent', 'Primary accent'], ['secondaryAccent', 'Secondary accent'],
  ['border', 'Border'], ['activeBorder', 'Hover / active border'], ['text', 'Primary text'], ['secondaryText', 'Secondary text'], ['mutedText', 'Muted text'],
];
swatches.forEach(([key, label], index) => {
  const x = 64 + index % 5 * 297;
  const y = 827 + Math.floor(index / 5) * 131;
  board += `<rect x="${x}" y="${y}" width="284" height="53" rx="9" fill="${colors[key]}" stroke="${colors.border}"/>`;
  board += text(colors[key], x + 142, y + 82, 19, 500, colors.text, 'middle');
  board += text(label, x + 142, y + 108, 16, 400, colors.secondaryText, 'middle');
});
board += `<path d="M64 1108H1536" stroke="${colors.border}"/>`;
const footerLogo = wordmark(colors.text, colors.text);
board += `<g transform="translate(64 1127) scale(${num(120 / footerLogo.width)})">${footerLogo.body}</g>`;
board += text('Zero limits. A more open internet.', 1536, 1155, 17, 400, colors.mutedText, 'end');
const boardSvg = save('brand-board', 1600, 1200, board, 'vpnØwn brand identity', `Deep navy and cyan identity. Palette: ${Object.values(colors).join(', ')}. Exact flat swatches, Sora typography, one shared Ø geometry; a separate 16px optical master.`, true);

let proof = `<rect width="1120" height="620" fill="${colors.background}"/>${text('Native-size check', 32, 44, 24, 600)}${text('Actual size at left; 4× inspection at right', 32, 74, 16, 400, colors.mutedText)}`;
for (const [row, light] of [false, true].entries()) {
  for (const [column, size] of [16, 24, 32].entries()) {
    const x = 32 + column * 362;
    const y = 99 + row * 242;
    const bg = light ? colors.text : colors.secondaryBackground;
    const fg = light ? colors.background : colors.text;
    const fill = light ? colors.background : colors.accent;
    proof += `<rect x="${x}" y="${y}" width="332" height="220" rx="14" fill="${bg}"/>${text(`${size}px / ${size === 16 ? 'optical master' : 'main master'}`, x + 18, y + 35, 17, 500, fg)}`;
    const nativeBody = size === 16 ? symbol(fill, true) : mark((size - size * 96 / 112) / 2, 0, size, fill);
    const nativePng = new Resvg(svg(size, size, nativeBody, `vpnØwn ${size}px`)).render().asPng();
    const dataUri = `data:image/png;base64,${nativePng.toString('base64')}`;
    proof += `<image href="${dataUri}" x="${x + 42}" y="${y + 122 - size / 2}" width="${size}" height="${size}"/>`;
    proof += `<image href="${dataUri}" x="${x + 164 - size / 2}" y="${y + 122 - size * 2}" width="${size * 4}" height="${size * 4}" image-rendering="optimizeSpeed"/>`;
  }
}
proof += text('The 16px master preserves the slash tips and opens the counter. Monochrome on light.', 32, 603, 14, 400, colors.mutedText);
save('size-proof', 1120, 620, proof, 'vpnØwn native-size inspection', '16, 24 and 32 pixel symbols on dark and light backgrounds, with four times enlarged actual raster pixels.', true);

fs.writeFileSync(path.join(root, 'tokens.json'), JSON.stringify({ colors, gradient: { type: 'linear', direction: 'top-to-bottom', stops: [{ offset: 0, color: colors.secondaryAccent }, { offset: 1, color: colors.accent }], use: 'display symbol and wordmark Ø only' }, typography: { family: 'Sora', weights: { wordmark: 800, heading: 600, label: 500, body: 400 } }, usage: { microMaximumPx: 16, normalMinimumPx: 24, buttonText: colors.background, buttonBackground: colors.accent, focus: colors.accent } }, null, 2) + '\n');
fs.writeFileSync(path.join(root, 'tokens.css'), `:root {\n${Object.entries(colors).map(([key, value]) => `  --vpnown-${key.replace(/[A-Z]/g, letter => '-' + letter.toLowerCase())}: ${value};`).join('\n')}\n  --vpnown-focus: var(--vpnown-accent);\n  --vpnown-button-text: var(--vpnown-background);\n  --vpnown-button-background: var(--vpnown-accent);\n}\n`);

const rendered = new Resvg(boardSvg).render();
const sampled = {};
for (const [key, x, y] of [['background', 20, 20], ...swatches.map(([key], index) => [key, 206 + index % 5 * 297, 853 + Math.floor(index / 5) * 131])]) {
  const offset = (y * rendered.width + x) * 4;
  const actual = '#' + Buffer.from(rendered.pixels.subarray(offset, offset + 3)).toString('hex').toUpperCase();
  if (actual !== colors[key]) throw new Error(`Color mismatch for ${key}: ${actual} != ${colors[key]}`);
  sampled[key] = actual;
}
fs.writeFileSync(path.join(root, 'verification.json'), JSON.stringify({ board: { width: rendered.width, height: rendered.height }, exactSwatchSamples: sampled, sharedGeometry: ['symbol', 'symbol-solid', 'symbol-white', 'symbol-navy'], opticalMasterSize: 16 }, null, 2) + '\n');
console.log(`Generated ${fs.readdirSync(out).length} assets. All 10 swatch centers match exact palette values. Board ${rendered.width}×${rendered.height}.`);
