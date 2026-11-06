package com.viding.datahub.tss.wx.mock;

import com.viding.datahub.tss.wx.dto.BaseField;
import com.viding.datahub.tss.wx.dto.DetailData;
import com.viding.datahub.tss.wx.dto.HistoryItem;
import com.viding.datahub.tss.wx.dto.TodoItem;
import com.viding.datahub.tss.wx.dto.TssEnums;
import com.viding.datahub.tss.wx.dto.Workflow;
import com.viding.datahub.tss.wx.dto.WorkflowEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>自造测试数据种子（TEST SEED，非真实数据）</b>。
 *
 * <p><b>为什么需要它</b>：RC2 冻结样例 {@code api-examples-rc2.json} 里每类只有 1 条记录，
 * 因此 {@code nextCursor} 恒为 null、{@code hasMore=false}，无法给出「多页正向流转」的实测证据。
 * 本类在冻结样例之外追加若干条记录，专供分页 / 筛选验证使用。
 *
 * <p><b>诚实声明（必须随数据一起传播，不得删除）</b>：
 * <ol>
 *   <li>这些记录<b>不是</b>真实 ERP 导出，<b>不是</b> RC2 冻结样例，是本模块为验证分页而<b>自造</b>的；</li>
 *   <li>所有标题 / 往来单位均以 {@link #TEST_MARK}（{@code 【测试数据】}）开头，
 *       一眼可辨，不会被误读为真实单据；</li>
 *   <li>{@code dataMode} 恒为 {@code MOCK}；详情中未经 ERP 核实的字段保持
 *       {@code verified=false}，不美化；</li>
 *   <li>时间取值固定在 2026-10 区间，既保证排序稳定可复现，也不污染「11 月无数据」类筛选断言。</li>
 * </ol>
 */
public final class MockTestSeed {

    /** 测试数据标记：所有自造记录的标题/往来单位均带此前缀，防止被误当真实业务数据。 */
    public static final String TEST_MARK = "【测试数据】";

    /** 空 {@code events} 夹具的单号：只登记在详情表，不进入待办列表（见 {@link #emptyEventsDetail}）。 */
    public static final String EMPTY_EVENTS_TASK_NUM = "355380";

    private MockTestSeed() {
    }

    /**
     * 追加的待办（2 条）：与冻结样例的 1 条合计 3 条，足以支撑 limit=1 的三页流转。
     *
     * <p>{@code updatedAt} 递减排列，保证「按 updatedAt 倒序 + taskNum 升序」的稳定排序结果是
     * {@code 355377 → 355378 → 355379}，分页断言可复现。
     */
    public static List<TodoItem> extraTodos() {
        List<TodoItem> out = new ArrayList<>();
        out.add(new TodoItem(
                "a_355373_355378", "355378", "355373", "77972",
                TssEnums.DocType.PURCHASE_INBOUND, TEST_MARK + "采购入库审批",
                TEST_MARK + "示例供应商", "8600.00", "CNY", "部门审核",
                "2026-10-31T09:20:00+08:00", "2026-10-31T09:20:00+08:00",
                TssEnums.TodoStatus.PENDING, TssEnums.DataMode.MOCK));
        out.add(new TodoItem(
                "a_355374_355379", "355379", "355374", "77973",
                TssEnums.DocType.SALES_BUSINESS, TEST_MARK + "销售业务审批",
                TEST_MARK + "示例客户B", "12345.67", "CNY", "风控审核",
                "2026-10-31T08:10:00+08:00", "2026-10-31T08:10:00+08:00",
                TssEnums.TodoStatus.PENDING, TssEnums.DataMode.MOCK));
        return out;
    }

    /**
     * 追加的历史（4 条）：单号使用与在办待办<b>完全错开</b>的已归档号段（355101~355104）。
     *
     * <p><b>为什么要错开</b>：历史记录代表「已办结」的单据，若其 taskNum 同时出现在待办
     * （PENDING 在办）里，就自相矛盾——同一单据不可能既是「待我审批」又是「我已审批完」。
     * 演示时这种矛盾一眼可见，因此种子一律只使用归档号段。
     *
     * <p><b>resultVerified 一律 false</b>：MOCK 数据未经 ERP 回查，
     * 声称「结论已回查证实」是站不住的；只有照搬对方冻结样例的记录才保留其原值（见 README 登记）。
     *
     * <p>动作分布：APPROVED 2 条、REJECTED 2 条；时间全部落在 2026-10-01..2026-10-31，
     * 因此「11 月区间应为空」的筛选断言仍然成立。
     */
    public static List<HistoryItem> extraHistory() {
        List<HistoryItem> out = new ArrayList<>();
        out.add(new HistoryItem("r_t1", "a_355001_355101", "355101", "77881",
                TssEnums.DocType.PURCHASE_INBOUND, TssEnums.HistoryAction.APPROVED, "审批完成",
                "2026-10-20T16:40:00+08:00", TEST_MARK + "示例员工", false));
        out.add(new HistoryItem("r_t2", "a_355002_355102", "355102", "77882",
                TssEnums.DocType.SALES_BUSINESS, TssEnums.HistoryAction.REJECTED, "审批驳回",
                "2026-10-15T11:15:00+08:00", TEST_MARK + "示例员工", false));
        out.add(new HistoryItem("r_t3", "a_355003_355103", "355103", "77883",
                TssEnums.DocType.PAYMENT, TssEnums.HistoryAction.APPROVED, "审批完成",
                "2026-10-08T14:00:00+08:00", TEST_MARK + "示例员工", false));
        out.add(new HistoryItem("r_t4", "a_355004_355104", "355104", "77884",
                TssEnums.DocType.SALES_PRICING, TssEnums.HistoryAction.REJECTED, "审批驳回",
                "2026-10-02T09:05:00+08:00", TEST_MARK + "示例员工", false));
        return out;
    }

    /**
     * 为追加待办派生详情（同样标注为测试数据）。
     *
     * <p>派生方式：以冻结样例的详情为模板，只替换 {@code approvalId / taskNum / docType /
     * instanceId / billNo / amount}，其余结构（字段、行、工作流、来源状态、可执行动作）原样沿用，
     * 因此派生详情同样满足 {@code additionalProperties:false}，也保持 V1.0 只读
     * （{@code workflow.readOnly=true}、{@code availableActions} 仅 VIEW）。
     *
     * @param sample 冻结样例的详情（模板）
     * @return taskNum → 派生详情
     */
    public static Map<String, DetailData> extraDetails(DetailData sample) {
        Map<String, DetailData> out = new LinkedHashMap<>();
        for (TodoItem t : extraTodos()) {
            out.put(t.taskNum(), deriveDetail(sample, t));
        }
        return out;
    }

    /**
     * 空 {@code events} 夹具（RC2：{@code events} 可为<b>空数组</b>，但字段本身 required，不得省略）。
     *
     * <p><b>为什么必须有这一条夹具</b>：冻结样例里 {@code events} 恒为非空，
     * 于是「流程尚无流转事件」这一形态在联调中<b>永远走不到</b>——
     * 前端「暂无流转记录」的空态分支等于没被测过，真到了那一天就是线上才发现。
     *
     * <p><b>为什么只做详情、不进待办列表</b>：待办条数已被分页用例（共 3 条、limit=1 三页）锁死，
     * 再加一条会让既有断言失去意义。因此本夹具只登记到详情表，
     * 直接用 {@code GET /wx/v1/todos/355380/detail} 访问即可，不影响任何列表口径。
     *
     * @param sample 冻结样例的详情（模板）
     * @return 只含一条（空 events 详情）的映射
     */
    public static Map<String, DetailData> emptyEventsDetail(DetailData sample) {
        Map<String, DetailData> out = new LinkedHashMap<>();
        TodoItem carrier = new TodoItem(
                "a_355375_" + EMPTY_EVENTS_TASK_NUM, EMPTY_EVENTS_TASK_NUM, "355375", "77974",
                TssEnums.DocType.PAYMENT, TEST_MARK + "空流转事件夹具（不进列表）",
                TEST_MARK + "示例客户C", "1000.00", "CNY", "部门审核",
                "2026-10-30T10:00:00+08:00", "2026-10-30T10:00:00+08:00",
                TssEnums.TodoStatus.PENDING, TssEnums.DataMode.MOCK);
        out.put(EMPTY_EVENTS_TASK_NUM, deriveDetail(sample, carrier, List.of()));
        return out;
    }

    private static DetailData deriveDetail(DetailData sample, TodoItem t) {
        return deriveDetail(sample, t, sample.workflow() == null ? null : sample.workflow().events());
    }

    /**
     * 以冻结样例为模板派生详情。
     *
     * @param events 工作流事件列表；传 {@code List.of()} 即得到「空 events」夹具
     *               （数组为空但字段本身仍然输出，满足 RC2 required）
     */
    private static DetailData deriveDetail(DetailData sample, TodoItem t, List<WorkflowEvent> events) {
        List<BaseField> base = new ArrayList<>();
        for (BaseField f : sample.base()) {
            Object value = f.value();
            if ("billNo".equals(f.key())) {
                value = t.billNo();
            } else if ("amount".equals(f.key())) {
                value = t.amount();
            }
            base.add(new BaseField(f.key(), f.label(), value, f.format(), f.verified()));
        }
        Workflow wf = sample.workflow();
        Workflow newWf = wf == null ? null : new Workflow(wf.workflowId(), t.instanceId(),
                wf.currentNodeIds(), wf.nodes(), wf.edges(), wf.graphType(), wf.dataVersion(),
                wf.definitionVersion(), wf.readOnly(), events);
        return new DetailData(t.approvalId(), t.taskNum(), t.docType(), base, sample.lines(),
                newWf, sample.sourceStatus(), sample.availableActions(), sample.meta());
    }
}
