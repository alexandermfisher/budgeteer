# ☁️ Cloudflare Tunnel Setup Guide

## 🧩 1. Install `cloudflared`

### Ubuntu / Debian
```bash
wget https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64.deb
sudo dpkg -i cloudflared-linux-amd64.deb
```

### macOS (Homebrew)
```bash
brew install cloudflare/cloudflare/cloudflared
```

Check install:
```bash
cloudflared --version
```

---

## ⚙️ 2. Run a **Quick Tunnel** (no domain required)

Use this for local testing or when you don’t own a domain yet.

```bash
# Run your app locally (e.g. Spring Boot on port 8080)
cloudflared tunnel --url http://localhost:8080
```

You’ll get output like:
```
Your Quick Tunnel has been created!
https://quiet-sun-1234.trycloudflare.com
```

✅ That public URL forwards traffic to your local port 8080.

Use it for:
- Testing Monzo webhooks:  
  `https://quiet-sun-1234.trycloudflare.com/webhook/monzo`
- Testing OAuth callbacks.

> 💡 The tunnel stops when you close the terminal, and the URL changes on restart.

---

## 🌐 3. Set up a **Permanent Tunnel** (when you have a domain)

### a) Authenticate
```bash
cloudflared tunnel login
```
Opens your browser → pick your domain in Cloudflare.

### b) Create the tunnel
```bash
cloudflared tunnel create monzo-webhook
```

### c) Configure it  
Create `~/.cloudflared/config.yml`:
```yaml
tunnel: <UUID-from-create>
credentials-file: /home/<user>/.cloudflared/<UUID>.json

ingress:
  - hostname: hooks.yourdomain.com
    path: /webhook/monzo
    service: http://localhost:8080/webhook/monzo
  - service: http_status:404
```

### d) Route DNS to the tunnel
```bash
cloudflared tunnel route dns <UUID> hooks.yourdomain.com
```

### e) Run it
```bash
cloudflared tunnel run monzo-webhook
```

✅ `https://hooks.yourdomain.com/webhook/monzo` → proxies securely to your local Spring Boot app.

---

## 🔒 4. Secure it (recommended for production)
- **Cloudflare WAF Rule:** allow only  
  `POST /webhook/monzo`
- **Rate limit:** e.g. 60 requests/min.
- **Fallback:** return `404` for other paths.
- Spring listens only on `localhost` (or Docker network).
- Validate webhook body and headers.

---

## 🧱 5. Optional: Cloudflare Access (for dashboard)
If you later expose your web dashboard remotely:
- Create a second tunnel route, e.g.  
  `app.yourdomain.com` → `http://localhost:8080`
- In Cloudflare Zero Trust → **Access**, protect it with SSO (Google, GitHub, etc.)
- Keep `/webhook/monzo` open (Access can’t gate webhooks).

---

## 🧠 Summary

| Use Case | Method | URL Type | Notes |
|-----------|---------|----------|-------|
| Local dev / testing | Quick Tunnel | `*.trycloudflare.com` | Free, ephemeral |
| Always-on webhook | Permanent Tunnel | `hooks.yourdomain.com` | Requires domain |
| Private remote dashboard | Tunnel + Access | `app.yourdomain.com` | Protected by SSO |
