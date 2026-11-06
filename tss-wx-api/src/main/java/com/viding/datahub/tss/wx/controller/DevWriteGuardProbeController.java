package com.viding.datahub.tss.wx.controller;

import com.viding.datahub.tss.wx.auth.DevOnlyCondition;
import com.viding.datahub.tss.wx.envelope.TssApiException;
import com.viding.datahub.tss.wx.envelope.TssErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * DEV 命名空间下的<b>写回守卫探针</b>（非 RC2 端点，与 {@link DevBindingFixtureController} 同级）。
 *
 * <p><b>为什么要有这一条</b>：V1.0 只读是 RC2 决议的红线（「V1.0 不开放真实 APPROVE/REJECT 写回」）。
 * 这条红线需要一个<b>可观测、可复测</b>的落点，否则它只是一句文档承诺：
 * 谁都能说"我们没做写回"，但没人能证明"写回确实被拒"。
 * 本探针把「写回被拒」变成一个能当场打出来的 403，与 Python 侧 DEV Mock 同形。
 *
 * <p><b>为什么路径里没有 approve / reject / transfer</b>：路径名带业务动词等于宣称我们设计过
 * 这些写回操作。这里刻意用中性的 {@code write-guard-probe}——只表达"写回守卫"，不表达任何动作。
 *
 * <p><b>为什么不放在 {@code /wx/v1/**} 下</b>：RC2 只冻结了 13 个端点。往 {@code /wx/v1/**}
 * 下塞任何非契约路由，都会让我们「13 个端点与 Schema 一致」的声明站不住。
 * 因此 {@code /wx/v1/**} 下的未知路径<b>保持 404</b>（现状，未改动）。
 *
 * <p><b>为什么注册全部写语义 method</b>：只注册 POST 的话，用 GET 打过来会拿到 405，
 * 而 405 会被 {@code TssErrorPageController} 映射成 {@code TSS_INVALID_ARGUMENT}(400)——
 * 语义完全错了：这不是参数问题，是「本版本禁止写回」。
 * 因此 GET / POST / PUT / PATCH / DELETE <b>一律</b>返回 403 {@code TSS_WRITE_DISABLED}。
 *
 * <p><b>门禁顺序：写回守卫优先于数据源门禁（不要在这里先调 {@code assertDataSourceUsable}）</b>。
 * 「禁止写回」是<b>版本级决策</b>——V1.0 根本不提供写回能力与"ERP 数据源通不通"无关。
 * 若让它排在数据源门禁之后，在 {@code dataMode=ERP} 且只读通道不可用的实例上，
 * 探针会返回 503 {@code TSS_ERP_READ_UNAVAILABLE} 而不是 403 {@code TSS_WRITE_DISABLED}，
 * 语义完全错：那是把一个版本约束谎报成了数据源故障。
 * 因此本 Controller 不注入任何数据源服务，直接抛 403。
 *
 * <p><b>非 DEV 环境返回 404 是预期，不是缺陷</b>：本 Controller 挂在 {@link DevOnlyCondition}
 * 下（dev profile + 显式开关 + 环境自证 DEV_LOCAL），门禁不成立时<b>根本不注册</b>，故为 404。
 * 刻意不为"两侧看起来一致"去绕过自己的 DEV 门禁——那在安全性上更不能接受。
 */
@RestController
@RequestMapping(path = "/dev-support/v1/tss/write-guard-probe", produces = MediaType.APPLICATION_JSON_VALUE)
@Conditional(DevOnlyCondition.class)
public class DevWriteGuardProbeController {

    private static final Logger log = LoggerFactory.getLogger(DevWriteGuardProbeController.class);

    private static final String MESSAGE = "V1.0 为只读版本：当前版本禁止真实审批写回（探针，非契约端点）";

    @RequestMapping(method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT,
            RequestMethod.PATCH, RequestMethod.DELETE})
    public Object probe() {
        log.warn("[TSS-WX][DEV-ONLY] write-guard-probe 命中：返回 403 TSS_WRITE_DISABLED（V1.0 只读红线）");
        throw new TssApiException(TssErrorCode.TSS_WRITE_DISABLED, MESSAGE);
    }
}
