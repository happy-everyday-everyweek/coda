FROM mcr.microsoft.com/devcontainers/python:1-3.13-bookworm

# 修复 Yarn APT 仓库 GPG 密钥过期（NO_PUBKEY 62D54FD4003F6525）
# 必须在任何 apt-get update 之前移除，否则 features 中的 apt-packages 等会失败
RUN rm -f /etc/apt/sources.list.d/yarn.list

# 安装 uv：与生产 Dockerfile 一致
COPY --from=ghcr.io/astral-sh/uv:latest /uv /uvx /bin/

# 需要使用 gitpod-remote-ssh 扩展的开发者请注释下面三行构建命令
RUN for d in /home/vscode/.vscode-server/extensions/gitpod.gitpod-remote-ssh-*; do \
        [ -d "$d" ] && mv "$d" "${d}.disabled"; \
    done; exit 0
