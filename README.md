# AuraSentry – Cloud Governance Tool

A Java 25 / Spring Boot 4 backend for automated Azure cloud governance: resource scanning, optimisation analysis, and AI-assisted remediation.

---

## Tech Stack

| Layer       | Technology                                                |
| ----------- | --------------------------------------------------------- |
| Backend     | Java 25, Spring Boot 4, Spring MVC                        |
| Cloud       | Azure SDK 2.48, Spring Cloud Azure 7.1                    |
| Persistence | PostgreSQL 17, Spring Data JPA, Hibernate                 |
| AI          | Google Gemini via RestClient                              |
| IaC         | Ansible 2.x, Docker Compose                               |
| Pattern     | Virtual Threads, Sealed Interfaces, Record Deconstruction |

---

## Quick Start (local)

### 1. Prerequisites

- JDK 25+
- Docker Desktop

### 2. Start the database

```bash
docker compose up -d
```

PostgreSQL 17 will then be available at `localhost:5432` (database: `aurasentry`).

### 3. Set environment variables

Copy the template and fill in your values:

```bash
cp .env.example .env
# Edit .env – never commit it to Git!
```

Required variables:

```
AZURE_TENANT_ID=...
AZURE_CLIENT_ID=...
AZURE_CLIENT_SECRET=...
AZURE_SUBSCRIPTION_ID=...
GEMINI_API_KEY=...
```

> The app also starts **without** Azure credentials – Azure beans are disabled via `@ConditionalOnProperty`.

### 4. Start the application

```bash
./mvnw spring-boot:run
```

The API is available at `http://localhost:8080`.

---

## API Endpoints

| Method | Path                             | Description                             |
| ------ | -------------------------------- | --------------------------------------- |
| GET    | `/api/v1/optimization/analyze`   | Scan and analyse Azure resources        |
| GET    | `/api/v1/optimization/history`   | Last 10 scan results from DB            |
| POST   | `/api/v1/remediation/advise`     | AI recommendation for a single resource |
| POST   | `/api/v1/remediation/advise-all` | AI recommendations for all findings     |
| GET    | `/actuator/health`               | Health check                            |

---

## Infrastructure as Code (Ansible)

The `ansible/` structure demonstrates how deployment to a remote Linux server (e.g. Azure VM) can be automated.

```
ansible/
├── group_vars/
│   └── all.yml          # Variables (no real secrets!)
├── templates/
│   └── env.j2           # Jinja2 template for .env
├── setup_env.yml        # Playbook: generate .env locally
└── deploy-docker.yml    # Playbook: deploy to remote server
```

### Playbook 1 – Generate local .env

Renders `templates/env.j2` with variables from `group_vars/all.yml` into a `.env` file:

```bash
ansible-playbook ansible/setup_env.yml
```

With encrypted vault (recommended for CI/CD):

```bash
ansible-playbook ansible/setup_env.yml --ask-vault-pass
```

### Playbook 2 – Remote Deployment on Azure VM

Full deployment (install Docker, start containers, health check):

```bash
# One-time: install community collection
ansible-galaxy collection install community.docker

# Run deployment
ansible-playbook ansible/deploy-docker.yml \
  -i "YOUR_AZURE_VM_IP," \
  --private-key ~/.ssh/aurasentry_key \
  --ask-vault-pass
```

The playbook:

1. Installs Docker on the target server (Debian/Ubuntu)
2. Creates a dedicated system user `aurasentry`
3. Transfers `docker-compose.yml` and generates `.env` from the vault
4. Starts all containers via `community.docker.docker_compose_v2`
5. Runs an HTTP health check against `/actuator/health`

### Secrets management with ansible-vault

```bash
# Create and encrypt the secrets file
cp ansible/group_vars/all.yml ansible/group_vars/secrets.yml
# Fill in real values in secrets.yml, then encrypt:
ansible-vault encrypt ansible/group_vars/secrets.yml

# Display file decrypted (without saving):
ansible-vault view ansible/group_vars/secrets.yml
```

> `ansible/group_vars/secrets.yml` is listed in `.gitignore` and will never be committed to Git.

---

## Architecture Decisions

**Why Sealed Interfaces + Pattern Matching?**
`ResourceCategory` enables exhaustive switch expressions – the compiler enforces that new resource types are handled in all rules. Eliminates `instanceof` cascades.

**Why Virtual Threads?**
Azure SDK and Gemini API are I/O-bound. `Executors.newVirtualThreadPerTaskExecutor()` allows hundreds of parallel API calls without classic thread pool tuning.

**Why direct RestClient instead of Spring AI?**
Spring AI 1.0.0 was not available in Maven Central at development time. The direct approach also demonstrates how HTTP clients can be implemented without a framework dependency.

**Why `@ConditionalOnProperty` for Azure beans?**
Enables local development and testing without Azure credentials. The application starts fully – all non-Azure features (persistence, AI) are immediately usable.
