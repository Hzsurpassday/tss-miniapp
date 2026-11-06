/**
 * 应用级常量。
 *
 * 纪律：此处只放**展示与配置**常量，绝不放置任何密钥（AppSecret / API Key 只应存在于服务端）。
 */

/** AppID（与 project.config.json 一致，仅用于展示与日志）。 */
export const APP_ID = 'wx16d988aa2b90af65';

/** 展示用版本号（与小程序后台提交版本对齐）。 */
export const APP_VERSION = '1.0.0-rc2';

/** 契约版本（RC2 + schemaVersion）。 */
export const CONTRACT_VERSION = 'RC2 / schemaVersion 1.0.0';

/**
 * 订阅消息模板 ID。
 * RC2 未定义模板下发接口，需在小程序后台申请后填入；为空数组时不弹授权窗，
 * 仅记录意愿（consented），不伪造订阅结果。
 */
export const SUBSCRIBE_TEMPLATE_IDS: string[] = [];

/** 隐私说明（展示用，与 PRD 口径一致）。 */
export const PRIVACY_SUMMARY =
  '本小程序仅读取您的待办审批数据用于展示，不会向 ERP 写入任何审批结果。';

/** 只读声明（各页面复用）。 */
export const READONLY_NOTICE = 'V1.0 为只读版本：不支持在小程序内提交同意/驳回，审批请在 ERP 中完成。';
