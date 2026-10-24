// Builds the EXCO two-slide pre-read as Word + PDF from one content model.
// Run: NODE_PATH=<dir containing docx> node build-exco-pre-read.js
// PDF is printed from HTML by headless Chrome (no LibreOffice needed).
const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');
const {
  Document, Packer, Paragraph, TextRun, Table, TableRow, TableCell, WidthType, ShadingType, BorderStyle,
  AlignmentType, LevelFormat, Footer, Header, PageNumber, ImageRun, PageBreak, VerticalAlign,
} = require('docx');

const DOCS = path.resolve(__dirname, '..');
const ASSETS = path.resolve(__dirname, '../assets');
const LOGO = ASSETS + '/aia-orbit-logo.png';
const OUT_BASE = DOCS + '/EXCO-Pre-Read-AIA-Orbit';

const RED = 'D31145', DEEP = '7A0A2B', INK = '1D1D1F', GREY = '6E6E73', MIST = 'F5F5F7', BLUSH = 'FCE8EE', LINE = 'D9D9DE';
const FONT = 'Arial';

// ───────────────────────────── content ─────────────────────────────
// inline: **bold**.  blocks: h1 h2 p bullets numbered table callout quote note pagebreak slidehead
const C = [
  { t: 'p', s: 'One idea sits behind both slides. The rules that run our life insurance business, how a surrender value is worked out, when a claim is paid or rejected, what a partial withdrawal costs, live inside decades-old AS400 programs written in COBOL. Only a few specialists can read them. So when a business owner needs to know **how something really works**, they raise a request and wait for an expert.' },
  { t: 'p', s: '**AIA Orbit is a next-generation knowledge copilot for our core systems.** It reads the actual programs and lets anyone ask in plain English, then answers in business language, shows where the answer comes from, and goes further: it maps the impact of a change, drafts the requirement and the test scenarios, and its **coding agent** proposes the code change itself and proves it compiles. A complicated question that takes the COBOL team **7 to 15 days** to answer by checking code and logic by hand can be worked through in **minutes**, by walking a real scenario through the system. Business teams get answers on their own; specialists get their time back.' },
  { t: 'h2', s: 'The two slides at a glance' },
  { t: 'table', w: [1700, 3900, 4038], head: ['', 'Slide 1 · Business and product owners', 'Slide 2 · AIA Singapore'], rows: [
    ['Message', 'Ask the system itself and get the answer in business language, without waiting in a queue.', 'Core-system knowledge that belongs to AIA, not to a handful of people, and changes made faster and with less risk.'],
    ['Core gains', 'Complex answers in minutes, not weeks · plain-English rules · clear impact before any change · requirements, tests and a proposed code change drafted · answers you can trace', 'Knowledge protected · weeks of waiting removed · faster delivery · lower change risk · stronger audit trail · specialists freed · ready for modernisation'],
  ] },
  { t: 'h2', s: 'What makes it next generation' },
  { t: 'p', s: 'Orbit reads the real programs, so answers are as current as the system:' },
  { t: 'table', w: [2700, 6938], head: ['Innovation', 'What it does for the business'], rows: [
    ['Ask in plain English', 'Type a business question; get an answer that explains what happens, why, and who is affected, in a business or a technical view'],
    ['Rules and scenarios made visible', 'Business rules, decision tables, flow diagrams, and step-by-step scenario walkthroughs, built from the code: the quickest route to a complicated answer'],
    ['Impact before change', 'See what a change would touch: directly affected, also touches, downstream, with an estimated scope'],
    ['From question to deliverable', 'Draft a functional requirement and test scenarios as ready-to-share reports'],
    ['A coding agent that shows its work', 'Finds the exact part of a program to change, drafts the change, checks it with a real compiler, and shows current and proposed code side by side, with a plain-English summary'],
    ['Evidence and safeguards', 'Every answer cites its source; it declines when the code does not support an answer, and blocks personal data and manipulation attempts'],
  ] },
  { t: 'h2', s: 'What we ask the Committee to take away' },
  { t: 'numbered', items: [
    '**Business owners win first.** Direct access to how our systems work changes their day; company value follows.',
    '**The value is knowledge, speed and safety,** more than time saved per question.',
    '**The numbers must be AIA’s own.** The illustrations show how value is built; measuring real requests turns them into a business case.',
  ] },
  { t: 'pagebreak' },

  // ── Slide 1 ──
  { t: 'slidehead', n: 'SLIDE 1', title: 'What business and product owners gain', line: '“Ask the system itself, and get the answer in business language, without waiting in a queue.”' },
  { t: 'callout', title: 'A complicated question: 7 to 15 days by hand, minutes with Orbit', body: 'The COBOL team’s estimate for answering a complex question by checking code and logic manually is 7 to 15 days. A scenario put to Orbit is traced through the real programs step by step, so the answer comes in minutes.' },
  { t: 'h2', s: 'A product owner’s week, before and after' },
  { t: 'table', w: [2000, 3819, 3819], head: ['', 'Today', 'With AIA Orbit'], rows: [
    ['How does it work?', 'Raise a request; wait for the specialist to trace the code by hand (7 to 15 days if it is complicated); get a technical reply', 'Ask in plain English, or put a scenario to it, and get a business explanation in minutes, with the source cited'],
    ['What is the rule exactly?', 'Rely on old documents, memory or a conversation; rules differ by who you ask', 'The rule is read from the live programs and shown as a plain statement, a decision table or a flow'],
    ['What would a change touch?', 'A meeting, then days of investigation before anyone can estimate', 'An impact map in seconds: what is affected directly, what it also touches, what follows downstream'],
    ['Writing the requirement', 'Start from a blank page and hope nothing was missed', 'A first-draft functional requirement grounded in how the system really behaves'],
    ['Preparing testing', 'Test cases built by hand from partial knowledge', 'Test scenarios drafted from the real rules, including the edge cases'],
    ['Making the change', 'A developer finds the right place in the program and writes the change by hand', 'A coding agent proposes the change, checks it compiles, and shows it beside the current code'],
  ] },
  { t: 'h2', s: 'Seven gains, in the product owner’s own terms' },
  { t: 'numbered', items: [
    '**Independence.** Answers to “how does this work?” no longer depend on a specialist’s diary. Ask at any time, in your own words, as often as you like.',
    '**Business language, not code.** Explanations lead with what happens and why it matters to the policyholder, the claims team or finance, and name who is affected.',
    '**Confidence before committing.** See the likely reach of a change before it is promised to the business, so timelines and scope are set on evidence.',
    '**Better requirements and a head start on the change.** Drafts start from what the system already does, and the coding agent proposes the change for a developer to review, so fewer surprises surface in build and testing.',
    '**Answers you can trust and check.** Each answer shows the programs behind it. If the code does not support an answer, Orbit says so rather than guess.',
    '**Complicated problems solved by scenario.** Put a real case to Orbit, such as a surrender in year three on a policy with a loan, and watch it traced step by step. What took days of manual checking becomes a conversation.',
    '**A shared language with IT.** Business and technology look at the same rules, flows and impact map, so conversations start from the same page.',
  ] },
  { t: 'h2', s: 'Typical questions a product owner can now answer alone' },
  { t: 'bullets', items: [
    'What happens to a policy when a customer surrenders, and how is the value worked out?',
    'Under what conditions is a claim rejected, and what does the customer see?',
    'If we add a new field or a new check to partial withdrawals, what else is affected?',
  ] },
  { t: 'quote', s: 'A question that once waited in a specialist’s queue is answered in moments, in business language, with the source shown.', by: 'Observed in the working prototype' },
  { t: 'pagebreak' },

  // ── Slide 2 ──
  { t: 'slidehead', n: 'SLIDE 2', title: 'What AIA Singapore gains', line: '“Core-system knowledge that belongs to AIA, not to a handful of people, and change delivered faster and with less risk.”' },
  { t: 'h2', s: 'Six gains for the company' },
  { t: 'table', w: [2700, 6938], head: ['Gain', 'What changes for AIA'], rows: [
    ['1 · Knowledge protected', 'What the core systems do is no longer held by a few people. Experience is available to everyone, and survives retirement and role changes'],
    ['2 · Faster delivery of change', 'Weeks of manual tracing disappear. Understanding, scoping, requirements, a proposed code change and test preparation all start sooner and from firmer ground, so product changes reach customers earlier'],
    ['3 · Lower change risk', 'Impact is seen before work begins, reducing surprises, production incidents and rework in systems that run policies and payments'],
    ['4 · Stronger audit and compliance', 'Rules can be explained in plain language and traced to the exact programs, supporting audit, regulator questions and product governance'],
    ['5 · Specialists freed for higher-value work', 'Scarce experts spend less time answering repeat questions and more on engineering, modernisation and risk'],
    ['6 · Ready for modernisation', 'Knowing exactly what the current systems do is the first step of any migration. Orbit builds that map as a by-product of daily use'],
  ] },
  { t: 'h2', s: 'The financial logic, kept simple' },
  { t: 'callout', title: 'Value = questions answered without a specialist × time saved × value of an hour, plus faster delivery and avoided incidents', body: 'Time saved is the easiest part to measure. The larger value is usually in change delivered sooner and incidents that never happen.' },
  { t: 'table', w: [5338, 4300], head: ['Illustration', 'Example value'], rows: [
    ['Routine questions per month needing a specialist · hours each', '100 · 2 hours'],
    ['**Specialist and business time returned per month**', '**200 hours**'],
    ['Complicated question: waiting time by hand (COBOL team estimate)', '7 to 15 days'],
    ['Complicated question: with Orbit', 'Minutes (to be confirmed in the pilot)'],
  ] },
  { t: 'note', s: 'Read this as an illustration, not a forecast. The 100 questions and 2 hours are planning assumptions, and 7 to 15 days is the COBOL team’s estimate of elapsed time, not measured effort. The first step is to log how many requests of this kind really occur and how long they take, so every figure shown to the Committee rests on AIA’s own numbers.' },
  { t: 'bullets', items: [
    '**Value beyond time:** one avoided production incident, or one change delivered a month earlier, may outweigh a year of time saved.',
  ] },
  { t: 'h2', s: 'Strategic position' },
  { t: 'bullets', items: [
    '**Next generation, not another document:** a living view of the systems, always as current as the code itself.',
    '**Beyond answers to action:** the coding agent carries a change from question to a compiler-checked proposal, always for a person to review.',
    '**A platform that grows:** more products and systems can be added, and the same approach serves business, audit, testing and onboarding.',
    '**Safe by design:** answers come only from our own code, personal data and manipulation attempts are blocked, and it explains and drafts rather than changing live systems.',
  ] },
  { t: 'pagebreak' },

  // ── FAQ ──
  { t: 'h1', s: 'FAQ' },
  { t: 'table', w: [3000, 6638], head: ['Question', 'Short answer'], rows: [
    ['What does the coding agent do?', 'It finds the part of a program that must change, drafts the new code, runs a real compiler over it, fixes errors that the change introduced, and shows current and proposed code side by side with a plain-English summary. A developer reviews and decides.'],
    ['Does it replace our specialists?', 'No. It takes the repeat questions off them, so they spend their time on the work only they can do. They remain the authority on anything Orbit cannot answer.'],
    ['Can we trust the answers?', 'Answers come only from our own programs, with the sources shown. When the code does not support an answer, Orbit says so. Users can rate answers, and the ratings are tracked.'],
    ['Will it change our live systems?', 'No. It explains, maps impact and drafts documents and proposed code changes for people to review. The coding agent never deploys anything.'],
    ['What about customer data and misuse?', 'Questions containing personal data such as ID or card numbers, and attempts to manipulate the assistant, are blocked before any answer is produced, and each event is logged.'],
    ['How is this different from documentation?', 'Documents age and are often missing. Orbit reads the real programs, so what it says matches what the system actually does.'],
    ['Is the saving real, or assumed?', 'Assumed in the illustration above. It is to be measured against real requests before any commitment is made.'],
    ['Is 7 to 15 days really the norm?', 'It is the COBOL team’s own estimate for complicated questions answered by manual code and logic checks. It is elapsed time, not hands-on effort, and the pilot will measure it properly.'],
    ['What is the downside of waiting?', 'Knowledge stays with a few people, change stays slow and risky, and every retirement or resignation takes understanding with it. The value above is deferred, not lost.'],
  ] },
  { t: 'note', s: 'Figures marked as illustrations, estimates or assumptions are planning inputs to be confirmed with Finance and a baseline measurement. Product behaviour described here was observed in the current working prototype.' },
];

