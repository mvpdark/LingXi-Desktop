#!/bin/sh
# ===========================================================================
# 灵犀后端容器启动入口（多租户 SaaS 纯 API）
#
#   1. python -m src.db.ensure_schema —— 幂等建表 + usage_stats 增量迁移，
#      失败时非零退出，配合 set -e 阻断启动（依赖 restart 策略形成重试，
#      等待 PostgreSQL 就绪场景下容器会自动重拉直至成功）
#   2. exec uvicorn 启动主进程（exec 保证 PID 1 信号正确传递，可优雅停止）
#
# 启动方式决策（uvicorn 直启 vs python src/server.py）：
#   选择 uvicorn src.server:app 直启。src/server.py 的全部初始化（sys.path
#   设置、Config 加载、数据库引擎、各 Service 构建、FastAPI app 与 lifespan）
#   均在模块顶层完成，import 即生效；if __name__ == "__main__" 块内仅做了
#   三件非必需的事：_cleanup_expired_images() 一次性临时图片清理（lifespan
#   内已有每小时 _cleanup_old_assets 循环兜底）、打印启动横幅、以完全相同
#   的参数调用 uvicorn.run。因此直启 uvicorn 行为等价且更干净。
#   下列 ws-ping / keep-alive 参数与 __main__ 块内的 uvicorn.run 参数一致。
# ===========================================================================
set -e

# 纯 API 模式：不托管前端静态文件（Web 前端已移除，全部使用原生客户端）
export SERVE_FRONTEND="${SERVE_FRONTEND:-false}"

python -m src.db.ensure_schema

# 资源目录可写检测（Unraid 卷挂载常见权限问题，提前失败并给出修复指引）
ASSETS_DIR="${ASSETS_DIR:-/app/assets_cache}"
if ! mkdir -p "$ASSETS_DIR" 2>/dev/null || ! touch "$ASSETS_DIR/.write_test" 2>/dev/null; then
  echo "============================================================" >&2
  echo "[FATAL] $ASSETS_DIR 不可写（Unraid 卷权限问题）" >&2
  echo "请在 Unraid 宿主机执行: chown -R 1000:1000 <对应宿主机目录>" >&2
  echo "============================================================" >&2
  exit 1
fi
rm -f "$ASSETS_DIR/.write_test"

exec uvicorn src.server:app \
    --host 0.0.0.0 \
    --port 8765 \
    --ws-ping-interval 10 \
    --ws-ping-timeout 60 \
    --timeout-keep-alive 30
