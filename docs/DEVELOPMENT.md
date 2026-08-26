# RecoverSense — Development Workflow

## Before coding

Read:
1. CLAUDE.md
2. PROJECT_CONTEXT.md
3. PRODUCT_SPEC.md
4. ARCHITECTURE.md
5. POLICY_SPEC.md
6. VERIFICATION_SPEC.md
7. SCOPE.md
8. PROJECT_STATE.md

## Development loop

```text
Understand
→ Design smallest change
→ Implement
→ Test
→ Verify
→ Update state/docs
→ Commit
```

## Commit style

Examples:
- `chore: bootstrap backend`
- `feat: add recovery case domain`
- `feat: add diagnosis contract`
- `feat: add deterministic policy engine`
- `test: cover duplicate recovery policy`
- `feat: add verification service`
- `feat: add audit events`

Keep commits focused.