const TITLE = 'The business case in two slides';
const KICKER = 'Pre-read for the Executive Committee · AIA Orbit';
const READERS = '5-minute read';

// ───────────────────────────── helpers ─────────────────────────────
const inline = (s) => s.split(/(\*\*[^*]+\*\*)/g).filter(Boolean).map((p) => (p.startsWith('**') ? { b: true, s: p.slice(2, -2) } : { b: false, s: p }));
const esc = (s) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
const htmlInline = (s) => inline(s).map((p) => (p.b ? `<b>${esc(p.s)}</b>` : esc(p.s))).join('');

// ───────────────────────────── DOCX ─────────────────────────────
const run = (t, o = {}) => new TextRun(Object.assign({ text: t, font: FONT, size: 19, color: INK }, o));
const runs = (s, o = {}) => inline(s).map((p) => run(p.s, Object.assign({}, o, p.b ? { bold: true } : {})));
const CW = 9638;
const ln = { style: BorderStyle.SINGLE, size: 4, color: LINE };
const borders = { top: ln, bottom: ln, left: ln, right: ln };
const none = { style: BorderStyle.NONE, size: 0, color: 'FFFFFF' };
const noBorders = { top: none, bottom: none, left: none, right: none };

function dCell(content, w, o = {}) {
  return new TableCell({
    width: { size: w, type: WidthType.DXA }, borders: o.borders || borders, verticalAlign: VerticalAlign.CENTER,
    shading: o.fill ? { fill: o.fill, type: ShadingType.CLEAR, color: 'auto' } : undefined,
    margins: { top: 50, bottom: 50, left: 110, right: 110 },
    children: [new Paragraph({ spacing: { after: 10, line: 252 }, children: runs(content, Object.assign({ size: 17 }, o.run || {})) })],
  });
}
function dTable(b) {
  return new Table({
    width: { size: CW, type: WidthType.DXA }, columnWidths: b.w,
    rows: [
      new TableRow({ tableHeader: true, children: b.head.map((h, i) => dCell(h, b.w[i], { fill: RED, run: { bold: true, color: 'FFFFFF' } })) }),
      ...b.rows.map((r, ri) => new TableRow({ cantSplit: true, children: r.map((c, i) => dCell(c, b.w[i], { fill: ri % 2 ? MIST : undefined, run: i === 0 ? { bold: true } : {} })) })),
    ],
  });
}
const sp = (n = 100) => new Paragraph({ spacing: { after: n }, children: [] });
const bar = (kids, o = {}) => new Table({
  width: { size: CW, type: WidthType.DXA }, columnWidths: [CW],
  rows: [new TableRow({ children: [new TableCell({
    width: { size: CW, type: WidthType.DXA },
    borders: Object.assign({}, noBorders, o.leftRed ? { left: { style: BorderStyle.SINGLE, size: 24, color: RED } } : {}),
    shading: { fill: o.fill || MIST, type: ShadingType.CLEAR, color: 'auto' },
    margins: { top: 90, bottom: 90, left: 240, right: 240 }, children: kids,
  })] })],
});

