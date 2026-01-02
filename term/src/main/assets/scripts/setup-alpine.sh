#!/bin/sh
# Alpine Linux setup script for Android AI CLI
# This runs inside the proot environment after Alpine is extracted

set -e

echo "=== Setting up Alpine Linux environment ==="

# Update package index
echo "[1/5] Updating package index..."
apk update

# Install essential packages
echo "[2/5] Installing essential packages..."
apk add --no-cache \
    bash \
    coreutils \
    curl \
    wget \
    git \
    openssh-client \
    ca-certificates \
    nodejs \
    npm \
    python3 \
    py3-pip

# Set bash as default shell
echo "[3/5] Configuring shell..."
echo "export PATH=/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin" >> /root/.profile
echo "export HOME=/root" >> /root/.profile
echo "export TERM=xterm-256color" >> /root/.profile
echo "cd ~" >> /root/.profile

# Install Claude Code
echo "[4/5] Installing Claude Code CLI..."
npm install -g @anthropic-ai/claude-code || echo "Claude Code install will complete on first run"

# Create welcome message
echo "[5/5] Finalizing setup..."
cat > /root/.bashrc << 'BASHRC'
# Android AI CLI - Bash Configuration
export PS1='\[\033[01;32m\]\u@android-ai-cli\[\033[00m\]:\[\033[01;34m\]\w\[\033[00m\]\$ '
export PATH="/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:$HOME/.local/bin:$HOME/node_modules/.bin"
export HOME=/root
export TERM=xterm-256color

# Welcome message
echo ""
echo "  ╔═══════════════════════════════════════╗"
echo "  ║       Android AI CLI Ready!           ║"
echo "  ╠═══════════════════════════════════════╣"
echo "  ║  Type 'claude' to start Claude Code   ║"
echo "  ║  Type 'apk add <pkg>' to install      ║"
echo "  ╚═══════════════════════════════════════╝"
echo ""
BASHRC

echo ""
echo "=== Setup complete! ==="
echo "Type 'claude' to start Claude Code"
