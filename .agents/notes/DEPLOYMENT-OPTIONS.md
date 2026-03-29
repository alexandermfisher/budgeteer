# Deployment Options Overview

> Personal notes on deployment strategies for Budgeteer

## 🎯 Your Situation

- **Dev machine:** Mac (local development)
- **Prod machine:** NUC (Linux, future)
- **Team size:** Solo developer
- **Scale:** Single user personal app
- **Current stage:** Development, no prod yet

---

## 📊 Deployment Methods Comparison

| Method | Complexity | Best For | Overkill For |
|--------|------------|----------|--------------|
| **Docker Compose** | ⭐ Low | Solo/small teams, single host | - |
| **Docker Swarm** | ⭐⭐ Medium | Multi-host, basic orchestration | Single host apps |
| **Kubernetes** | ⭐⭐⭐⭐ High | Large teams, microservices, scaling | Personal projects |
| **VM (direct)** | ⭐ Low | Simple, traditional | When containerization adds value |
| **Bare metal** | ⭐ Low | Maximum performance | Most cases |

---

## 🐳 Option 1: Docker Compose (RECOMMENDED for you)

**How it works:**
- Same `compose.yaml` for dev and prod
- Different `.env` files for each environment
- Simple `docker compose up -d` to deploy

**Dev/Prod Separation:**
```
# Dev (on Mac)
.env                    # Dev secrets (git-ignored)
compose.yaml            # Same compose file

# Prod (on NUC)
.env.prod               # Prod secrets (never in git)
compose.yaml            # Same compose file
```

**Deployment Flow:**
```bash
# On Mac: Build & push image
docker build -t budgeteer-backend ./backend
docker tag budgeteer-backend your-registry/budgeteer-backend:v1.0
docker push your-registry/budgeteer-backend:v1.0

# On NUC: Pull & run
docker pull your-registry/budgeteer-backend:v1.0
docker compose --env-file .env.prod up -d
```

**Pros:**
- ✅ Simple, you already know it
- ✅ Same tooling for dev and prod
- ✅ Easy to understand and debug
- ✅ Perfect for single-host deployment

