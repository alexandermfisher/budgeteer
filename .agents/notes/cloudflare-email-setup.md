# Budgeteer Email Setup Guide

> **Domain:** budgeteer.amfshr.dev  
> **Gmail Inbox:** fisher.alexander.michael@gmail.com  
> **Last Updated:** March 2026

---

## 📋 Overview

This guide sets up email for Budgeteer so that:
- ✅ Users receive magic links FROM `noreply@budgeteer.amfshr.dev`
- ✅ Users contact support AT `support@budgeteer.amfshr.dev`
- ✅ All emails managed in your Gmail inbox with a "budgeteer" label
- ✅ **100% FREE** (using Cloudflare Email Routing + Resend)

> **Note:** We use Gmail instead of Outlook because Microsoft blocks Cloudflare's email forwarding IPs.

---

## 📧 Email Addresses

| Email | Purpose | How It Works |
|-------|---------|--------------|
| `noreply@budgeteer.amfshr.dev` | Magic links, automated emails | Sent via Resend SMTP |
| `support@budgeteer.amfshr.dev` | User support inquiries | Cloudflare → Gmail |
| `admin@budgeteer.amfshr.dev` | Admin/Resend account | Cloudflare → Gmail |

**Destination Inbox:** `fisher.alexander.michael@gmail.com`

---

## 🔧 SETUP STEPS

### Step 1: Cloudflare Email Routing (Receiving Emails)

This forwards emails to `@budgeteer.amfshr.dev` → your Gmail inbox.

#### 1.1: Enable Email Routing

