package com.viding.datahub.tss.wx.mock;

import com.viding.datahub.tss.wx.config.TssWxClock;
import com.viding.datahub.tss.wx.config.TssWxProperties;
import com.viding.datahub.tss.wx.dto.AdminMappingsData;
import com.viding.datahub.tss.wx.dto.CountGroup;
import com.viding.datahub.tss.wx.dto.DataMeta;
import com.viding.datahub.tss.wx.dto.DetailData;
import com.viding.datahub.tss.wx.dto.HistoryItem;
import com.viding.datahub.tss.wx.dto.NotificationItem;
import com.viding.datahub.tss.wx.dto.SummaryData;
import com.viding.datahub.tss.wx.dto.TodoItem;
import com.viding.datahub.tss.wx.dto.TssEnums;
import com.viding.datahub.tss.wx.envelope.TssApiException;
import com.viding.datahub.tss.wx.envelope.TssErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * DEV Mock 数据服务（B1）。
 *
 * <p>职责：按「服务端解析出的可信身份」返回对应的样例数据，并承担本人数据隔离的第一道过滤——
 * 非数据集归属身份一律返回空集合，绝不返回任何跨员工 / 跨企业数据。
 *
 * <p><b>归属身份（Mock 夹具）</b>：样例数据仅对 {@code entId=E498AU5L35J + userId=U4FXG07VRDI}
 * 可见（与 RC2 样例中的 userId/entId 一致）。其它身份返回空列表而非报错——
 * 「没有待办」与「无权访问」是两回事，不应把空数据伪装成错误，也不应把他人数据混入。
 *
 * <p><b>关于 freshness</b>：取值来自 {@code datahub.tss.wx.freshness}（默认 {@code FRESH}），
 * 之所以不写死：前端「新鲜度提示条」的 {@code DELAYED} / {@code UNAVAILABLE} 两条分支需要能被真实触发，
 * 写死等于这两条分支永远没被测过。真实 ERP 接入（dataMode=ERP）后必须按 {@code asOf} 与阈值计算；
 * 未接入 ERP 只读数据源时不得返回 ERP 模式的 FRESH（应 UNAVAILABLE）。
 */
@Service
public class MockDataStore {

    private static final Logger log = LoggerFactory.getLogger(MockDataStore.class);

    /** 样例数据归属的企业（与 RC2 样例 MeData.entId 一致）。 */
    public static final String SAMPLE_ENT_ID = "E498AU5L35J";
    /** 样例数据归属的用户（与 RC2 样例 MeData.userId 一致）。 */
    public static final String SAMPLE_USER_ID = "U4FXG07VRDI";

    private final MockDataSet data;
    private final TssWxProperties props;
    private final TssWxClock clock;

    public MockDataStore(ObjectMapper objectMapper,
                         ResourceLoader resourceLoader,
                         TssWxProperties props,
                         TssWxClock clock) {
        this.props = props;
        this.clock = clock;
        this.data = loadSample(objectMapper, resourceLoader, props.getMockExamples());
    }

    private static MockDataSet loadSample(ObjectMapper om, ResourceLoader loader, String location) {
        try {
            Resource resource = loader.getResource(location);
            try (InputStream in = resource.getInputStream()) {
                JsonNode root = om.readTree(in);
                if (root == null || root.isMissingNode()) {
                    throw new IllegalStateException("样例文件解析结果为空: " + location);
                }
                return MockDataSet.load(root, om);
            }
        } catch (Exception e) {
            throw new IllegalStateException("无法加载 RC2 静态样例: " + location, e);
        }
    }

    /** 该身份是否为样例数据归属者。 */
    public boolean ownsSampleData(String entId, String userId) {
        return SAMPLE_ENT_ID.equals(entId) && SAMPLE_USER_ID.equals(userId);
    }

    // ------------------------------------------------------------------ 通用

    /** 构造 RC2 DataMeta。 */
    public DataMeta meta() {
        TssEnums.DataMode mode = parseDataMode(props.getDataMode());
        assertDataSourceUsable(mode);
        return new DataMeta(mode, clock.isoNow(), parseFreshness(props.getFreshness()),
                props.getSchemaVersion());
    }

    /**
     * 解析 {@code datahub.tss.wx.freshness}（F2）。
     *
     * <p>默认值 {@code FRESH} 只对 MOCK 数据源成立（数据就在本进程内，无同步延迟）。
     * 非法取值回退 {@code FRESH} 而不是静默吞掉：回退会打 WARN，避免配置拼错后无人察觉。
     */
    static TssEnums.Freshness parseFreshness(String raw) {
        if (raw != null) {
            try {
                return TssEnums.Freshness.valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                log.warn("[TSS-WX] freshness 取值 '{}' 非法（应为 FRESH/DELAYED/UNAVAILABLE），回退为 FRESH", raw);
            }
        }
        return TssEnums.Freshness.FRESH;
    }

