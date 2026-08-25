// Final token set for the Servya design system: in gamut, and every pair measured.
const g = (c) => (c <= 0.0031308 ? 12.92 * c : 1.055 * Math.pow(c, 1 / 2.4) - 0.055);
const ung = (c) => (c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4));
function raw(L, C, H) {
  const a = C * Math.cos((H * Math.PI) / 180), b = C * Math.sin((H * Math.PI) / 180);
  const l_ = L + 0.3963377774 * a + 0.2158037573 * b, m_ = L - 0.1055613458 * a - 0.0638541728 * b, s_ = L - 0.0894841775 * a - 1.2914855480 * b;
  const l = l_ ** 3, m = m_ ** 3, s = s_ ** 3;
  return [4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
         -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
         -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s];
}
function px(L, C, H) {
  const r = raw(L, C, H);
  const bytes = r.map((v) => Math.round(Math.min(1, Math.max(0, g(v))) * 255));
  const lin = bytes.map((v) => ung(v / 255));
  return { hex: '#' + bytes.map((v) => v.toString(16).padStart(2, '0')).join('').toUpperCase(),
           Y: 0.2126 * lin[0] + 0.7152 * lin[1] + 0.0722 * lin[2],
           clipped: r.some((v) => v < -0.0005 || v > 1.0005) };
}
const cr = (a, b) => { const [hi, lo] = a.Y > b.Y ? [a.Y, b.Y] : [b.Y, a.Y]; return (hi + 0.05) / (lo + 0.05); };

const LIGHT = {
  ground: [0.972, 0.004, 245], surface: [1, 0, 0],
  line: [0.90, 0.006, 245], lineSoft: [0.94, 0.005, 245], lineStrong: [0.636, 0.008, 245],
  ink: [0.24, 0.015, 250], ink2: [0.41, 0.013, 250], muted: [0.544, 0.011, 250],
  go: [0.50, 0.119, 155], goInk: [0.99, 0, 0], goTint: [0.95, 0.035, 155],
  wait: [0.52, 0.111, 66], waitTint: [0.95, 0.042, 78],
  stop: [0.52, 0.16, 27], stopTint: [0.95, 0.024, 27],
  idle: [0.658, 0.008, 250],
};
const DARK = {
  ground: [0.175, 0.01, 250], surface: [0.215, 0.012, 250],
  line: [0.30, 0.014, 250], lineSoft: [0.25, 0.012, 250], lineStrong: [0.516, 0.014, 250],
  ink: [0.95, 0.005, 250], ink2: [0.80, 0.008, 250], muted: [0.66, 0.013, 250],
  go: [0.74, 0.15, 155], goInk: [0.16, 0.03, 155], goTint: [0.27, 0.055, 155],
  wait: [0.80, 0.13, 78], waitTint: [0.28, 0.055, 78],
  stop: [0.72, 0.15, 27], stopTint: [0.27, 0.06, 27],
  idle: [0.514, 0.01, 250],
};
const TEXT = 4.5, NONTEXT = 3.0;
const PAIRS = [
  ['ink', 'ground', TEXT], ['ink', 'surface', TEXT],
  ['ink2', 'ground', TEXT], ['ink2', 'surface', TEXT],
  ['muted', 'ground', TEXT], ['muted', 'surface', TEXT],
  ['goInk', 'go', TEXT], ['go', 'goTint', TEXT], ['go', 'surface', TEXT], ['go', 'ground', TEXT],
  ['wait', 'waitTint', TEXT], ['wait', 'surface', TEXT], ['wait', 'ground', TEXT],
  ['stop', 'stopTint', TEXT], ['stop', 'surface', TEXT], ['stop', 'ground', TEXT],
  // control boundaries: an input or an outlined button must be findable on either backdrop
  ['lineStrong', 'surface', NONTEXT], ['lineStrong', 'ground', NONTEXT],
  // status rails and section bars
  ['idle', 'surface', NONTEXT], ['go', 'surface', NONTEXT],
  ['wait', 'surface', NONTEXT], ['stop', 'surface', NONTEXT],
];

let fails = 0;
for (const [name, T] of [['CLARO', LIGHT], ['ESCURO', DARK]]) {
  const P = Object.fromEntries(Object.entries(T).map(([k, v]) => [k, px(...v)]));
  const clip = Object.entries(P).filter(([, v]) => v.clipped).map(([k]) => k);
  console.log(`\n=== ${name} ===  ${clip.length ? '!! fora do gamut: ' + clip.join(', ') : 'todos dentro do gamut sRGB'}`);
  for (const [fg, bg, need] of PAIRS) {
    const r = cr(P[fg], P[bg]);
    if (r < need) { fails++; console.log(`  FAIL ${r.toFixed(2)}:1 (>=${need}) ${fg}/${bg}`); }
  }
  console.log('  ' + Object.entries(P).map(([k, v]) => `${k}=${v.hex}`).join('  '));
}
console.log(`\n${fails === 0 ? 'PASS' : 'FAIL'} — ${fails} reprovacao(oes) em ${PAIRS.length * 2} pares`);
process.exit(fails === 0 ? 0 : 1);
