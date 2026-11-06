#!/usr/bin/env node
/**
 * 生成 config/generated.env.ts —— 把环境变量 TSS_API_BASE_URL 注入前端。
 *
 * 用法（PowerShell）：
 *   $env:TSS_API_BASE_URL="http://39.106.179.158:9000"; node scripts/gen-env.mjs
 * 用法（bash）：
 *   TSS_API_BASE_URL="http://127.0.0.1:8787" node scripts/gen-env.mjs
 *
 * 未设置该环境变量时生成空串，运行期回退到 config/env.ts 的默认预设。
 */

import { writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve as resolvePath } from 'node:path';

const __dirname = dirname(fileURLToPath(import.meta.url));

const raw = process.env.TSS_API_BASE_URL || '';
const baseUrl = String(raw).trim().replace(/\/+$/, '');

if (baseUrl && !/^https?:\/\//i.test(baseUrl)) {
  console.error('[gen-env] TSS_API_BASE_URL 必须以 http:// 或 https:// 开头，当前值：' + baseUrl);
  process.exit(1);
}

const target = resolvePath(__dirname, '..', 'config', 'generated.env.ts');
const content = `/**
 * 由 scripts/gen-env.mjs 生成，请勿手工编辑。
 *
 * 生成命令（PowerShell）：
 *   $env:TSS_API_BASE_URL="\${baseUrl}"; node scripts/gen-env.mjs
 *
 * 未设置 TSS_API_BASE_URL 时本文件为空串，运行时回退到 config/env.ts 的默认预设。
 */

/** 构建期注入的 base URL（TSS_API_BASE_URL）。空串表示未注入。 */
export const BASE_URL_FROM_ENV: string = ${JSON.stringify(baseUrl)};

/** 生成时间（仅用于排查，不参与运行逻辑）。 */
export const GENERATED_AT: string = ${JSON.stringify(new Date().toISOString())};

/** 生成来源标识。 */
export const GENERATED_BY: string = 'scripts/gen-env.mjs';
`;

writeFileSync(target, content, 'utf8');
console.log('[gen-env] 已写入 ' + target);
console.log('[gen-env] BASE_URL_FROM_ENV = ' + (baseUrl || '(空，回退默认预设)'));
