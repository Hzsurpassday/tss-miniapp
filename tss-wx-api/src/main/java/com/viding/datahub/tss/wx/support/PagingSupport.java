package com.viding.datahub.tss.wx.support;

import com.viding.datahub.tss.wx.config.TssWxProperties;
import com.viding.datahub.tss.wx.cursor.CursorCodec;
import com.viding.datahub.tss.wx.cursor.PageCursor;
import com.viding.datahub.tss.wx.dto.PageMeta;
import com.viding.datahub.tss.wx.envelope.TssApiException;
import com.viding.datahub.tss.wx.envelope.TssErrorCode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 列表分页（F3）。
 *
 * <p>游标由 {@link CursorCodec} 签名并绑定「用户 / 企业 / 筛选条件 / 排序锚点」：
 * 换用户、换筛选、篡改或过期都会被判为 {@code TSS_INVALID_CURSOR}(400)。
 */
@Component
public class PagingSupport {

    private final TssWxProperties props;
    private final CursorCodec codec;

    public PagingSupport(TssWxProperties props, CursorCodec codec) {
        this.props = props;
        this.codec = codec;
    }

    /** 分页结果：条目 + RC2 PageMeta。 */
    public record PageResult<T>(List<T> items, PageMeta page) {
    }

    /**
     * 对已过滤且已排序的列表做分页。
     *
     * @param filtered   已完成本人隔离与筛选、且按稳定锚点排序的列表
     * @param rawCursor  客户端传入的不透明游标（可为 null / 空）
     * @param limitParam 客户端传入的 limit（可为 null ⇒ 用默认值）
     * @param userId     服务端解析出的 userId
     * @param entId      服务端解析出的 entId
     * @param filterHash 当前筛选组合哈希
     * @param anchor     当前稳定排序锚点
     */
    public <T> PageResult<T> page(List<T> filtered,
                                  String rawCursor,
                                  Integer limitParam,
                                  String userId,
                                  String entId,
                                  String filterHash,
                                  String anchor) {
        int limit = resolveLimit(limitParam);
        int offset = codec.decode(rawCursor, userId, entId, filterHash, anchor);
        int size = filtered.size();
        if (offset > size) {
            // 游标越过末尾：不报错，按空页处理（客户端可能并发删除了数据）
            offset = size;
        }
        int end = Math.min(offset + limit, size);
        List<T> items = new ArrayList<>(filtered.subList(offset, end));
        boolean hasMore = end < size;
        String nextCursor = null;
        if (hasMore) {
            nextCursor = codec.encode(new PageCursor(end, anchor, filterHash, userId, entId,
                    System.currentTimeMillis() / 1000));
        }
        return new PageResult<>(items, new PageMeta(nextCursor, hasMore, size));
    }

    /** limit 解析：缺省用配置默认值；越界（&lt;1 或 &gt;max）报 400。 */
    public int resolveLimit(Integer limitParam) {
        int max = props.getCursor().getMaxLimit();
        if (limitParam == null) {
            return props.getCursor().getDefaultLimit();
        }
        if (limitParam < 1 || limitParam > max) {
            throw new TssApiException(TssErrorCode.TSS_INVALID_ARGUMENT,
                    "limit 必须在 1.." + max + " 之间，当前为 " + limitParam);
        }
        return limitParam;
    }

    /** 生成筛选哈希：把 docType / keyword / action / from / to 等条件绑定到游标。 */
    public String filterHash(String resource, String... parts) {
        String[] all = new String[parts.length + 1];
        all[0] = resource;
        System.arraycopy(parts, 0, all, 1, parts.length);
        return codec.filterHash(all);
    }
}
