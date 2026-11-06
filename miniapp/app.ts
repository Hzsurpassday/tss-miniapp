/**
 * 小程序入口。
 *
 * 只做两件事：解析 base URL（并打印，便于排查）与恢复本地会话。
 * 不在入口处写入任何密钥；AppID 由 project.config.json 提供，AppSecret 只应存在于服务端。
 */

import { resolveBaseUrl, isCurrentBaseUrlHttps, getEnvVersion } from './config/env';
import { restoreSession } from './utils/session';

interface IAppGlobalData {
  baseUrl: string;
  https: boolean;
  envVersion: string;
}

App<{ globalData: IAppGlobalData; onLaunch(): void }>({
  globalData: {
    baseUrl: '',
    https: false,
    envVersion: 'develop'
  },

  onLaunch(): void {
    const baseUrl = resolveBaseUrl();
    const envVersion = getEnvVersion();
    this.globalData.baseUrl = baseUrl;
    this.globalData.https = isCurrentBaseUrlHttps();
    this.globalData.envVersion = envVersion;
    restoreSession();
    console.info(
      '[tss] envVersion=' + envVersion + ' baseUrl=' + baseUrl + ' https=' + String(this.globalData.https)
    );
    if (envVersion !== 'develop' && !this.globalData.https) {
      console.error('[tss] 非开发环境却使用非 HTTPS base URL，请立即修正 config/env.ts');
    }
  }
});
