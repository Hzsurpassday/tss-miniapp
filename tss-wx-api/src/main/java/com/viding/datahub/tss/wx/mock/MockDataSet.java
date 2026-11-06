package com.viding.datahub.tss.wx.mock;

import com.viding.datahub.tss.wx.dto.AdminBindingsData;
import com.viding.datahub.tss.wx.dto.AdminMappingsData;
import com.viding.datahub.tss.wx.dto.DetailData;
import com.viding.datahub.tss.wx.dto.HistoryItem;
import com.viding.datahub.tss.wx.dto.NotificationItem;
import com.viding.datahub.tss.wx.dto.SummaryData;
import com.viding.datahub.tss.wx.dto.TodoItem;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * RC2 静态样例数据集（B1）。
 *
 * <p>数据<b>逐字取自</b> {@code api-examples-rc2.json}（与桌面冻结样例同一份文件，已随模块打包），
 * 解析后映射为与 RC2 schema 一一对应的强类型结构，因此对外序列化结果天然满足
 * {@code additionalProperties:false}。
 *
 * <p><b>诚实声明</b>：这些是 MOCK 样例，<b>不代表已接入真实 ERP</b>，也不代表 ERP 中确有这些单据。
 * 任何字段的来源未经 ERP 核实的，其 {@code verified=false} 保持样例原值，不做美化为 true。
 */
public final class MockDataSet {

    private final SummaryData summary;
    private final List<TodoItem> todos;
    private final Map<String, DetailData> detailsByTaskNum;
    private final List<HistoryItem> history;
    private final List<NotificationItem> notifications;
    private final AdminBindingsData bindings;
    private final AdminMappingsData mappings;

    private MockDataSet(SummaryData summary,
                        List<TodoItem> todos,
                        Map<String, DetailData> detailsByTaskNum,
                        List<HistoryItem> history,
                        List<NotificationItem> notifications,
                        AdminBindingsData bindings,
                        AdminMappingsData mappings) {
        this.summary = summary;
        this.todos = todos;
        this.detailsByTaskNum = detailsByTaskNum;
        this.history = history;
        this.notifications = notifications;
        this.bindings = bindings;
        this.mappings = mappings;
    }

    /**
     * 从样例 JSON 根节点解析。
     *
     * @param root {@code api-examples-rc2.json} 的根节点
     * @param om   Jackson 3 的 ObjectMapper
     */
    public static MockDataSet load(JsonNode root, ObjectMapper om) {
        SummaryData summary = data(om, root, "API-03", SummaryData.class);
        TodoItem[] todoArr = data(om, root, "API-04", com.viding.datahub.tss.wx.dto.TodoListData.class).items()
                .toArray(new TodoItem[0]);
        List<TodoItem> todos = new ArrayList<>(List.of(todoArr));

        DetailData detail = data(om, root, "API-05", DetailData.class);
        Map<String, DetailData> details = new LinkedHashMap<>();
        details.put(detail.taskNum(), detail);

        List<HistoryItem> history = new ArrayList<>(
                data(om, root, "API-06", com.viding.datahub.tss.wx.dto.HistoryData.class).items());

        // 追加自造测试数据种子（见 MockTestSeed 的诚实声明）：仅供分页/筛选验证，非 ERP 真实数据
        todos.addAll(MockTestSeed.extraTodos());
        details.putAll(MockTestSeed.extraDetails(detail));
        // 空 events 夹具：只进详情表，不进待办列表（不影响既有分页断言的 3 条口径）
        details.putAll(MockTestSeed.emptyEventsDetail(detail));
        history.addAll(MockTestSeed.extraHistory());

        // 夹具自洽规则：历史代表「已办结」，因此历史里的单号不得同时出现在在办待办中，
        // 否则会出现「同一单据既是 PENDING 待办、又是已审批历史」的观感冲突（演示时无法当众解释）。
        // 注意：冻结样例中 355377 同时出现在 API-04(PENDING) 与 API-06(APPROVED)。
        // 契约 9.2 允许「同一 TASKNUM 多候选人」，因此该情况**不必然是缺陷**——
        // 若那条 APPROVED 属另一候选人的已办记录，即为合法状态；样例未标注处理人归属，我方无法断定。
        // 故此处只做「演示自洽」处理：按规则把该条暂剔除，绝不改写对方样例任何字段内容。
        // 详见 README §5.3.1「待澄清询问项（非指控）」。
        Set<String> openTaskNums = new HashSet<>();
        for (TodoItem t : todos) {
            if (t.taskNum() != null) {
                openTaskNums.add(t.taskNum());
            }
        }
        history.removeIf(h -> h.taskNum() != null && openTaskNums.contains(h.taskNum()));
        List<NotificationItem> notifications = new ArrayList<>(
                data(om, root, "API-07", com.viding.datahub.tss.wx.dto.NotificationData.class).items());

        AdminBindingsData bindings = data(om, root, "API-12", AdminBindingsData.class);
        AdminMappingsData mappings = data(om, root, "API-13", AdminMappingsData.class);

        return new MockDataSet(summary, todos, details, history, notifications, bindings, mappings);
    }

    /** 取指定 API 的成功响应 {@code data} 节点并转换为强类型。 */
    private static <T> T data(ObjectMapper om, JsonNode root, String apiKey, Class<T> type) {
        JsonNode apiNode = root.get(apiKey);
        if (apiNode == null) {
            throw new IllegalStateException("样例文件缺少节点: " + apiKey);
        }
        JsonNode dataNode = apiNode.get("response").get("data");
        if (dataNode == null || dataNode.isNull()) {
            throw new IllegalStateException("样例文件 " + apiKey + " 缺少 response.data");
        }
        return om.treeToValue(dataNode, type);
    }

    public SummaryData summary() {
        return summary;
    }

    public List<TodoItem> todos() {
        return todos;
    }

    public Map<String, DetailData> detailsByTaskNum() {
        return detailsByTaskNum;
    }

    public DetailData detail(String taskNum) {
        return taskNum == null ? null : detailsByTaskNum.get(taskNum);
    }

    public List<HistoryItem> history() {
        return history;
    }

    public List<NotificationItem> notifications() {
        return notifications;
    }

    public AdminBindingsData bindings() {
        return bindings;
    }

    public AdminMappingsData mappings() {
        return mappings;
    }
}
