/**
 * 极简微信小程序运行时声明（离线可编译版）。
 *
 * 说明：本工程不依赖 npm 安装即可在微信开发者工具中编译。若团队改用官方
 * `miniprogram-api-typings`，请删除本文件并在 tsconfig.json 中设置
 * `"types": ["miniprogram-api-typings"]`，否则会出现重复声明。
 */

declare interface IAnyObject {
  [key: string]: any;
}

declare interface WxGeneralError {
  errMsg: string;
  errno?: number;
}

declare type WxRequestMethod =
  | 'GET'
  | 'POST'
  | 'PUT'
  | 'DELETE'
  | 'HEAD'
  | 'OPTIONS'
  | 'PATCH'
  | 'TRACE'
  | 'CONNECT';

declare interface WxRequestOption {
  url: string;
  method?: WxRequestMethod;
  data?: string | IAnyObject | ArrayBuffer;
  header?: IAnyObject;
  timeout?: number;
  responseType?: 'text' | 'arraybuffer';
  dataType?: 'json' | 'text';
  enableHttp2?: boolean;
  enableQuic?: boolean;
  enableCache?: boolean;
  success?: (res: WxRequestSuccessResult) => void;
  fail?: (err: WxGeneralError) => void;
  complete?: (res: unknown) => void;
}

declare interface WxRequestSuccessResult {
  data: any;
  statusCode: number;
  header: IAnyObject;
  cookies?: string[];
  profile?: IAnyObject;
}

declare interface WxRequestTask {
  abort(): void;
  onHeadersReceived(cb: (res: { header: IAnyObject }) => void): void;
  offHeadersReceived(cb: (res: unknown) => void): void;
}

declare interface WxLoginSuccessResult {
  code: string;
}

declare interface WxLoginOption {
  timeout?: number;
  success?: (res: WxLoginSuccessResult) => void;
  fail?: (err: WxGeneralError) => void;
  complete?: (res: unknown) => void;
}

declare interface WxToastOption {
  title: string;
  icon?: 'success' | 'error' | 'loading' | 'none';
  duration?: number;
  mask?: boolean;
}

declare interface WxModalSuccessResult {
  confirm: boolean;
  cancel: boolean;
  content?: string;
}

declare interface WxModalOption {
  title?: string;
  content?: string;
  showCancel?: boolean;
  cancelText?: string;
  cancelColor?: string;
  confirmText?: string;
  confirmColor?: string;
  editable?: boolean;
  placeholderText?: string;
  success?: (res: WxModalSuccessResult) => void;
  fail?: (err: WxGeneralError) => void;
  complete?: (res: unknown) => void;
}

declare interface WxNavigateOption {
  url: string;
  success?: (res: IAnyObject) => void;
  fail?: (err: WxGeneralError) => void;
  complete?: (res: unknown) => void;
}

declare interface WxSubscribeMessageOption {
  tmplIds: string[];
  success?: (res: { [tmplId: string]: 'accept' | 'reject' | 'ban' | 'filter' }) => void;
  fail?: (err: WxGeneralError) => void;
  complete?: (res: unknown) => void;
}

declare interface WxAccountInfo {
  miniProgram: {
    appId: string;
    envVersion: 'develop' | 'trial' | 'release' | 'gray';
    version?: string;
  };
  plugin?: IAnyObject;
}

declare interface WxSystemInfo {
  SDKVersion: string;
  platform: string;
  system: string;
  version?: string;
}

declare const wx: {
  request(option: WxRequestOption): WxRequestTask;
  login(option: WxLoginOption): void;
  showToast(option: WxToastOption): void;
  showLoading(option: { title: string; mask?: boolean }): void;
  hideLoading(option?: IAnyObject): void;
  showModal(option: WxModalOption): void;
  showActionSheet(option: IAnyObject): void;
  navigateTo(option: WxNavigateOption): void;
  redirectTo(option: WxNavigateOption): void;
  navigateBack(option?: { delta?: number }): void;
  switchTab(option: WxNavigateOption): void;
  reLaunch(option: WxNavigateOption): void;
  requestSubscribeMessage(option: WxSubscribeMessageOption): void;
  setNavigationBarTitle(option: { title: string }): void;
  stopPullDownRefresh(option?: IAnyObject): void;
  startPullDownRefresh(option?: IAnyObject): void;
  setStorageSync(key: string, value: unknown): void;
  getStorageSync(key: string): any;
  removeStorageSync(key: string): void;
  clearStorageSync(): void;
  getAccountInfoSync(): WxAccountInfo;
  getSystemInfoSync(): WxSystemInfo;
  setClipboardData(option: { data: string }): void;
  canIUse(schema: string): boolean;
};

/** 页面生命周期方法名（用于在 Page options 中放行这些键）。 */
declare type PageLifecycle =
  | 'onLoad'
  | 'onShow'
  | 'onReady'
  | 'onHide'
  | 'onUnload'
  | 'onPullDownRefresh'
  | 'onReachBottom'
  | 'onShareAppMessage'
  | 'onShareTimeline'
  | 'onAddToFavorites'
  | 'onPageScroll'
  | 'onResize'
  | 'onTabItemTap'
  | 'onSaveExitState';

/** 页面实例类型：数据 + 自定义方法 + 内置 setData。 */
declare type PageInstance<D, M> = D &
  M & {
    data: D;
    setData(data: Partial<D> & IAnyObject, callback?: () => void): void;
  };

declare function Page<D extends IAnyObject, M extends IAnyObject>(
  options: M &
    Partial<Record<PageLifecycle, (...args: any[]) => void>> & { data: D } & ThisType<PageInstance<D, M>>
): void;

/** 组件属性定义（type 为构造器，故用 any 承载）。 */
declare interface ComponentPropertyDef {
  type?: any;
  value?: any;
  optionalTypes?: any[];
  observer?: (...args: any[]) => void;
}

declare type ComponentPropertyMap = { [key: string]: ComponentPropertyDef };

declare interface ComponentOptions<D, P extends ComponentPropertyMap, M> {
  properties?: P;
  data?: D;
  methods?: M;
  observers?: { [key: string]: (...args: any[]) => void };
  lifetimes?: { [key: string]: (...args: any[]) => void };
  pageLifetimes?: { [key: string]: (...args: any[]) => void };
  options?: IAnyObject;
  externalClasses?: string[];
  relations?: IAnyObject;
  behaviors?: unknown[];
}

declare type ComponentInstance<D, P, M> = D &
  M & {
    data: D & { [K in keyof P]: any };
    setData(data: Partial<D & { [K in keyof P]: any }> & IAnyObject, callback?: () => void): void;
    triggerEvent(name: string, detail?: unknown, options?: IAnyObject): void;
  };

declare function Component<
  D extends IAnyObject,
  P extends ComponentPropertyMap,
  M extends IAnyObject
>(options: ComponentOptions<D, P, M> & ThisType<ComponentInstance<D, P, M>>): void;

declare function App<T extends IAnyObject>(options: T & ThisType<T>): void;

declare function getApp<T extends IAnyObject>(options?: IAnyObject): T;

declare function getCurrentPages(): Array<{ route: string; options: IAnyObject; [key: string]: any }>;
