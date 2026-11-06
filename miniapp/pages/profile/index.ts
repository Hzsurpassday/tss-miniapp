/**
 * MP-050 个人中心。
 *
 * 能力：绑定状态展示（API-02 / API-10）、发起绑定（API-09）、解绑（API-11）、退出登录、
 * 版本与隐私说明，以及**仅开发版可见**的开发者选项（base URL 切换 / Mock 会话 / 静态样例兜底）。
 *
 * 纪律：
 *   - V1.0 单当前企业：entId 由服务端绑定推导，本页**无任何企业切换入口**，也不提交 entId；
 *   - 前端绝不提交 userId / entId 作为授权依据；
 *   - Mock 会话通道与微信真实登录隔离，默认禁用，非 develop 环境硬禁用。
 */

import { getMe, getBindingStatus, claimBinding, unbind } from '../../utils/api';
import type { MeData, BindingStatusData, ClaimData, UnbindData } from '../../types/api';
import { BIND_STATUS_TEXT, BINDING_STATUS_VALUE_TEXT, textOf } from '../../utils/dict';
import { formatDateTime, randomNonce } from '../../utils/format';
import { errorText, errorTraceId } from '../../utils/page-utils';
import {
  logout,
  isMockSessionAvailable,
  isMockSessionEnabled,
  setMockSessionEnabled,
  getMockSessionCode,
  setMockSessionCode,
  isDevMockTokenAvailable,
  isDevMockTokenEnabled,
  setDevMockTokenEnabled,
  getDevMockTokenEntId,
  setDevMockTokenEntId,
  getDevMockTokenUserId,
  setDevMockTokenUserId
} from '../../utils/session';
import { BASE_URL_PRESETS, resolveBaseUrl, setBaseUrlOverride, clearBaseUrlOverride, isSecureRequiredEnv, isHttpsUrl } from '../../config/env';
import { isStaticSampleEnabled, setStaticSampleEnabled } from '../../utils/mock';
import { APP_ID, APP_VERSION, CONTRACT_VERSION, PRIVACY_SUMMARY, READONLY_NOTICE } from '../../config/app';

interface BindingStatusView {
  statusText: string;
  bindRequestIdText: string;
  erpUserMaskedText: string;
  entDisplayNameText: string;
  checkedAtText: string;
}

interface PresetView {
  id: string;
  label: string;
  url: string;
}

interface ProfilePageData {
  loading: boolean;
  busy: boolean;
  displayNameText: string;
  userIdText: string;
  entIdText: string;
  bindStatusText: string;
  bindingVersionText: string;
  binding: BindingStatusView | null;
  inviteCode: string;
  /** API-02 返回的绑定状态（仅用于与 API-10 做一致性比对，不用于覆盖渲染）。 */
  meBindStatus: string;
  /** API-10 返回的绑定状态（仅用于一致性比对，不用于覆盖渲染）。 */
  bindingStatus: string;
  /** 两个端点绑定态是否冲突（对方服务缺陷，仅在 develop 下提示，不改写任何渲染）。 */
  bindingConflict: boolean;
  bindingConflictText: string;
  errorText: string;
  traceId: string;
  devVisible: boolean;
  baseUrl: string;
  https: boolean;
  presets: PresetView[];
  presetIndex: number;
  customUrl: string;
  mockSessionEnabled: boolean;
  mockSessionCode: string;
  devMockTokenEnabled: boolean;
  devMockEntId: string;
  devMockUserId: string;
  staticSampleEnabled: boolean;
  appId: string;
  appVersion: string;
  contractVersion: string;
  privacy: string;
  readonlyNotice: string;
  secureRequired: boolean;
}

