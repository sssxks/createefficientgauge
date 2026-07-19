// Renders the mod icon SVG to PNG.
// Usage: node render.js [srcSvg] [outPng] [size]
//   Defaults: docs/icon.svg -> mods/create-efficient-gauge/.../icon.png at 512px.
const fs = require('fs');
const path = require('path');
const { Resvg } = require('@resvg/resvg-js');

const src = process.argv[2]
const out = process.argv[3]
const size = Number(process.argv[4] || 512);

const svg = fs.readFileSync(src, 'utf8');
const resvg = new Resvg(svg, {
  fitTo: { mode: 'width', value: size },
  background: 'rgba(0,0,0,0)',
});
const png = resvg.render().asPng();
fs.writeFileSync(out, png);
console.log(`rendered ${src} -> ${out} (${size}px, ${png.length} bytes)`);
