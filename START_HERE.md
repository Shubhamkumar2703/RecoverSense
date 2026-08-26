# RecoverSense — Start Here

## Before writing application code

### 1. Read
- `CLAUDE.md`
- `docs/PROJECT_CONTEXT.md`
- `docs/PRODUCT_SPEC.md`
- `docs/ARCHITECTURE.md`
- `docs/POLICY_SPEC.md`
- `docs/VERIFICATION_SPEC.md`
- `docs/SCOPE.md`

### 2. Verify local tools
- Git
- Java 21
- Maven
- Node.js/npm
- Docker (optional)

### 3. Initialize Git
```bash
git init
git add .
git commit -m "chore: bootstrap RecoverSense project"
```

### 4. Only then start implementation
First coding milestone:
- backend skeleton
- frontend skeleton
- PostgreSQL
- health endpoint
- baseline tests

### Important

Do not start by implementing the LLM.

Build the deterministic domain and safety boundaries first.