**Cons:**
- ❌ Manual deployment (but can script it)
- ❌ No auto-scaling (you don't need it)

---

## 🐝 Option 2: Docker Swarm

**What it adds over Compose:**
- Built-in orchestration
- Rolling updates
- Service discovery
- Secrets management
- Multi-host support

**When you'd need it:**
- Running across multiple machines
- Need rolling updates with zero downtime
- Want built-in secrets management

**Reality check:** For a single NUC running a personal app, Swarm adds complexity without much benefit. Docker Compose is sufficient.

---

## ☸️ Option 3: Kubernetes

**What it is:** Container orchestration platform

**Features:**
- Auto-scaling
- Self-healing
- Rolling deployments
- Service mesh
- Ingress controllers

**Reality check:** **MASSIVE overkill** for your use case.

- You're a solo developer
- Single host (NUC)
- Personal app with one user
- K8s has a steep learning curve
- Adds significant operational complexity

**When K8s makes sense:**
- Multiple services needing to scale independently
- Team needs declarative infrastructure
- Running on cloud with managed K8s (EKS, GKE)
- Production apps with high availability requirements

**My recommendation:** Skip K8s entirely for this project. Learn it separately if interested, but don't use it for Budgeteer.

---

## 🖥️ Option 4: Direct VM/Bare Metal

**How it works:**
- Install Java, PostgreSQL directly on NUC
- Run `java -jar budgeteer.jar`
- Use systemd for service management

**Pros:**
- Maximum performance (no container overhead)
- Simple mental model

**Cons:**
- Environment differences between Mac and Linux
- Manual dependency management
- Harder to reproduce/reset

---

## 🏆 My Recommendation: Docker Compose

For your situation, **Docker Compose is the sweet spot**:

1. **You already use it** for dev (PostgreSQL)
2. **Same tooling** on Mac and NUC
3. **Environment separation** via `.env` files
4. **Simple to understand** and debug
5. **Easy to evolve** - can add services later

---

## 🔧 Practical Setup

### File Structure (add to project later)

```
budgeteer/
├── backend/
│   └── Dockerfile           # Build Spring Boot app
├── docker/
│   ├── compose.yaml         # Production compose (or use root)
│   └── .env.prod.example    # Template for prod secrets
├── compose.yaml             # Dev compose (existing)
└── .env                     # Dev secrets (existing)
```

### Environment Separation

**Option A: Different .env files**
```bash
# Dev (Mac)
docker compose up -d                    # Uses .env

# Prod (NUC)
docker compose --env-file .env.prod up -d
```

**Option B: Docker Compose profiles**
```yaml
# compose.yaml
services:
  backend:
    profiles: ["prod"]
    image: budgeteer/backend:latest
  
  backend-dev:
    profiles: ["dev"]
    build: ./backend
```

**Option C: Separate compose files**
```bash
# Dev
docker compose -f compose.yaml up -d

# Prod
docker compose -f compose.prod.yaml up -d
```

### Spring Boot Profiles

```properties
# application.properties (base config)
spring.profiles.active=${SPRING_PROFILE:dev}

# application-dev.properties
logging.level.root=DEBUG
spring.jpa.show-sql=true

# application-prod.properties
logging.level.root=INFO
spring.jpa.show-sql=false
```

Then in `.env.prod`:
```
SPRING_PROFILE=prod
```

---

## 🌿 Branch Strategy for Releases

### For Solo Dev (Your Case)

**Keep it simple - you don't need a `develop` branch:**

```
main ────●────●────●────●────●────────────────►
              │         │
              │         └── feature/webhooks
              └── feature/token-persistence
```

**Workflow:**
1. Work on `main` or short-lived feature branches
2. When ready to deploy: tag a release
3. Deploy the tagged version

```bash
# Tag a release
git tag v0.1.0
git push origin v0.1.0

# On NUC, deploy specific version
docker pull budgeteer/backend:v0.1.0
```

### When You'd Need `main` + `develop`

- Multiple developers
- Long-running features
- Formal release cycles
- Need to hotfix prod while developing

**You don't need this complexity yet.**

---

## 🚀 Deployment to NUC (Future)

### Option A: Manual SCP (Simple)

```bash
# Build on Mac
docker build -t budgeteer-backend ./backend
docker save budgeteer-backend | gzip > budgeteer.tar.gz
scp budgeteer.tar.gz nuc:/home/user/

# On NUC
docker load < budgeteer.tar.gz
docker compose up -d
```

### Option B: Private Registry (Better)

```bash
# Run a simple registry on NUC
docker run -d -p 5000:5000 registry:2

# Push from Mac
docker tag budgeteer-backend nuc:5000/budgeteer-backend
docker push nuc:5000/budgeteer-backend

# On NUC
docker pull localhost:5000/budgeteer-backend
```

### Option C: GitHub Container Registry (Easiest long-term)

When you have a GitHub remote:
- GitHub Actions builds and pushes images
- NUC pulls from ghcr.io
- Automated on every release tag

---

## 📝 Summary

| Question | Answer |
|----------|--------|
| Docker Swarm? | No - overkill for single host |
| Kubernetes? | No - massive overkill |
| Docker Compose? | **Yes - perfect fit** |
| Separate `develop` branch? | No - just use `main` + feature branches |
| How to separate dev/prod? | Different `.env` files + Spring profiles |
| VM vs Container? | Containers (consistency across Mac/Linux) |

---

## 🎯 Action Plan

1. **Now:** Continue developing on Mac with current setup
2. **Soon:** Create a `Dockerfile` for the backend
3. **When NUC arrives:** 
   - Install Docker on NUC
   - Copy compose.yaml and .env.prod
   - `docker compose up -d`
4. **Later:** Add GitHub Actions for automated builds/deploys

---

*This is a scratch note - refine as you learn more!*
