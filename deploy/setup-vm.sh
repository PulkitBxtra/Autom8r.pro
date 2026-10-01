#!/usr/bin/env bash
# One-time preparation of a fresh Ubuntu server (24.04, arm64 or amd64) for the compose stack:
# Docker Engine with the compose plugin, the firewall opened for HTTP/HTTPS, Docker started at boot.
# Run as the login user (it uses sudo); safe to run again.
#   ./deploy/setup-vm.sh
set -euo pipefail

if ! command -v docker >/dev/null; then
  echo "== Installing Docker"
  curl -fsSL https://get.docker.com | sudo sh
fi
sudo systemctl enable --now docker
if ! id -nG "$USER" | grep -qw docker; then
  sudo usermod -aG docker "$USER"
  echo "   (log out and back in for the docker group to apply)"
fi

# Oracle's Ubuntu images ship iptables rules that reject everything except SSH; the cloud
# security list must allow 80/443 too. Inserted just before the first REJECT, and saved so they
# survive a reboot.
if sudo iptables -S INPUT | grep -q -- '-j REJECT'; then
  echo "== Opening ports 80 and 443 in the server's firewall"
  for rule in "-p tcp --dport 80" "-p tcp --dport 443" "-p udp --dport 443"; do
    # shellcheck disable=SC2086
    if ! sudo iptables -C INPUT -m state --state NEW $rule -j ACCEPT 2>/dev/null; then
      line=$(sudo iptables -L INPUT --line-numbers | awk '/REJECT/ {print $1; exit}')
      # shellcheck disable=SC2086
      sudo iptables -I INPUT "$line" -m state --state NEW $rule -j ACCEPT
    fi
  done
  if command -v netfilter-persistent >/dev/null; then
    sudo netfilter-persistent save >/dev/null
  fi
fi

echo "== Done. Next: put deploy/.env in place (chmod 600), then ./deploy/deploy.sh"
