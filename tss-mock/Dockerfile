FROM python:3.12-alpine

LABEL org.opencontainers.image.title="tss-contract-mock" \
      org.opencontainers.image.description="时空智审 V1.0 /wx/v1 契约 MOCK 服务（RC3），仅供联调，不含真实 ERP 凭据，也不含硬编码管理员密钥" \
      org.opencontainers.image.version="1.0.0-rc3"

WORKDIR /app
COPY server.py verify.py README.md ./

# 非 root 运行
RUN addgroup -S tss && adduser -S -G tss tss && chown -R tss:tss /app
USER tss

EXPOSE 9000

HEALTHCHECK --interval=30s --timeout=3s --start-period=3s --retries=3 \
  CMD python3 -c "import urllib.request,sys;sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:9000/healthz',timeout=2).status==200 else 1)"

# 注意：此处刻意不设置 TSS_ADMIN_TOKEN 默认值。
# 未注入时 server.py 会生成临时随机令牌并只写入 stderr（docker logs 可见）。
ENV TSS_MOCK_PORT=9000 \
    TSS_MOCK_DATA_MODE=MOCK \
    TSS_MOCK_FAILURE=none \
    TSS_MOCK_FRESHNESS=FRESH

CMD ["python3", "-u", "server.py"]
