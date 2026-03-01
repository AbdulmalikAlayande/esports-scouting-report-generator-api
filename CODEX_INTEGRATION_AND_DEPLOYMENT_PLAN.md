## Integration + Deployment Plan (React Frontend, Internship-Optimized)

### Summary
Use a **single deployed app** for internship delivery:
1. Keep one repo.
2. Keep one backend Worker (`/chat`) with DO + Workers AI.
3. Serve the built React frontend from the same Worker via static assets.
4. Do **not** force R2/D1/Pages in MVP unless required by rubric.
5. Add optional “separated app” topology only after internship acceptance.

This is the safest path for requirement clarity, easiest demo reliability, and lowest delivery risk.

---

## Final Architecture Decision

### Chosen structure (for internship)
- **Monorepo, single production app, same domain**
- Cloudflare services in MVP:
1. `Workers` (API + static hosting)
2. `Durable Objects` (session memory)
3. `Workers AI` (incident reasoning)

### Not chosen for MVP
- `Pages` (optional later)
- `R2` (optional when persistent raw file retention is required)
- `D1` (optional when org accounts/billing/audit metadata is required)

### Why this is better for your project requirement
1. Your current frontend already expects same-origin `POST /chat`.
2. Internship asks for clean architecture and working system, not max service sprawl.
3. Fewer moving parts means less integration risk and easier grading/demo.
4. DO + AI already satisfy core “stateful incident assistant” requirement.

---

## Integration Implementation Plan (Frontend already exists)

### Phase 0 — Requirement alignment
1. Update `CODEX.md` and `CLAUDE.md` to reflect React frontend reality.
2. Keep backend ownership boundaries unchanged:
1. `index.ts` routing
2. `chat.ts` request parsing
3. `session.ts` state lifecycle
4. `prompt.ts` prompt construction
5. `ai.ts` inference

### Phase 1 — Frontend/backend contract hardening
1. Preserve API contract:
1. `POST /chat`
2. Request: JSON or multipart
3. Response: `{ sessionId: string, response: string }`
2. In frontend `api.ts`, support:
1. default same-origin (`/chat`)
2. optional `VITE_API_BASE_URL` override for future split deployments
3. In `useChat.ts`, keep current session lifecycle:
1. first call without `sessionId`
2. follow-ups with returned `sessionId`

### Phase 2 — Same-origin compatibility checks
1. Validate multipart upload from React composer to worker parser.
2. Validate assistant structured section rendering from plain response text.
3. Validate error mapping:
1. `400/413/415/429/502/500` render meaningful UI messages.

---

## Deployment Plan (Single App)

### Phase 3 — Worker static asset serving
1. Build frontend output into `frontend/dist`.
2. Configure Worker static assets in `wrangler.jsonc` (`assets.directory` to `frontend/dist`).
3. Worker routing behavior:
1. `POST /chat` -> existing API flow
2. non-API `GET` -> serve frontend asset/index fallback

### Phase 4 — Environment strategy
1. Environments: `dev`, `staging`, `prod`.
2. Per-environment bindings:
1. `AI`
2. `SESSIONS` DO namespace
3. optional vars for model/limits.
3. Run `npx wrangler types` after any binding changes.

### Phase 5 — CI/CD
1. Pipeline steps:
1. install
2. backend tests/typecheck
3. frontend build
4. worker deploy
2. Post-deploy smoke:
1. open app URL
2. send first message
3. send follow-up with same session
4. test one file upload

---

## “Should we separate this app?” Decision

### For internship submission
- **No, do not separate into Pages+Worker now.**
- Keep one deployed app/domain for predictability and grading simplicity.

### When to separate later
- Separate frontend/backend deployment only if:
1. independent release cadence is needed
2. team ownership splits
3. stricter edge API isolation is needed
4. you add multi-surface clients (web + integrations)

### If later separated
- Use:
1. `Pages` for React frontend
2. `Pages Functions` gateway `/api/chat`
3. Service binding to backend Worker
- This avoids browser CORS complexity.

---

## Communication Flow After Deployment

### MVP (chosen)
1. Browser loads app from Worker-hosted static assets.
2. Browser sends `POST /chat` to same origin.
3. Worker routes to DO by `sessionId`.
4. DO builds prompt + calls Workers AI.
5. DO returns response.
6. Worker responds to browser with `{ sessionId, response }`.

---

## Revenue/“What pays more” plan (without hurting internship delivery)

### Immediate (internship)
- Focus on reliability + correctness, not billing stack.

### Next (commercialization)
1. Add `D1` for:
1. organizations
2. users
3. projects
4. usage ledger
2. Add `R2` for:
1. retained raw upload artifacts
2. compliance/audit retention tiers
3. Monetize with:
1. base subscription per org
2. seat count
3. usage overage (analysis volume/file volume)

---

## Public API/Interface changes (explicit)

### Required now
- No breaking API change: keep `POST /chat` contract.

### Optional now (recommended)
1. Frontend config:
1. add `VITE_API_BASE_URL` support (fallback to same-origin)
2. Keep request/response typings unchanged.

### Deferred
- Add usage metadata in response only when billing model starts.

---

## Test Cases and Acceptance Criteria

### Core API
1. First message without `sessionId` returns a generated `sessionId`.
2. Second message with same `sessionId` reuses history.
3. Multipart upload path returns successful response.
4. Oversized payload returns `413`.
5. Abuse limit returns `429`.
6. AI transient failure maps to controlled error response.

### Frontend integration
1. Message appears optimistic in feed.
2. Loading placeholder updates to assistant response.
3. Session badge updates after first response.
4. Error responses render readable UI error message.

### Deployment
1. App root serves React page.
2. `/chat` remains functional after static asset enablement.
3. Staging and prod both pass same smoke checklist.

---

## Assumptions and Defaults
1. React frontend is now authoritative (docs will be updated).
2. Internship does not require using every Cloudflare product simultaneously.
3. MVP priority is stable end-to-end incident analysis flow.
4. Same-origin deployment is preferred over split deployments for submission.

---

## Cloudflare docs used for this plan
1. Workers limits: `https://developers.cloudflare.com/workers/platform/limits/`
2. Workers pricing: `https://developers.cloudflare.com/workers/platform/pricing/`
3. Pages Functions pricing: `https://developers.cloudflare.com/pages/functions/pricing/`
4. Pages bindings/service bindings: `https://developers.cloudflare.com/pages/functions/bindings/`
5. Durable Objects pricing: `https://developers.cloudflare.com/durable-objects/platform/pricing/`
6. Workers AI pricing: `https://developers.cloudflare.com/workers-ai/platform/pricing/`
7. Workers AI limits: `https://developers.cloudflare.com/workers-ai/platform/limits/`
8. D1 pricing: `https://developers.cloudflare.com/d1/platform/pricing/`
9. R2 pricing: `https://developers.cloudflare.com/r2/pricing/`
10. Workers static assets: `https://developers.cloudflare.com/workers/static-assets/`