1. Log into [Cloudflare Dashboard](https://dash.cloudflare.com)
2. Select your domain: **amfshr.dev**
3. Navigate to: **Email** → **Email Routing**
4. Click **Get Started** or **Enable Email Routing**

#### 1.2: Add Destination Email (Gmail)

1. Go to **Destination addresses**
2. Click **Add destination address**
3. Enter: `fisher.alexander.michael@gmail.com`
4. Check your Gmail inbox and click the verification link

#### 1.3: Add DNS Records for Subdomain

In Cloudflare DNS, add these MX and TXT records for the `budgeteer` subdomain:

| Type | Name | Content | Priority |
|------|------|---------|----------|
| MX | budgeteer | `route1.mx.cloudflare.net` | 69 |
| MX | budgeteer | `route2.mx.cloudflare.net` | 20 |
| MX | budgeteer | `route3.mx.cloudflare.net` | 96 |
| TXT | budgeteer | `v=spf1 include:_spf.mx.cloudflare.net ~all` | - |

#### 1.4: Create Email Routes

Go to **Email Routing** → **Routing rules** and create these addresses:

| Custom Address | Action | Destination |
|----------------|--------|-------------|
| `support` @ budgeteer.amfshr.dev | Forward | fisher.alexander.michael@gmail.com |
| `admin` @ budgeteer.amfshr.dev | Forward | fisher.alexander.michael@gmail.com |
| `noreply` @ budgeteer.amfshr.dev | Forward | fisher.alexander.michael@gmail.com |

---

### Step 2: Gmail Filter (Auto-Label Budgeteer Emails)

Create a Gmail filter to automatically label all Budgeteer emails:

1. Open [Gmail](https://mail.google.com)
2. Click the **gear icon** (⚙️) → **See all settings**
3. Go to **Filters and Blocked Addresses** tab
4. Click **Create a new filter**
5. In the **To** field, enter: `@budgeteer.amfshr.dev`
6. Click **Create filter**
7. Check: **Apply the label:** and select your "budgeteer" label
8. (Optional) Check: **Skip the Inbox (Archive it)** if you want emails to go straight to the label
9. Click **Create filter**

Now all emails to `anything@budgeteer.amfshr.dev` will be auto-labeled!

---

### Step 3: Resend Setup (Sending Emails)

Resend sends your magic links from `noreply@budgeteer.amfshr.dev`.

**Free tier:** 3,000 emails/month

#### 3.1: Create Resend Account

1. Go to [resend.com](https://resend.com)
2. Sign up with `admin@budgeteer.amfshr.dev` (or your personal email)
3. Verify your email address

#### 3.2: Add Your Domain

1. In Resend dashboard, go to **Domains**
2. Click **Add Domain**
3. Enter: `budgeteer.amfshr.dev`
4. Resend will provide DNS records to add

#### 3.3: Add DNS Records in Cloudflare

Resend will give you records like these (use the ACTUAL values from Resend):

| Type | Name | Content |
|------|------|---------|
| TXT | resend._domainkey.budgeteer | `p=MIGfMA0GCS...` (DKIM key from Resend) |

> **Merge SPF records:** Update your existing SPF to include Resend:
> ```
> v=spf1 include:_spf.mx.cloudflare.net include:amazonses.com ~all
> ```

#### 3.4: Verify Domain

1. After adding DNS records, click **Verify** in Resend
2. Wait for propagation (usually 5-10 minutes)
3. Status should change to **Verified**

#### 3.5: Create API Key

1. Go to **API Keys** in Resend dashboard
2. Click **Create API Key**
3. Name: `budgeteer-dev` (or `budgeteer-production`)
4. Permission: **Sending access**
5. Domain: `budgeteer.amfshr.dev`
6. **Save the API key securely!**

---

### Step 4: Configure Spring Boot

#### 4.1: Add to `.env`

```properties
# Enable email sending
APP_EMAIL_ENABLED=true

# Resend SMTP configuration
MAIL_HOST=smtp.resend.com
MAIL_PORT=587
MAIL_USERNAME=resend
MAIL_PASSWORD=re_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx

# From address
MAIL_FROM=noreply@budgeteer.amfshr.dev
```

#### 4.2: Verify Configuration

The app is already configured to use these environment variables:
- `application.properties` reads SMTP settings from env vars
- `EmailService.java` uses the configurable `from` address

---

## 📊 Final DNS Records Summary

After setup, your Cloudflare DNS for `budgeteer.amfshr.dev` should include:

| Type | Name | Content |
|------|------|---------|
| MX | budgeteer | route1.mx.cloudflare.net (priority 69) |
| MX | budgeteer | route2.mx.cloudflare.net (priority 20) |
| MX | budgeteer | route3.mx.cloudflare.net (priority 96) |
| TXT | budgeteer | `v=spf1 include:_spf.mx.cloudflare.net include:amazonses.com ~all` |
| TXT | resend._domainkey.budgeteer | (DKIM key from Resend) |

---

## ✅ Setup Checklist

### Cloudflare Email Routing
- [x] Enable Email Routing for amfshr.dev
- [x] Verify destination: fisher.alexander.michael@gmail.com
- [x] Add MX records for `budgeteer` subdomain
- [x] Add SPF record for `budgeteer` subdomain
- [x] Create route: support@budgeteer.amfshr.dev → Gmail
- [x] Create route: admin@budgeteer.amfshr.dev → Gmail
- [x] Create route: noreply@budgeteer.amfshr.dev → Gmail

### Gmail
- [ ] Create filter for @budgeteer.amfshr.dev → "budgeteer" label
- [ ] Test receiving email at support@budgeteer.amfshr.dev

### Resend (Sending)
- [x] Create Resend account
- [ ] Add domain: budgeteer.amfshr.dev
- [ ] Add DKIM DNS record in Cloudflare
- [ ] Update SPF to include Resend (amazonses.com)
- [ ] Verify domain in Resend
- [ ] Create API key
- [ ] Save API key securely

### Spring Boot
- [ ] Add MAIL_PASSWORD (Resend API key) to .env
- [ ] Set APP_EMAIL_ENABLED=true
- [ ] Test magic link sending

---

## 🧪 Testing Guide

### Prerequisites

1. Ensure `.env` has:
   ```properties
   APP_EMAIL_ENABLED=true
   MAIL_HOST=smtp.resend.com
   MAIL_PASSWORD=re_your_api_key
   ```

2. Start the app: `./scripts/dev.sh start`

3. Have Postman ready with these endpoints saved

---

### Test 1: Receiving Emails (Cloudflare → Gmail)

**Goal:** Verify emails TO your domain arrive in Gmail

| Step | Action | Expected Result |
|------|--------|-----------------|
| 1 | From a **different** email account, send to `support@budgeteer.amfshr.dev` | - |
| 2 | Check `fisher.alexander.michael@gmail.com` inbox | Email arrived |
| 3 | Check the email has "budgeteer" label | Label applied |
| 4 | Reply to the email (if "Send as" configured) | Reply sends from custom domain |

**Also test:**
- `admin@budgeteer.amfshr.dev` → should arrive
- `noreply@budgeteer.amfshr.dev` → should arrive
- `random@budgeteer.amfshr.dev` → may bounce (no catch-all)

---

### Test 2: Magic Link - Happy Path

**Goal:** Normal login flow works end-to-end

```bash
# In Postman or curl:
POST http://localhost:8080/api/auth/login
Content-Type: application/json

{
  "email": "your-actual-email@gmail.com"
}
```

| Step | Action | Expected Result |
|------|--------|-----------------|
| 1 | Send login request | Response: `{"success": true, "data": {"message": "Check your email..."}}` |
| 2 | Check your email inbox | Email from `noreply@budgeteer.amfshr.dev` with magic link |
| 3 | Click the magic link | Browser redirects to `/` (error page is OK - no frontend yet) |
| 4 | Check browser cookies at `/api` path | `access_token` and `refresh_token` cookies exist |
| 5 | Test authenticated endpoint | `GET /api/auth/me` with cookie returns user info |

---

### Test 3: Magic Link - Expired Token

**Goal:** Expired links are rejected

**Setup:** Temporarily change expiry to 1 minute:

In `backend/src/main/resources/application-dev.properties`:
```properties
# Change from:
app.jwe.magic-link-expiry=30m

# To:
app.jwe.magic-link-expiry=1m
```

Restart the app: `./scripts/dev.sh restart`

| Step | Action | Expected Result |
|------|--------|-----------------|
| 1 | Request magic link | Email received |
| 2 | **Wait 2 minutes** | - |
| 3 | Click the magic link | Error: "Invalid or expired magic link token" |

**Cleanup:** Change expiry back to `30m` and restart.

---

### Test 4: Magic Link - Reused Token

**Goal:** Magic links can only be used once

| Step | Action | Expected Result |
|------|--------|-----------------|
| 1 | Request magic link | Email received |
| 2 | Click the magic link **first time** | Success - redirects, cookies set |
| 3 | Click the **same link again** | Error: "Invalid or expired magic link token" |

---

### Test 5: Magic Link - Multiple Requests Invalidate Old

**Goal:** Requesting new magic link invalidates previous one

| Step | Action | Expected Result |
|------|--------|-----------------|
| 1 | Request magic link (email 1 received) | - |
| 2 | Request magic link again (email 2 received) | - |
| 3 | Click link from **email 1** (older) | Error: "Invalid or expired magic link token" |
| 4 | Click link from **email 2** (newer) | Success |

---

### Test 6: Invalid Token Format

**Goal:** Random/malformed tokens are rejected

```bash
# Try invalid token:
GET http://localhost:8080/api/auth/verify?token=not-a-real-token

# Try empty token:
GET http://localhost:8080/api/auth/verify?token=

# Try missing token:
GET http://localhost:8080/api/auth/verify
```

| Expected | Result |
|----------|--------|
| All cases | 400/401 error with "Invalid or expired" message |

---

### Test 7: Invalid Email Format

**Goal:** Invalid emails are rejected before sending

```bash
POST http://localhost:8080/api/auth/login
Content-Type: application/json

{
  "email": "not-an-email"
}
```

| Expected | Result |
|----------|--------|
| Response | 400 Bad Request with validation error |
| Email sent? | **No** - validation fails first |

---

### Test 8: New User vs Existing User

**Goal:** New users get accounts created, existing users just get links

| Step | Action | Check Server Logs |
|------|--------|-------------------|
| 1 | Login with never-used email | `Creating new user account [email=...]` |
| 2 | Login with same email again | No "Creating new user" log - just sends link |

---

### DNS Verification

Check your DNS is configured correctly:

- [MXToolbox MX Check](https://mxtoolbox.com/SuperTool.aspx?action=mx%3abudgeteer.amfshr.dev)
- [MXToolbox SPF Check](https://mxtoolbox.com/SuperTool.aspx?action=spf%3abudgeteer.amfshr.dev)
- [MXToolbox DMARC Check](https://mxtoolbox.com/SuperTool.aspx?action=dmarc%3abudgeteer.amfshr.dev) (after adding DMARC)

---

### Test Results Checklist

- [ ] Test 1: Receive email at support@budgeteer.amfshr.dev
- [ ] Test 1: Receive email at admin@budgeteer.amfshr.dev
- [ ] Test 2: Happy path - magic link login works
- [ ] Test 3: Expired token rejected
- [ ] Test 4: Reused token rejected
- [ ] Test 5: Old token invalidated by new request
- [ ] Test 6: Invalid tokens rejected
- [ ] Test 7: Invalid email format rejected
- [ ] Test 8: New vs existing user behavior correct

---

## 📚 Resources

- [Cloudflare Email Routing Docs](https://developers.cloudflare.com/email-routing/)
- [Resend Documentation](https://resend.com/docs)
- [Resend SMTP Guide](https://resend.com/docs/send-with-smtp)
- [Gmail Filters Help](https://support.google.com/mail/answer/6579)

---

## ⚠️ Why Not Outlook?

Microsoft Outlook/Hotmail blocks Cloudflare's email forwarding IPs. When we tried using Outlook as the destination, we got errors like:
```
550: 5.7.1 messages from [104.30.10.214] weren't sent.
Part of their network is on our block list (S3150)
```

Gmail is more tolerant of email forwarding services, making it a reliable choice for Cloudflare Email Routing.

---

## 🔮 Future: Replying FROM Custom Domain

To reply to support emails FROM `support@budgeteer.amfshr.dev`:

1. In Gmail → Settings → **Accounts and Import**
2. Under "Send mail as", click **Add another email address**
3. Enter: `support@budgeteer.amfshr.dev`
4. Use Resend SMTP settings:
   - SMTP: smtp.resend.com
   - Port: 587
   - Username: resend
   - Password: Your Resend API key
5. **Check "Treat as an alias"** - this makes replies automatically use the custom address
6. Gmail will send a verification email - click the link
7. Now you can select "support@budgeteer.amfshr.dev" when composing/replying

---

## 🛡️ DMARC Setup (Recommended)

DMARC improves email deliverability and is now required by Microsoft/Outlook. It builds on SPF + DKIM.

### Add DMARC Record in Cloudflare

In **Cloudflare DNS**, add this TXT record:

| Type | Name | Content |
|------|------|---------|
| TXT | `_dmarc.budgeteer` | `v=DMARC1; p=none; rua=mailto:admin@budgeteer.amfshr.dev` |

### What This Does

- `v=DMARC1` - Version identifier
- `p=none` - Policy (monitoring mode - don't reject emails, just report)
- `rua=mailto:...` - Where to send aggregate reports (optional, can remove)

### Later: Stricter Policy

Once you've confirmed everything works, upgrade to:
```
v=DMARC1; p=quarantine; pct=100
```

This tells receivers to quarantine (spam folder) emails that fail SPF/DKIM checks.

### Full DNS Records with DMARC

After all setup, your Cloudflare DNS should include:

| Type | Name | Content |
|------|------|---------|
| MX | budgeteer | route1/2/3.mx.cloudflare.net |
| TXT | budgeteer | `v=spf1 include:_spf.mx.cloudflare.net include:amazonses.com ~all` |
| TXT | resend._domainkey.budgeteer | (DKIM key from Resend) |
| TXT | _dmarc.budgeteer | `v=DMARC1; p=none; rua=mailto:admin@budgeteer.amfshr.dev` |

### Verify DMARC

Check your DMARC setup at:
- [MXToolbox DMARC Check](https://mxtoolbox.com/SuperTool.aspx?action=dmarc%3abudgeteer.amfshr.dev)
