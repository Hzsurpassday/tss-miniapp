#!/usr/bin/env node
/**
 * WXML 标签配平校验（结构性）。
 *
 * 为什么需要它：`tsc -p tsconfig.json` **只检查 .ts**，完全看不到 .wxml。
 * 曾经出现过 `<view>` 开标签 55 个、`</view>` 闭标签 54 个的重复开标签缺陷，
 * tsc 报 0 错误，但微信开发者工具会在编译该页面时报错 / 结构错乱。
 * 因此本脚本做两件事：
 *   1. 逐标签类型统计：`<tag ...>`（排除自闭合 `/>`）与 `</tag>` 数量必须相等；
 *   2. 栈式结构校验：标签必须**正确嵌套**（能抓出 `<view><text></view></text>` 这种数量相等但嵌套错乱的情况）。
 *
 * 用法：
 *   node scripts/check-wxml.mjs            # 校验 pages 与 components 目录下所有 .wxml
 *   node scripts/check-wxml.mjs a.wxml b.wxml  # 校验指定文件
 *
 * 退出码：0 = 全部通过；1 = 存在不配平/嵌套错误。
 */

import { readFileSync, readdirSync, statSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join, relative, resolve as resolvePath, sep } from 'node:path';

const __dirname = dirname(fileURLToPath(import.meta.url));
const ROOT = resolvePath(__dirname, '..');

/** 已知只以自闭合形式出现、但也允许成对出现的元素（不参与「必须成对」的额外限制）。 */
const VOID_LIKE = new Set(['input', 'image', 'icon', 'import', 'include', 'wxs', 'switch', 'picker']);

/** 递归收集 .wxml 文件。 */
function collect(dir, out) {
  let entries;
  try {
    entries = readdirSync(dir);
  } catch (e) {
    return out;
  }
  for (const name of entries) {
    if (name === 'node_modules' || name === '.git') {
      continue;
    }
    const full = join(dir, name);
    let st;
    try {
      st = statSync(full);
    } catch (e) {
      continue;
    }
    if (st.isDirectory()) {
      collect(full, out);
    } else if (name.endsWith('.wxml')) {
      out.push(full);
    }
  }
  return out;
}

/** 把注释替换为等长换行，保持行号不变。 */
function stripComments(source) {
  return source.replace(/<!--[\s\S]*?-->/g, (m) => m.replace(/[^\n]/g, ' '));
}

/** WXML 模板插值中的字符串也可能含引号，这里只做标签抽取，属性内引号已由正则分组处理。 */
const TAG_RE = /<(\/?)([a-zA-Z][\w-]*)((?:"[^"]*"|'[^']*'|[^>"'])*?)(\/?)>/g;

/**
 * 校验单个文件。
 * @returns {{file: string, errors: string[], counts: Map<string, {open:number, close:number}>}}
 */
function checkFile(file) {
  const raw = readFileSync(file, 'utf8');
  const src = stripComments(raw);
  const errors = [];
  const counts = new Map();
  const stack = [];

  const lineOf = (idx) => {
    let line = 1;
    for (let i = 0; i < idx; i++) {
      if (src.charCodeAt(i) === 10) {
        line++;
      }
    }
    return line;
  };

  let m;
  TAG_RE.lastIndex = 0;
  while ((m = TAG_RE.exec(src)) !== null) {
    const isClose = m[1] === '/';
    const tag = m[2];
    const selfClose = m[4] === '/';
    const line = lineOf(m.index);

    if (!counts.has(tag)) {
      counts.set(tag, { open: 0, close: 0 });
    }
    const c = counts.get(tag);

    if (isClose) {
      c.close++;
      const top = stack.pop();
      if (!top) {
        errors.push(`${file}:${line} 多余的闭合标签 </${tag}>`);
      } else if (top.tag !== tag) {
        errors.push(
          `${file}:${line} 闭合标签 </${tag}> 与 ${top.file ? top.file + ':' : ''}${top.line} 行的 <${top.tag}> 不匹配`
        );
      }
      continue;
    }

    if (selfClose) {
      // 自闭合标签：不计入开/闭统计，也不入栈。
      continue;
    }

    c.open++;
    stack.push({ tag, line });
  }

  while (stack.length > 0) {
    const top = stack.pop();
    errors.push(`${file}:${top.line} 标签 <${top.tag}> 未闭合`);
  }

  for (const [tag, c] of counts) {
    if (c.open !== c.close) {
      errors.push(`${file} 标签 <${tag}> 数量不配平：开 ${c.open} / 闭 ${c.close}`);
    }
  }

  return { file: relative(ROOT, file).split(sep).join('/'), errors, counts };
}

const args = process.argv.slice(2);
let files;
if (args.length > 0) {
  files = args.map((a) => resolvePath(process.cwd(), a));
} else {
  files = [];
  collect(join(ROOT, 'pages'), files);
  collect(join(ROOT, 'components'), files);
  files.sort();
}

if (files.length === 0) {
  console.error('[check-wxml] 未找到任何 .wxml 文件');
  process.exit(1);
}

let failed = 0;
let totalOpen = 0;
for (const f of files) {
  const r = checkFile(f);
  let opens = 0;
  for (const c of r.counts.values()) {
    opens += c.open;
  }
  totalOpen += opens;
  if (r.errors.length > 0) {
    failed++;
    console.error(`✗ ${r.file}`);
    for (const e of r.errors) {
      console.error('    ' + e);
    }
  } else {
    console.log(`✓ ${r.file}（成对标签 ${opens} 个）`);
  }
}

console.log(`\n[check-wxml] 共校验 ${files.length} 个文件，成对标签合计 ${totalOpen} 个。`);
if (failed > 0) {
  console.error(`[check-wxml] 失败：${failed} 个文件存在标签配平 / 嵌套错误`);
  process.exit(1);
}
console.log('[check-wxml] 全部通过：标签配平与嵌套均正确。');
console.log('[check-wxml] 注意：VOID_LIKE 元素（' + Array.from(VOID_LIKE).join(', ') + '）允许自闭合写法。');
