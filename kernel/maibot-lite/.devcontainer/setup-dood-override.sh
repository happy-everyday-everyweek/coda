#!/bin/sh
# ============================================================================
# Docker-outside-of-Docker (DooD) bind mount 路径修正脚本
# ============================================================================
# 问题：VS Code devcontainer 将宿主机项目路径（如 /home/user/MaiBot）挂载到
#       容器内的 ${containerWorkspaceFolder}。当 docker compose 在容器内执行时，
#       Docker Compose 将相对路径 ./ 解析为容器内路径，发送给宿主机 Docker 守护进程。
#       守护进程在宿主机上找不到同名路径，自动创建空目录，导致数据"消失"。
#
# 解决：从 /proc/1/mountinfo 提取宿主机真实项目路径，生成 docker-compose.devcontainer.yml，
#       将 bind mount 源路径改写为宿主机绝对路径。该文件仅在 devcontainer 内通过
#       COMPOSE_FILE 环境变量加载，容器外 docker compose 不受任何影响。
#
# 兼容性：devcontainer 始终运行 Linux，本脚本依赖 /proc/1/mountinfo（Linux 内核接口）。
#         Windows/macOS 宿主机的 Docker Desktop 会自动处理路径转换。
# ============================================================================

set -eu

WORKSPACE_FOLDER="${1:-/workspaces/MaiBot}"
OVERRIDE_FILE="${WORKSPACE_FOLDER}/docker-compose.devcontainer.yml"

# 从 /proc/1/mountinfo 提取宿主机上对应 workspace 的真实路径
# mountinfo 格式: mount_id parent_id major:minor root mount_point options - fs_type source super_options
# 我们需要第5列(mount_point)匹配 workspace，取第4列(root)为宿主机路径
HOST_ROOT=$(awk -v ws="$WORKSPACE_FOLDER" '$5 == ws {print $4; exit}' /proc/1/mountinfo)

if [ -z "$HOST_ROOT" ]; then
    echo "[devcontainer] ⚠ 未能从 /proc/1/mountinfo 检测到 ${WORKSPACE_FOLDER} 的宿主机路径"
    echo "[devcontainer] docker compose 的 bind mount 可能指向错误位置，数据不会持久化"
    exit 0
fi

echo "[devcontainer] 检测到宿主机项目路径: ${HOST_ROOT}"

# 生成 docker-compose.devcontainer.yml（通过 COMPOSE_FILE 加载）
# 仅覆盖 volumes 配置，其余配置继承自 docker-compose.yml
cat > "$OVERRIDE_FILE" << OVERRIDE_EOF
# 此文件由 .devcontainer/setup-dood-override.sh 自动生成
# 修复 Docker-outside-of-Docker 场景下 bind mount 的宿主机路径解析问题
# 仅在 devcontainer 内通过 COMPOSE_FILE 环境变量加载，容器外 docker compose 不受影响
# 请勿手动编辑，删除后将在下次容器创建时重新生成
services:
  core:
    volumes:
      - ${HOST_ROOT}/docker-config/mmc:/MaiMBot/config
      - ${HOST_ROOT}/data/MaiMBot:/MaiMBot/data
      - ${HOST_ROOT}/data/MaiMBot/emoji:/data/emoji
      - ${HOST_ROOT}/data/MaiMBot/plugins:/MaiMBot/plugins
      - ${HOST_ROOT}/data/MaiMBot/logs:/MaiMBot/logs
      - ${HOST_ROOT}/depends-data:/MaiMBot/depends-data
  napcat:
    volumes:
      - ${HOST_ROOT}/docker-config/napcat:/app/napcat/config
      - ${HOST_ROOT}/data/qq:/app/.config/QQ
      - ${HOST_ROOT}/data/MaiMBot:/MaiMBot/data
  sqlite-web:
    volumes:
      - ${HOST_ROOT}/data/MaiMBot:/data/MaiMBot
OVERRIDE_EOF

echo "[devcontainer] ✓ 已生成 ${OVERRIDE_FILE}"
