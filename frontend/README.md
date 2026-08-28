# RecoverSense Frontend

React + TypeScript dashboard.

Primary views:
- Overview
- At-risk payments
- Recovery cases
- Audit trail
- Metrics
- Policy rules
- Integrations
- Settings

The uploaded HTML mockup is a visual reference stored under `reference/`.

## Current implementation (M1.14)

Only the **Overview** dashboard is wired up, reading from the read-only
`GET /api/dashboard/metrics` and `GET /api/dashboard/cases/{id}/audit`
backend endpoints. The other sidebar items are placeholders for a later
milestone.

Run with `npm install && npm run dev` (expects the backend on
`http://localhost:8081`, overridable via `VITE_API_BASE_URL`).