function dBlocks() {
  const out = []; let numN = 0;
  // cover block
  out.push(new Table({
    width: { size: CW, type: WidthType.DXA }, columnWidths: [1700, CW - 1700],
    rows: [new TableRow({ children: [
      new TableCell({ width: { size: 1700, type: WidthType.DXA }, borders: noBorders, verticalAlign: VerticalAlign.CENTER, margins: { left: 0, right: 100 },
        children: [new Paragraph({ children: [new ImageRun({ type: 'png', data: fs.readFileSync(LOGO), transformation: { width: 100, height: 100 }, altText: { title: 'AIA Orbit', description: 'AIA Orbit logo', name: 'logo' } })] })] }),
      new TableCell({ width: { size: CW - 1700, type: WidthType.DXA }, borders: noBorders, verticalAlign: VerticalAlign.CENTER,
        children: [
          new Paragraph({ spacing: { after: 40 }, children: [run('AIA ORBIT', { size: 52, bold: true, color: RED })] }),
          new Paragraph({ children: [run('Your knowledge copilot  ·  Explore  ·  Understand  ·  Build together', { size: 17, color: GREY, bold: true })] }),
        ] }),
    ] })],
  }));
  out.push(new Paragraph({ spacing: { before: 120, after: 160 }, border: { bottom: { style: BorderStyle.SINGLE, size: 18, color: RED, space: 1 } }, children: [] }));
  out.push(new Paragraph({ spacing: { after: 40 }, children: [run(KICKER.toUpperCase(), { size: 17, bold: true, color: RED })] }));
  out.push(new Paragraph({ spacing: { after: 60 }, children: [run(TITLE, { size: 48, bold: true })] }));
  out.push(new Paragraph({ spacing: { after: 200 }, children: [run(READERS, { size: 19, color: GREY })] }));

  for (const b of C) {
    if (b.t === 'p') out.push(new Paragraph({ spacing: { after: 100, line: 270 }, children: runs(b.s, { size: 20 }) }));
    else if (b.t === 'h1') out.push(new Paragraph({ spacing: { before: 120, after: 160 }, keepNext: true, children: [run(b.s, { size: 32, bold: true, color: DEEP })] }));
    else if (b.t === 'h2') out.push(new Paragraph({ spacing: { before: 160, after: 80 }, keepNext: true, border: { left: { style: BorderStyle.SINGLE, size: 24, color: RED, space: 8 } }, indent: { left: 140 }, children: [run(b.s, { size: 24, bold: true })] }));
    else if (b.t === 'bullets') b.items.forEach((s) => out.push(new Paragraph({ numbering: { reference: 'bullets', level: 0 }, spacing: { after: 50, line: 262 }, children: runs(s) })));
    else if (b.t === 'numbered') { const ref = 'num' + (numN++); b.items.forEach((s) => out.push(new Paragraph({ numbering: { reference: ref, level: 0 }, spacing: { after: 60, line: 262 }, children: runs(s) }))); }
    else if (b.t === 'table') { out.push(dTable(b)); out.push(sp(60)); }
    else if (b.t === 'callout') { out.push(bar([
      new Paragraph({ spacing: { after: 60 }, children: [run(b.title, { size: 22, bold: true, color: DEEP })] }),
      new Paragraph({ children: [run(b.body, { size: 19, color: GREY })] }),
    ], { fill: BLUSH, leftRed: true })); out.push(sp(120)); }
    else if (b.t === 'quote') { out.push(sp(60)); out.push(bar([
      new Paragraph({ spacing: { after: 60 }, children: [run('“' + b.s + '”', { size: 23, italics: true, color: DEEP })] }),
      new Paragraph({ children: [run(b.by.toUpperCase(), { size: 15, bold: true, color: RED })] }),
    ], { fill: BLUSH, leftRed: true })); out.push(sp(100)); }
    else if (b.t === 'note') out.push(new Paragraph({ spacing: { before: 80, after: 100, line: 262 }, children: [run(b.s, { size: 17, color: GREY, italics: true })] }));
    else if (b.t === 'pagebreak') out.push(new Paragraph({ children: [new PageBreak()] }));
    else if (b.t === 'slidehead') {
      out.push(new Table({
        width: { size: CW, type: WidthType.DXA }, columnWidths: [CW],
        rows: [new TableRow({ children: [new TableCell({
          width: { size: CW, type: WidthType.DXA }, borders: noBorders, shading: { fill: RED, type: ShadingType.CLEAR, color: 'auto' },
          margins: { top: 140, bottom: 140, left: 300, right: 300 },
          children: [
            new Paragraph({ spacing: { after: 40 }, children: [run(b.n, { size: 16, bold: true, color: 'FFC9D6' })] }),
            new Paragraph({ spacing: { after: 80 }, children: [run(b.title, { size: 34, bold: true, color: 'FFFFFF' })] }),
            new Paragraph({ children: [run(b.line, { size: 21, italics: true, color: 'FFFFFF' })] }),
          ],
        })] })],
      }));
      out.push(sp(40));
    }
  }
  return out;
}