Page<
  ProfilePageData,
  {
    load(): Promise<void>;
    applyMe(me: MeData): void;
    applyBinding(data: BindingStatusData): void;
    onInviteInput(e: { detail: { value: string } }): void;
    checkBindingConflict(): void;
    onBindTap(): void;
    onUnbindTap(): void;
    onLogoutTap(): void;
    onPresetChange(e: { detail: { value: string | number } }): void;
    onCustomUrlInput(e: { detail: { value: string } }): void;
    onApplyCustomUrl(): void;
    onResetBaseUrl(): void;
    onMockSessionSwitch(e: { detail: { value: boolean } }): void;
    onMockCodeInput(e: { detail: { value: string } }): void;
    onDevTokenSwitch(e: { detail: { value: boolean } }): void;
    onDevEntInput(e: { detail: { value: string } }): void;
    onDevUserInput(e: { detail: { value: string } }): void;
    onRelogin(): void;
    onStaticSampleSwitch(e: { detail: { value: boolean } }): void;
    onCopyTraceId(): void;
  }
>({
  data: {
    loading: false,
    busy: false,
    displayNameText: '未提供',
    userIdText: '未提供',
    entIdText: '由服务端绑定推导',
    bindStatusText: '未知',
    bindingVersionText: '未提供',
    binding: null,
    inviteCode: '',
    meBindStatus: '',
    bindingStatus: '',
    bindingConflict: false,
    bindingConflictText: '',
    errorText: '',
    traceId: '',
    devVisible: false,
    baseUrl: '',
    https: false,
    presets: [],
    presetIndex: 0,
    customUrl: '',
    mockSessionEnabled: false,
    mockSessionCode: '',
    devMockTokenEnabled: false,
    devMockEntId: '',
    devMockUserId: '',
    staticSampleEnabled: false,
    appId: APP_ID,
    appVersion: APP_VERSION,
    contractVersion: CONTRACT_VERSION,
    privacy: PRIVACY_SUMMARY,
    readonlyNotice: READONLY_NOTICE,
    secureRequired: false
  },

  onLoad(): void {
    const current = resolveBaseUrl();
    const presets: PresetView[] = [];
    let presetIndex = 0;
    for (let i = 0; i < BASE_URL_PRESETS.length; i++) {
      presets.push({
        id: BASE_URL_PRESETS[i].id,
        label: BASE_URL_PRESETS[i].label,
        url: BASE_URL_PRESETS[i].url
      });
      if (BASE_URL_PRESETS[i].url === current) {
        presetIndex = i;
      }
    }
    this.setData({
      devVisible: isMockSessionAvailable(),
      secureRequired: isSecureRequiredEnv(),
      baseUrl: current,
      https: isHttpsUrl(current),
      presets: presets,
      presetIndex: presetIndex,
      mockSessionEnabled: isMockSessionEnabled(),
      mockSessionCode: getMockSessionCode(),
      devMockTokenEnabled: isDevMockTokenEnabled(),
      devMockEntId: getDevMockTokenEntId(),
      devMockUserId: getDevMockTokenUserId(),
      staticSampleEnabled: isStaticSampleEnabled()
    });
  },

  onShow(): void {
    this.load();
  },

  onPullDownRefresh(): void {
    this.load().then(() => {
      wx.stopPullDownRefresh();
    });
  },

  load(): Promise<void> {
    this.setData({
      loading: true,
      errorText: '',
      traceId: '',
      meBindStatus: '',
      bindingStatus: '',
      bindingConflict: false,
      bindingConflictText: ''
    });
    return getMe()
      .then((me: MeData) => {
        this.applyMe(me);
      })
      .catch((e: unknown) => {
        this.setData({ errorText: errorText(e), traceId: errorTraceId(e) });
      })
      .then(() => {
        return getBindingStatus()
          .then((data: BindingStatusData) => {
            this.applyBinding(data);
          })
          .catch((e: unknown) => {
            if (!this.data.errorText) {
              this.setData({ errorText: errorText(e), traceId: errorTraceId(e) });
            }
          });
      })
      .then(() => {
        this.setData({ loading: false });
      });
  },

  applyMe(me: MeData): void {
    this.setData({
      displayNameText: me.displayName || '未提供',
      userIdText: me.userId || '未提供',
      entIdText: me.entId || '由服务端绑定推导',
      bindStatusText: textOf(BIND_STATUS_TEXT, me.bindStatus),
      bindingVersionText: me.bindingVersion === null ? '未提供' : String(me.bindingVersion),
      meBindStatus: me.bindStatus
    });
    this.checkBindingConflict();
  },

  applyBinding(data: BindingStatusData): void {
    this.setData({
      bindingStatus: data.status,
      binding: {
        statusText: textOf(BINDING_STATUS_VALUE_TEXT, data.status),
        bindRequestIdText: data.bindRequestId || '未提供',
        erpUserMaskedText: data.erpUserMasked || '未提供',
        entDisplayNameText: data.entDisplayName || '未提供',
        checkedAtText: formatDateTime(data.checkedAt)
      }
    });
    this.checkBindingConflict();
  },

  /**
   * 绑定态一致性自检。
   *
   * 纪律：只做**提示**，绝不改写渲染。
   *  - 绑定链路以 API-10 `bindings/status` 为准，不用 API-02 `me` 的值去覆盖；
   *  - 真实场景存在「me 已绑定但当前 bindRequest 仍在 PENDING_PC_CONFIRM」的合法中间态，
   *    静默覆盖会误报，因此这里只比对 BOUND 与非 BOUND 的强冲突；
   *  - 提示条仅在 develop 可见，不弹 toast 骚扰普通用户。
   */
  checkBindingConflict(): void {
    const meBindStatus = this.data.meBindStatus;
    const bindingStatus = this.data.bindingStatus;
    if (!meBindStatus || !bindingStatus) {
      return;
    }
    const meBound = meBindStatus === 'BOUND';
    const apiBound = bindingStatus === 'BOUND';
    if (meBound === apiBound) {
      this.setData({ bindingConflict: false, bindingConflictText: '' });
      return;
    }
    this.setData({
      bindingConflict: true,
      bindingConflictText:
        '服务端 /wx/v1/me 与 /wx/v1/bindings/status 绑定态不一致（me=' +
        meBindStatus +
        '，status=' +
        bindingStatus +
        '）。属对方服务缺陷，已登记；本页两端均按原样展示，不做任何改写。'
    });
  },

  onInviteInput(e: { detail: { value: string } }): void {
    this.setData({ inviteCode: e.detail.value });
  },

  onBindTap(): void {
    const inviteCode = (this.data.inviteCode || '').trim();
    if (!inviteCode) {
      wx.showToast({ title: '请输入邀请码', icon: 'none' });
      return;
    }
    this.setData({ busy: true });
    claimBinding({ inviteCode: inviteCode, claimNonce: randomNonce('claim') })
      .then((data: ClaimData) => {
        wx.showModal({
          title: '绑定申请已提交',
          content: '状态：等待 PC 端确认\n申请号：' + data.bindRequestId + '\n有效期至：' + formatDateTime(data.expiresAt),
          showCancel: false
        });
        return this.load();
      })
      .catch((e: unknown) => {
        this.setData({ errorText: errorText(e), traceId: errorTraceId(e) });
      })
      .then(() => {
        this.setData({ busy: false });
      });
  },

  onUnbindTap(): void {
    wx.showModal({
      title: '确认解绑',
      content: '解绑后将无法在小程序中查看待办，需重新绑定才能恢复。此操作不会写入 ERP。',
      confirmText: '确认解绑',
      cancelText: '取消',
      success: (res) => {
        if (!res.confirm) {
          return;
        }
        this.setData({ busy: true });
        unbind({ confirmation: 'UNBIND', clientNonce: randomNonce('unbind') })
          .then((data: UnbindData) => {
            wx.showToast({ title: '已解绑', icon: 'none' });
            console.info('[tss] 解绑时间 ' + data.revokedAt);
            // 跨端统一口径（后端 D-6）：解绑只解除绑定关系，**会话仍然有效**——
            // 解绑后 GET /wx/v1/me 返回 200 SUCCESS 且 bindStatus=UNBOUND，
            // 业务端点返回 403 TSS_NOT_BOUND（不是 401）。
            // 因此这里**不再 logout()**：保留会话直接刷新，页面落到「绑定引导」；
            // 若强行清空会话，下次请求要先重跑一遍 wx.login——在未配置 appid/secret 的
            // 本机联调环境里那是 503 TSS_NOT_CONFIGURED，用户看到的是「登录失败」
            // 而不是「已解绑，去绑定」，恰好把 D-6 要消除的歧义又造了回来。
            this.setData({ inviteCode: '' });
            return this.load();
          })
          .catch((e: unknown) => {
            this.setData({ errorText: errorText(e), traceId: errorTraceId(e) });
          })
          .then(() => {
            this.setData({ busy: false });
          });
      }
    });
  },

  onLogoutTap(): void {
    wx.showModal({
      title: '退出登录',
      content: '将清除本地会话令牌，下次进入需重新登录。',
      confirmText: '退出',
      cancelText: '取消',
      success: (res) => {
        if (!res.confirm) {
          return;
        }
        logout();
        wx.showToast({ title: '已退出登录', icon: 'none' });
        this.setData({
          binding: null,
          bindStatusText: '未知',
          meBindStatus: '',
          bindingStatus: '',
          bindingConflict: false,
          bindingConflictText: ''
        });
      }
    });
  },

  onPresetChange(e: { detail: { value: string | number } }): void {
    const index = Number(e.detail.value);
    const preset = BASE_URL_PRESETS[index];
    if (!preset) {
      return;
    }
    const result = setBaseUrlOverride(preset.url);
    if (!result.ok) {
      wx.showToast({ title: result.message, icon: 'none' });
      return;
    }
    this.setData({ baseUrl: preset.url, https: isHttpsUrl(preset.url), presetIndex: index });
    wx.showToast({ title: result.message, icon: 'none' });
  },

  onCustomUrlInput(e: { detail: { value: string } }): void {
    this.setData({ customUrl: e.detail.value });
  },

  onApplyCustomUrl(): void {
    const url = (this.data.customUrl || '').trim();
    if (!url) {
      wx.showToast({ title: '请输入 base URL', icon: 'none' });
      return;
    }
    const result = setBaseUrlOverride(url);
    if (!result.ok) {
      wx.showToast({ title: result.message, icon: 'none' });
      return;
    }
    this.setData({ baseUrl: url, https: isHttpsUrl(url) });
    wx.showToast({ title: result.message, icon: 'none' });
  },

  onResetBaseUrl(): void {
    clearBaseUrlOverride();
    const url = resolveBaseUrl();
    this.setData({ baseUrl: url, https: isHttpsUrl(url), customUrl: '' });
    wx.showToast({ title: '已恢复默认 base URL', icon: 'none' });
  },

  onMockSessionSwitch(e: { detail: { value: boolean } }): void {
    const value = !!e.detail.value;
    setMockSessionEnabled(value);
    this.setData({ mockSessionEnabled: value });
    if (value) {
      wx.showToast({ title: 'Mock 会话已开启，仅用于联调', icon: 'none' });
    }
  },

  onMockCodeInput(e: { detail: { value: string } }): void {
    const code = e.detail.value;
    setMockSessionCode(code);
    this.setData({ mockSessionCode: code });
  },

  onDevTokenSwitch(e: { detail: { value: boolean } }): void {
    const value = !!e.detail.value;
    setDevMockTokenEnabled(value);
    this.setData({ devMockTokenEnabled: value });
  },

  onDevEntInput(e: { detail: { value: string } }): void {
    setDevMockTokenEntId(e.detail.value);
    this.setData({ devMockEntId: e.detail.value });
  },

  onDevUserInput(e: { detail: { value: string } }): void {
    setDevMockTokenUserId(e.detail.value);
    this.setData({ devMockUserId: e.detail.value });
  },

  /** 清掉当前会话，强制用新配置重新登录。 */
  onRelogin(): void {
    logout();
    this.load().then(() => {
      wx.showToast({ title: '已按新配置重新登录', icon: 'none' });
    });
  },

  onStaticSampleSwitch(e: { detail: { value: boolean } }): void {
    const value = !!e.detail.value;
    setStaticSampleEnabled(value);
    this.setData({ staticSampleEnabled: value });
  },

  onCopyTraceId(): void {
    if (!this.data.traceId) {
      return;
    }
    wx.setClipboardData({ data: this.data.traceId });
  }
});
