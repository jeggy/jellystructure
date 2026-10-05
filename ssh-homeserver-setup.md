# SSH to the home server from Claude Code cloud sessions (Cloudflare Tunnel)

## Why this is needed

Claude Code cloud sessions can only reliably reach the internet over HTTPS (port 443).
A direct `ssh -p 2525 jeggy@jebster.net` is dropped there, even on full network access,
while it works from anywhere else (confirmed from a phone hotspot).

A **Cloudflare Tunnel** carries SSH inside an HTTPS websocket, so the cloud session can
reach the server. It does not need an open router port.

**You need:**
- `jebster.net` on Cloudflare DNS (the free plan is fine)
- sudo on the Debian server
- the `SSH_PRIVATE_KEY` secret already set in the cloud environment

---

## Part 1 — On the Debian server

### 1.1 Install `cloudflared`

```bash
sudo mkdir -p --mode=0755 /usr/share/keyrings
curl -fsSL https://pkg.cloudflare.com/cloudflare-main.gpg \
  | sudo tee /usr/share/keyrings/cloudflare-main.gpg >/dev/null
echo 'deb [signed-by=/usr/share/keyrings/cloudflare-main.gpg] https://pkg.cloudflare.com/cloudflared any main' \
  | sudo tee /etc/apt/sources.list.d/cloudflared.list
sudo apt-get update && sudo apt-get install -y cloudflared
```

### 1.2 Find the port sshd listens on locally

```bash
sudo ss -tlnp | grep sshd
```

If your router forwards 2525 → 22, sshd is on **22**. Use the port this shows in step 1.3.

### 1.3 Create the tunnel (Cloudflare dashboard)

1. Go to **Cloudflare dashboard → Zero Trust → Networks → Tunnels → Create a tunnel**.
2. Choose **Cloudflared**, name it `homeserver`, and save.
3. Pick **Debian**. Copy the command it shows (`sudo cloudflared service install eyJ...`)
   and run it on the server. This installs a systemd service that starts on boot.
4. Under **Public Hostname**, add:

   | Field        | Value                              |
   |--------------|------------------------------------|
   | Subdomain    | `ssh`                              |
   | Domain       | `jebster.net`                      |
   | Service type | `SSH`                              |
   | URL          | `localhost:22` (port from step 1.2) |

5. Save. Cloudflare creates the `ssh.jebster.net` DNS record automatically.

### 1.4 Check the tunnel is up

```bash
systemctl status cloudflared
```

In the dashboard, the tunnel should show **HEALTHY**.

---

## Part 2 — Test from your own computer

Install `cloudflared` locally (`brew install cloudflared` on a Mac, or the apt steps
above on Linux), then:

```bash
ssh -o ProxyCommand="cloudflared access ssh --hostname %h" jeggy@ssh.jebster.net "uptime"
```

If that prints the uptime, the tunnel works.

---

## Part 3 — Configure the Claude Code cloud environment

### 3.1 Check the secret

Open the cloud environment menu in the session's title bar, choose **Edit**, and make sure
`SSH_PRIVATE_KEY` is set (the full private key, including the `BEGIN`/`END` lines).

### 3.2 Add this to the environment's setup script

```bash
# cloudflared client
curl -fsSL -o /usr/local/bin/cloudflared \
  https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64
chmod +x /usr/local/bin/cloudflared

# key + ssh config
mkdir -p ~/.ssh && chmod 700 ~/.ssh
printf '%s\n' "$SSH_PRIVATE_KEY" > ~/.ssh/cloud-agent
chmod 600 ~/.ssh/cloud-agent
cat > ~/.ssh/config <<'EOF'
Host homeserver
  HostName ssh.jebster.net
  User jeggy
  IdentityFile ~/.ssh/cloud-agent
  IdentitiesOnly yes
  StrictHostKeyChecking accept-new
  ProxyCommand /usr/local/bin/cloudflared access ssh --hostname %h
EOF
chmod 600 ~/.ssh/config
```

### 3.3 Allow SSH commands

Sessions in **auto mode** may block `ssh` commands. Either run the session in the default
permission mode (and approve when asked), or add this to `.claude/settings.json` in the
repo (or your user settings):

```json
{
  "permissions": {
    "allow": ["Bash(ssh:*)"]
  }
}
```

### 3.4 Test from a new session

Ask Claude to run:

```bash
ssh homeserver "echo 'Successfully connected to Debian host!' && uptime"
```

---

## Notes

- **Security:** the SSH key is still what protects the server. To restrict it further,
  add a Zero Trust **Access application** for `ssh.jebster.net` with a **service token**.
  Then add `TUNNEL_SERVICE_TOKEN_ID` and `TUNNEL_SERVICE_TOKEN_SECRET` as environment
  secrets in the cloud environment.
- **Not yet confirmed:** that `cloudflared` gets out through the cloud environment's proxy.
  It uses HTTPS on 443, so it should, but the first test in step 3.4 will tell.
- **Port 2525:** you can keep the router port forward for your own use; the tunnel
  doesn't need it.
- **Troubleshooting:** add `-v` to the ssh command for verbose output. On the server,
  `journalctl -u cloudflared -f` shows tunnel logs.