const numCfg = [];
for (let i = 0; i < 10; i++) numCfg.push({ reference: 'num' + i, levels: [{ level: 0, format: LevelFormat.DECIMAL, text: '%1.', alignment: AlignmentType.LEFT, style: { paragraph: { indent: { left: 540, hanging: 300 } }, run: { bold: true, color: RED, font: FONT } } }] });

const doc = new Document({
  creator: 'AIA Orbit', title: 'AIA Orbit: ' + TITLE,
  styles: { default: { document: { run: { font: FONT, size: 20 } } } },
  numbering: { config: [{ reference: 'bullets', levels: [{ level: 0, format: LevelFormat.BULLET, text: '•', alignment: AlignmentType.LEFT, style: { paragraph: { indent: { left: 540, hanging: 270 } }, run: { color: RED } } }] }, ...numCfg] },
  sections: [{
    properties: { page: { size: { width: 11906, height: 16838 }, margin: { top: 700, right: 1020, bottom: 800, left: 1020 } } },
    footers: { default: new Footer({ children: [new Paragraph({ alignment: AlignmentType.RIGHT, border: { top: { style: BorderStyle.SINGLE, size: 6, color: RED, space: 6 } }, children: [run('AIA Orbit · EXCO pre-read · Page ', { size: 16, color: GREY }), new TextRun({ children: [PageNumber.CURRENT], font: FONT, size: 16, color: GREY })] })] }) },
    children: dBlocks(),
  }],
});