    /**
     * 数据源可用性门禁（fail-closed）。
     *
     * <p>声明 {@code dataMode=ERP} 却未接入 ERP 只读通道时，绝不返回空列表——
     * 空结果会被误读为「确实没有待办」，那是比报错更危险的谎言。
     */
    private static void assertDataSourceUsable(TssEnums.DataMode mode) {
        if (mode == TssEnums.DataMode.ERP) {
            throw new TssApiException(TssErrorCode.TSS_ERP_READ_UNAVAILABLE,
                    "已声明 ERP 数据源，但本轮未接入 ERP 只读通道：拒绝返回可能被误读为「无待办」的空结果");
        }
    }

    private static TssEnums.DataMode parseDataMode(String raw) {
        if (raw != null) {
            try {
                return TssEnums.DataMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                log.warn("[TSS-WX] dataMode 取值 '{}' 非法，回退为 MOCK", raw);
            }
        }
        return TssEnums.DataMode.MOCK;
    }

    // ---------------------------------------------------------------- API-03

    public SummaryData summary(String entId, String userId) {
        if (!ownsSampleData(entId, userId)) {
            return new SummaryData(CountGroup.empty(), clock.isoNow(), meta());
        }
        // 统计口径与列表保持一致：按本人待办实时统计，避免出现「列表 3 条、统计却说 1 条」的自相矛盾。
        int purchaseInbound = 0;
        int salesBusiness = 0;
        int payment = 0;
        int salesPricing = 0;
        for (TodoItem t : data.todos()) {
            switch (t.docType()) {
                case PURCHASE_INBOUND -> purchaseInbound++;
                case SALES_BUSINESS -> salesBusiness++;
                case PAYMENT -> payment++;
                case SALES_PRICING -> salesPricing++;
            }
        }
        int total = data.todos().size();
        return new SummaryData(new CountGroup(total, purchaseInbound, salesBusiness,
                payment, salesPricing), clock.isoNow(), meta());
    }

    // ---------------------------------------------------------------- API-04

    public List<TodoItem> todos(String entId, String userId) {
        if (!ownsSampleData(entId, userId)) {
            return new ArrayList<>();
        }
        return new ArrayList<>(data.todos());
    }

    // ---------------------------------------------------------------- API-05

    /** 按 taskNum 取详情；不存在返回 null（由调用方区分 404 与 403）。 */
    public DetailData detailOrNull(String taskNum) {
        return data.detail(taskNum);
    }

    /** 详情归属校验：样例详情只属于归属身份。 */
    public boolean ownsDetail(String taskNum, String entId, String userId) {
        DetailData d = data.detail(taskNum);
        return d != null && ownsSampleData(entId, userId);
    }

    /**
     * 详情归属企业（用于把「跨企业」与「同企业他人」两种不同的 403 区分开）。
     *
     * <p>为什么必须区分：两者对客户端的处理完全不同——
     * 跨企业是<b>作用域</b>问题（换企业也没用，必须换授权），回 {@code TSS_ENT_SCOPE_DENIED}；
     * 同企业他人是<b>资源归属</b>问题（这个单据本来就不在你的待办里），回 {@code TSS_FORBIDDEN}。
     * 一律回 {@code TSS_FORBIDDEN} 会把「企业作用域越界」这一更严重的信号抹平。
     *
     * @return 单据存在时返回其归属企业；不存在返回 null
     */
    public String detailOwnerEntId(String taskNum) {
        return data.detail(taskNum) == null ? null : SAMPLE_ENT_ID;
    }

    // ---------------------------------------------------------------- API-06

    public List<HistoryItem> history(String entId, String userId) {
        if (!ownsSampleData(entId, userId)) {
            return new ArrayList<>();
        }
        return new ArrayList<>(data.history());
    }

    // ---------------------------------------------------------------- API-07

    public List<NotificationItem> notifications(String entId, String userId) {
        if (!ownsSampleData(entId, userId)) {
            return new ArrayList<>();
        }
        return new ArrayList<>(data.notifications());
    }

    // ------------------------------------------------------------ API-12/13

    public AdminMappingsData mappings() {
        return data.mappings();
    }

    /** 稳定排序锚点：MOCK 数据集版本（真实实现应为 ERP 侧的稳定排序键）。 */
    public String sortAnchor() {
        return "mock-dataset-v1";
    }
}
