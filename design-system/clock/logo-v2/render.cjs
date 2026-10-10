/** Rasterize the SVG proposals and check actual small-size pixel bounds. */
const fs = require('node:fs');
const path = require('node:path');
const sharp = require('sharp');

const root = __dirname;
const qa = path.join(root, 'qa');
fs.mkdirSync(qa, { recursive: true });
const manifest = JSON.parse(fs.readFileSync(path.join(root, 'manifest.json'), 'utf8'));
const results = [];

function paths(svg, color) {
  return [...svg.matchAll(/<path\s[^>]*\/>/g)].map(match => match[0])
    .join('').replaceAll('currentColor', color);
}

async function main() {
  for (const name of ['overview.svg', ...manifest.suites.map(s => `${s.folder}/sheet.svg`)]) {
    await sharp(path.join(root, name), { density: 144 })
      .resize({ width: name === 'overview.svg' ? 1408 : 1280 })
      .png().toFile(path.join(root, name.replace('.svg', '.png')));
  }
  const parts = ['<rect width="1512" height="1070" fill="#F8F8F5"/>'];
  const text = (x, y, value, size = 13, color = '#646965') =>
    `<text x="${x}" y="${y}" font-size="${size}" fill="${color}">${value}</text>`;
  parts.push(text(28, 38, 'CLOCK / SMALL-SIZE REVIEW — 20px, 1× render', 18, '#202322'));
  for (const [suiteIndex, suite] of manifest.suites.entries()) {
    const suiteRoot = path.join(root, suite.folder);
    const names = ['brand-mark', ...suite.mapping.filter(m => m.name !== 'ic_clock').map(m => m.name)];
    const y = 80 + suiteIndex * 326;
    parts.push(text(28, y + 14, `${suite.id} / ${suite.name}`, 17, '#202322'));
    for (const [themeIndex, theme] of ['light', 'dark'].entries()) {
      const top = y + 33 + themeIndex * 102;
      const color = theme === 'light' ? '#646965' : '#B8B8BC';
      const background = theme === 'light' ? '#FFFFFF' : '#1B1B1D';
      parts.push(`<rect x="28" y="${top}" width="1456" height="90" rx="9" fill="${background}"/>`);
      for (const [i, key] of names.entries()) {
        const svg = fs.readFileSync(path.join(suiteRoot, 'svg', key + '.svg'), 'utf8');
        const x = 51 + i * 72;
        parts.push(`<g transform="translate(${x},${top + 18}) scale(${20 / 24})">${paths(svg, color)}</g>`);
        const label = key.replace('ic_', '').replace('nav_', '').replace('_primary', '')
          .replace('_preset_thin', '').replace('_outline', '').replace('notification_', 'notify_')
          .replace('habit_unchecked', 'unchecked').replace('habit_check', 'checked')
          .replace('theme_', '').replace('chevron_', '').replace('manage_', '');
        parts.push(text(x + 10, top + 62, label, 9, color).replace('<text ', '<text text-anchor="middle" '));
        for (const size of [16, 20, 24, 32]) {
          const input = svg.replace('width="24"', `width="${size}"`)
            .replace('height="24"', `height="${size}"`).replaceAll('currentColor', color);
          const { data, info } = await sharp(Buffer.from(input)).ensureAlpha().raw()
            .toBuffer({ resolveWithObject: true });
          let minX = size, minY = size, maxX = -1, maxY = -1, pixels = 0, edge = 0;
          for (let py = 0; py < info.height; py++) {
            for (let px = 0; px < info.width; px++) {
              if (data[(py * info.width + px) * 4 + 3] > 8) {
                minX = Math.min(minX, px); minY = Math.min(minY, py);
                maxX = Math.max(maxX, px); maxY = Math.max(maxY, py); pixels++;
                if (px === 0 || py === 0 || px === size - 1 || py === size - 1) edge++;
              }
            }
          }
          if (pixels === 0 || edge > 0) throw Error(`${suite.id}/${key}/${size}/${theme}: empty or edge clipping`);
          results.push({ suite: suite.id, icon: key, theme, size, bounds: [minX, minY, maxX, maxY], pixels });
        }
      }
    }
    for (const [i, size] of [16,20,24,32].entries()) {
      parts.push(text(36 + i * 160, y + 285, `${size}px`, 11));
      const svg = fs.readFileSync(path.join(suiteRoot, 'svg', 'ic_nav_timer.svg'), 'utf8');
      parts.push(`<g transform="translate(${86 + i * 160},${y + 261}) scale(${size / 24})">${paths(svg, '#202322')}</g>`);
    }
  }
  const contact = '<svg xmlns="http://www.w3.org/2000/svg" width="1512" height="1070" viewBox="0 0 1512 1070" font-family="PingFang SC, Helvetica Neue, Arial, sans-serif">' + parts.join('') + '</svg>';
  fs.writeFileSync(path.join(qa, 'contact-sheet.svg'), contact);
  await sharp(Buffer.from(contact)).png().toFile(path.join(qa, 'contact-sheet.png'));
  fs.writeFileSync(path.join(qa, 'raster-checks.json'), JSON.stringify({ renders: results.length, checks: results }, null, 2) + '\n');
  console.log(`Rendered overview, 3 suite sheets, and small-size sheet. ${results.length} nonempty, unclipped icon renders passed.`);
}

main().catch(error => { console.error(error); process.exitCode = 1; });
