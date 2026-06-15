# Changelog

Engineering changelog for the hotel-sim POC. Contract freeze/version history lives in the
`WAVE0_0X` artifacts' own changelog sections — this file tracks implementation work that is
not itself a contract change.

## Stage 2 · Feature 2 · Part 1C-a — `payments-sim` real endpoints self-emit webhooks

Closes the money loop on the **real, always-on** request endpoints. Previously only the
`@Profile("test")` `TestTriggerController` (`/v1/test/...`) fired settlement webhooks, so a
demo against the production wiring left the loop open (core-api requested, the sim never
called back). Now the real `POST /v1/payments/{ref}/captures`, `/cancellations`, and
`/v1/payments/{ref}/refunds` settle and emit their webhook asynchronously after commit, with
no `/v1/test` step.

- **New** `PspWebhookEmitter` (non-transactional) — sequences the existing
  `PspTriggerService.prepare*` settlement (tx2) then `WebhookDispatcher.dispatch(..., sync=false)`
  after commit. No duplicated state-flip or envelope logic; emitted `idempotencyKey`
  (`pspRef:EVENTCODE:seq`) stays byte-identical to the test path, so core-api's WHK-005
  inbox dedupe is unchanged.
- `PaymentController` / `RefundController` now delegate the post-commit emit to the new bean
  (they remain the non-transactional sequencer; the HTTP call never sits inside a DB tx —
  PSP-006 / GAP-2 discipline). `202` returns immediately; delivery is fire-and-forget,
  single-attempt, no-retry (PSP-007/008).
- **Async-only on the real path.** The inline `?sync=true` seam stays exclusive to
  `@Profile("test")` `TestTriggerController` (WHK-015 stays unreachable in prod).
- **New config** `psp-sim.settlement-delay-ms` (env `PSP_SIM_SETTLEMENT_DELAY_MS`,
  default `0`) — delays the webhook on the executor thread only (never the request thread,
  never the sync seam). A non-zero value makes the out-of-band `AUTHORISED → CAPTURED` flip
  visible in a demo. Wired in `application.yml` and `docker-compose.yml`.
- **AUTHORISATION stays test-only** — it represents the customer paying (pay-web deferred,
  RX-001), so it has no real trigger and remains the `/v1/test` customer-checkout stand-in.
  IMMEDIATE capture rides the AUTHORISATION→CAPTURE chain, so the real `/captures` self-emit
  only ever runs for MANUAL.
- **Tests** — new `RealPathAutoEmitTest` proves a real `/captures` self-emits a signed
  CAPTURE webhook (deterministic `idempotencyKey`) and settles the row with **no `/v1/test`
  call and no `test` profile**. `AuthoriseTriggerSyncTest` now drives capture via the real
  async path (polls the receiver). `Capture/Cancellation/RefundApiTest` happy-path
  assertions updated to the now-synchronous settled state.
- **Postman** collection folders 04/05 — removed the three `/v1/test/...` settle steps,
  relabelled the authorise step as the customer-checkout stand-in, and added poll-until-state
  steps where settlement now lands out-of-band.

No frozen `WAVE0_0X` contract was modified.