// ───────────────────────────── HTML → PDF ─────────────────────────────
const b64 = (f) => 'data:image/png;base64,' + fs.readFileSync(f).toString('base64');
function html() {
  let body = '';
  for (const b of C) {
    if (b.t === 'p') body += `<p>${htmlInline(b.s)}</p>`;
    else if (b.t === 'h1') body += `<h1>${esc(b.s)}</h1>`;
    else if (b.t === 'h2') body += `<h2>${esc(b.s)}</h2>`;
    else if (b.t === 'bullets') body += '<ul>' + b.items.map((s) => `<li>${htmlInline(s)}</li>`).join('') + '</ul>';
    else if (b.t === 'numbered') body += '<ol>' + b.items.map((s) => `<li>${htmlInline(s)}</li>`).join('') + '</ol>';
    else if (b.t === 'table') body += `<table><thead><tr>${b.head.map((h) => `<th>${esc(h)}</th>`).join('')}</tr></thead><tbody>${b.rows.map((r) => `<tr>${r.map((c) => `<td>${htmlInline(c)}</td>`).join('')}</tr>`).join('')}</tbody></table>`;
    else if (b.t === 'callout') body += `<div class="callout"><b>${esc(b.title)}</b><span>${esc(b.body)}</span></div>`;
    else if (b.t === 'quote') body += `<blockquote>“${esc(b.s)}”<cite>${esc(b.by)}</cite></blockquote>`;
    else if (b.t === 'note') body += `<p class="note">${esc(b.s)}</p>`;
    else if (b.t === 'pagebreak') body += '<div class="pb"></div>';
    else if (b.t === 'slidehead') body += `<div class="slidehead"><small>${esc(b.n)}</small><h1>${esc(b.title)}</h1><p>${esc(b.line)}</p></div>`;
  }
  return `<!doctype html><html><head><meta charset="utf-8"><title>AIA Orbit: ${TITLE}</title><style>
@page{size:A4;margin:12mm 15mm 14mm}
*{box-sizing:border-box}
body{font:9.3pt/1.42 Arial,Helvetica,sans-serif;color:#${INK};margin:0;-webkit-print-color-adjust:exact;print-color-adjust:exact}
.cover{display:flex;align-items:center;gap:16px;padding-bottom:14px;border-bottom:3px solid #${RED}}
.cover img.m{width:84px;height:84px}
.brand{font-size:26pt;font-weight:700;color:#${RED};letter-spacing:.02em;line-height:1.1}
.cover small{font-weight:700;color:#${GREY};font-size:8.6pt;letter-spacing:.02em}
.kicker{margin:16px 0 2px;font-size:8.6pt;font-weight:700;letter-spacing:.12em;color:#${RED};text-transform:uppercase}
.title{font-size:21pt;font-weight:700;margin:0 0 4px;line-height:1.15}
.meta{color:#${GREY};font-size:9.4pt;margin:0 0 14px}
h1{font-size:16pt;color:#${DEEP};margin:6px 0 10px}
h2{font-size:11pt;margin:12px 0 6px;padding-left:9px;border-left:4px solid #${RED};break-after:avoid}
p{margin:0 0 6px}
ul,ol{margin:0 0 8px;padding-left:20px} li{margin-bottom:3px}
ol li::marker{color:#${RED};font-weight:700} ul li::marker{color:#${RED}}
table{width:100%;border-collapse:collapse;margin:0 0 6px;font-size:8.3pt}
th{background:#${RED};color:#fff;text-align:left;padding:4px 7px;border:1px solid #${RED}}
td{padding:4px 7px;border:1px solid #${LINE};vertical-align:middle} tr{break-inside:avoid}
tbody tr:nth-child(even) td{background:#${MIST}} td:first-child{font-weight:700}
.callout{background:#${BLUSH};border-left:5px solid #${RED};padding:7px 12px;margin:4px 0 8px}
.callout b{display:block;color:#${DEEP};font-size:10.6pt;margin-bottom:3px}.callout span{color:#${GREY};font-size:9.4pt}
blockquote{background:#${BLUSH};border-left:5px solid #${RED};margin:12px 0 8px;padding:8px 14px;font-style:italic;color:#${DEEP};font-size:10pt;break-inside:avoid}
cite{display:block;margin-top:5px;font-style:normal;font-weight:700;font-size:7.4pt;letter-spacing:.1em;color:#${RED};text-transform:uppercase}
.note{font-size:7.8pt;margin:0 0 4px;color:#${GREY};font-style:italic}
.slidehead{background:linear-gradient(135deg,#${DEEP},#${RED});color:#fff;padding:12px 18px;margin-bottom:4px}
.slidehead small{font-weight:700;letter-spacing:.14em;color:#FFC9D6;font-size:8pt}
.slidehead h1{color:#fff;font-size:19pt;margin:2px 0 4px}.slidehead p{margin:0;font-style:italic;font-size:10.6pt}
.pb{break-after:page}
.foot{position:fixed;bottom:-11mm;right:0;font-size:7.6pt;color:#${GREY}}
</style></head><body>
<div class="cover"><img class="m" src="${b64(LOGO)}"><div><div class="brand">AIA ORBIT</div><small>Your knowledge copilot · Explore · Understand · Build together</small></div></div>
<div class="kicker">${esc(KICKER)}</div><div class="title">${esc(TITLE)}</div><div class="meta">${esc(READERS)}</div>
${body}</body></html>`;
}

(async () => {
  const buf = await Packer.toBuffer(doc);
  fs.writeFileSync(OUT_BASE + '.docx', buf);
  const htmlPath = path.join(process.env.TMPDIR || '/tmp', 'exco-pre-read.html');
  fs.writeFileSync(htmlPath, html());
  execFileSync('/Applications/Google Chrome.app/Contents/MacOS/Google Chrome', [
    '--headless=new', '--disable-gpu', '--no-pdf-header-footer', '--print-to-pdf=' + OUT_BASE + '.pdf', 'file://' + htmlPath,
  ], { stdio: 'ignore' });
  console.log('written', OUT_BASE + '.docx', OUT_BASE + '.pdf');
})();
