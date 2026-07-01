#!/usr/bin/env node
// ============================================================================
// hotel-sim — demo data creator   (repo: github.com/surajj2223/hotel-sim)
// ----------------------------------------------------------------------------
// Populates a running stack with presentable, correct-by-construction data for
// ops-web: ~12 customers, many separate visits across ~90 days, a few
// deliberately cross-vertical folios, and the full money lifecycle underneath.
//
// Calls ONLY frozen HTTP contracts. Never writes to the DB — all domain logic
// (pricing, capture rules, ledger postings) stays in core-api.
//
// ── THE ONE INVARIANT THAT MATTERS (learned the hard way) ───────────────────
// Revenue is posted to the ledger ASYNCHRONOUSLY by a @Scheduled(fixedDelay=5s)
// outbox processor — NOT synchronously by the capture webhook. `?sync=true` only
// delivers the webhook inline; the webhook merely ENQUEUES a PENDING outbox event.
//
// LedgerService.postCapture allocates the captured amount across the folio's
// **ACTIVE** booking lines. If a line is completed (ACTIVE → COMPLETED) BEFORE the
// outbox processor runs, allocation finds no ACTIVE line, throws, and the outbox
// row is marked FAILED — no posting is written, transaction rolls back. That is
// the "no payment lines + failed outbox rows" failure mode.
//
// THEREFORE: never completeLine/completeFolio until the line's `revenuePosted`
// (derived from real ledger_posting rows, visible on GET /bookings/{id}) reflects
// its payment. This script polls `revenuePosted` after every revenue-posting
// payment and only then completes. There is NO synchronous capture in this system.
//
// ── WHAT MUST BE RUNNING ────────────────────────────────────────────────────
//   core-api :8080, payments-sim :8081 UNDER THE `test` PROFILE, products seeded.
//     docker compose -f docker-compose.yml -f docker-compose.smoke.yml up -d
//     psql ... < docs/runnable-postman-collection/seed-products.sql   (once)
//     psql ... < docs/seed-data/seed-products-extra.sql               (optional)
//
// ── MONEY LOOP (verified against source) ────────────────────────────────────
//   AUTHORISE:  POST {sim}/v1/test/payment-links/{linkId}/authorise?sync=true
//               → webhook inline; IMMEDIATE (SPA/FNB) also fires CAPTURE inline.
//               Either way the REVENUE posting is enqueued, not yet written.
//   CAPTURE (MANUAL room): POST {core}/payments/{id}/capture (X-Human-Auth), 202.
//   REFUND / CANCEL:       POST {core}/payments/{id}/refunds|cancel (X-Human-Auth).
//   All settlement lands on the outbox → poll the core read model, never assume timing.
//
// ── TIME ────────────────────────────────────────────────────────────────────
//   Stay dates (startsAt/endsAt) are request fields ⇒ spread across ~90 days for
//   real operational history. Ledger posting time is server-"now"; a revenue
//   TREND by date is out of scope (see docs/system-design/seedable-posting-time.md).
//   Revenue SPLIT by vertical + unpaid worklist are fully real.
// ============================================================================

const CORE = (process.env.CORE_URL ?? 'http://localhost:8080').replace(/\/$/, '');
const SIM  = (process.env.SIM_URL  ?? 'http://localhost:8081').replace(/\/$/, '');
const HUMAN_AUTH = process.env.HUMAN_AUTH ?? 'seed-script-operator';
// Outbox processor is @Scheduled(fixedDelay=5000). Worst-case wait for a posting
// is one tick + processing, so allow generously.
const POLL_TIMEOUT_MS  = Number(process.env.POLL_TIMEOUT_MS  ?? 15000);
const POLL_INTERVAL_MS = Number(process.env.POLL_INTERVAL_MS ?? 250);

const P = {
  ROOM_DELUXE: '11111111-1111-1111-1111-111111111111', // ROOM £180/night
  ROOM_TWIN:   '22222222-2222-2222-2222-222222222222', // ROOM £95/night
  SPA_MASSAGE: '33333333-3333-3333-3333-333333333333', // SPA  £80
  SPA_FACIAL:  '44444444-4444-4444-4444-444444444444', // SPA  £65
  FNB_DINNER:  '55555555-5555-5555-5555-555555555555', // FNB  £45/cover
  // additive (seed-products-extra.sql):
  ROOM_SUITE:  '66666666-6666-6666-6666-666666666666', // ROOM £320/night
  SPA_SAUNA:   '77777777-7777-7777-7777-777777777777', // SPA  £40
  FNB_BRUNCH:  '88888888-8888-8888-8888-888888888888', // FNB  £35/cover
  FNB_LUNCH:   '99999999-9999-9999-9999-999999999999', // FNB  £28/cover
};

// ── tiny HTTP client (native fetch, Node 18+) ───────────────────────────────
let CALLS = 0;
async function http(method, base, path, { body, humanAuth } = {}) {
  CALLS++;
  const headers = { 'Content-Type': 'application/json' };
  if (humanAuth) headers['X-Human-Auth'] = HUMAN_AUTH;
  const res = await fetch(base + path, {
    method, headers, body: body == null ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  let json = null; try { json = text ? JSON.parse(text) : null; } catch { /* non-JSON */ }
  if (!res.ok) throw new Error(`${method} ${path} -> ${res.status} ${json ? JSON.stringify(json) : text}`);
  return json;
}
const core = (m, p, o) => http(m, CORE, p, o);
const sim  = (m, p, o) => http(m, SIM,  p, o);
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

// ── bounded poll: fail loud on timeout ──────────────────────────────────────
async function pollUntil(label, fetchState, predicate) {
  const deadline = Date.now() + POLL_TIMEOUT_MS;
  let last;
  while (Date.now() < deadline) {
    last = await fetchState();
    if (predicate(last)) return last;
    await sleep(POLL_INTERVAL_MS);
  }
  throw new Error(`POLL TIMEOUT (${POLL_TIMEOUT_MS}ms) waiting for ${label}`);
}

// ── time helpers: stay windows across the last ~90 days ─────────────────────
const isoZ = (d) => d.toISOString().replace(/\.\d{3}Z$/, 'Z');
function roomWindow(checkInDaysAgo, nights) {
  const inD = new Date(); inD.setUTCDate(inD.getUTCDate() - checkInDaysAgo); inD.setUTCHours(15, 0, 0, 0);
  const outD = new Date(inD); outD.setUTCDate(outD.getUTCDate() + nights); outD.setUTCHours(11, 0, 0, 0);
  return { startsAt: isoZ(inD), endsAt: isoZ(outD), nights };
}
function slotWindow(daysAgo, hour, durationMin) {
  const s = new Date(); s.setUTCDate(s.getUTCDate() - Math.max(0, daysAgo)); s.setUTCHours(hour, 0, 0, 0);
  const e = new Date(s); e.setUTCMinutes(e.getUTCMinutes() + durationMin);
  return { startsAt: isoZ(s), endsAt: isoZ(e) };
}

// ── contract-shaped operations (paths/fields verified against WAVE0_02) ──────
const createCustomer = (fullName, email, phone) =>
  core('POST', '/customers', { body: { fullName, email, phone } });
const setPreference = (customerId, key, value) =>
  core('PUT', `/customers/${customerId}/preferences/${encodeURIComponent(key)}`, { body: { value } });
const openFolio = (customerId) =>
  core('POST', '/bookings', { body: { customerId, currency: 'GBP' } });
const getFolio = (bookingId) => core('GET', `/bookings/${bookingId}`);
const getPayment = (paymentId) => core('GET', `/payments/${paymentId}`);

async function addLine(bookingId, productId, startsAt, endsAt, quantity) {
  const folio = await core('POST', `/bookings/${bookingId}/lines`,
    { body: { productId, startsAt, endsAt, quantity } });      // returns FolioResponse
  return { folio, line: folio.lines[folio.lines.length - 1] };  // new line is last
}
const createPaymentLink = (bookingId, amount, captureMode, lineCoverage) =>
  core('POST', `/bookings/${bookingId}/payments`,
    { body: {
        amount, currency: 'GBP',
        ...(captureMode ? { captureMode } : {}),
        ...(lineCoverage ? { lineCoverage } : {}),
      }, humanAuth: true });

// Per-vertical default capture mode (verified: ROOM MANUAL, SPA/FNB IMMEDIATE).
// MUST be passed explicitly on a scoped payment: resolveDefaultCaptureMode keys off
// the folio's FIRST active line, not the covered line — so a dinner-scoped payment
// on a room-first folio would otherwise inherit the room's MANUAL mode and never
// auto-capture. Explicit override per covered line is mandatory.
const CAPTURE_MODE = { ROOM: 'MANUAL', SPA: 'IMMEDIATE', FNB: 'IMMEDIATE' };

// Pay for exactly ONE line via WHK-016 scoped coverage. Coverage amount == lineAmount
// (must sum to `amount`, else 400). Capture mode is set explicitly from the line's
// vertical so it does not inherit the folio's first-line default.
async function payLineScoped(bookingId, line, { amount } = {}) {
  const amt = amount ?? line.lineAmount;
  const mode = CAPTURE_MODE[line.vertical];
  return createPaymentLink(bookingId, amt, mode,
    [{ bookingLineId: line.id, amount: amt }]);
}

// AUTHORISE via sim (sync ⇒ webhook inline), then read core's stamped state.
async function authorise(payment, overrideAmount) {
  await sim('POST', `/v1/test/payment-links/${payment.paymentLinkId}/authorise?sync=true`,
    { body: overrideAmount ? { amount: overrideAmount } : undefined });
  return getPayment(payment.id);  // authoritative: pspReference now stamped by core
}
// CAPTURE via core (202). Ledger posts async via outbox. amountCaptured flips first;
// the revenue posting itself is confirmed separately by waitForRevenue().
async function capture(paymentId, amount) {
  await core('POST', `/payments/${paymentId}/capture`,
    { body: amount ? { amount } : undefined, humanAuth: true });
  const want = amount ?? null;
  return pollUntil(`amountCaptured on payment ${paymentId}`, () => getPayment(paymentId),
    p => want == null ? p.amountCaptured > 0 : p.amountCaptured >= want);
}
async function cancelAuth(paymentId) {
  const before = await getPayment(paymentId);
  await core('POST', `/payments/${paymentId}/cancel`, { humanAuth: true });
  return pollUntil(`cancellation of payment ${paymentId}`, () => getPayment(paymentId),
    p => p.status !== before.status);
}
async function refund(paymentId, amount, reason) {
  const before = await getPayment(paymentId);
  await core('POST', `/payments/${paymentId}/refunds`, { body: { amount, reason }, humanAuth: true });
  return pollUntil(`amountRefunded on payment ${paymentId}`, () => getPayment(paymentId),
    p => p.amountRefunded >= (before.amountRefunded ?? 0) + amount);
}
const completeLine = (bookingId, lineId) =>
  core('POST', `/bookings/${bookingId}/lines/${lineId}/complete`);
const completeFolio = (bookingId) =>
  core('POST', `/bookings/${bookingId}/complete`, { humanAuth: true });

// ── THE FIX: wait for the outbox to drain before completing lines ────────────
// Revenue posts async. A line's `revenuePosted` on GET /bookings/{id} is derived
// from real ledger_posting rows, so it is the authoritative "posting landed" signal.
// Poll until every line in `lineIds` shows revenuePosted != 0 (REVENUE, or net
// after a refund). Completing before this races the outbox and marks it FAILED.
async function waitForRevenue(bookingId, lineIds) {
  const want = new Set(lineIds);
  return pollUntil(`ledger postings for lines ${[...want].map(s => s.slice(0, 8)).join(',')}`,
    () => getFolio(bookingId),
    folio => folio.lines
      .filter(l => want.has(l.id))
      .every(l => l.revenuePosted !== 0));
}

// ── product availability probe (only use products that exist) ────────────────
const AVAILABLE = new Set();
async function probeProducts() {
  const probes = [
    ['ROOM', roomWindow(30, 1)],
    ['SPA',  slotWindow(30, 14, 60)],
    ['FNB',  slotWindow(30, 20, 120)],
  ];
  for (const [vertical, w] of probes) {
    let results = [];
    try {
      const qs = `vertical=${vertical}&startsAt=${encodeURIComponent(w.startsAt)}` +
                 `&endsAt=${encodeURIComponent(w.endsAt)}&quantity=1`;
      results = (await core('GET', `/availability?${qs}`)) ?? [];
    } catch { /* vertical may return nothing */ }
    for (const r of results) if (r.productId) AVAILABLE.add(r.productId);
  }
}
const has = (id) => AVAILABLE.has(id);
const pick = (...ids) => ids.find(has) ?? null;

// ── customer roster (12) with cross-vertical preferences ────────────────────
const ROSTER = [
  { name: 'John Patel',      email: 'john.patel@example.com',    phone: '+44 7700 900001', prefs: { floor: 'high', quiet: 'true' } },
  { name: 'Li Chen',         email: 'li.chen@example.com',       phone: '+44 7700 900002', prefs: { dietary: 'vegetarian', spa_therapist: 'female' } },
  { name: 'Amara Okafor',    email: 'amara.okafor@example.com',  phone: '+44 7700 900003', prefs: { floor: 'high', dietary: 'halal' } },
  { name: 'Diego Fernandez', email: 'diego.f@example.com',       phone: '+44 7700 900004', prefs: { bed: 'king' } },
  { name: 'Sofia Rossi',     email: 'sofia.rossi@example.com',   phone: '+44 7700 900005', prefs: { spa_therapist: 'female', floor: 'low' } },
  { name: 'Tom Whitmore',    email: 'tom.whitmore@example.com',  phone: '+44 7700 900006', prefs: { dietary: 'vegan' } },
  { name: 'Priya Nair',      email: 'priya.nair@example.com',    phone: '+44 7700 900007', prefs: { floor: 'high', quiet: 'true' } },
  { name: 'Hassan Ali',      email: 'hassan.ali@example.com',    phone: '+44 7700 900008', prefs: { dietary: 'halal', bed: 'twin' } },
  { name: 'Greta Bauer',     email: 'greta.bauer@example.com',   phone: '+44 7700 900009', prefs: { spa_therapist: 'any' } },
  { name: 'Marcus Lee',      email: 'marcus.lee@example.com',    phone: '+44 7700 900010', prefs: { floor: 'high' } },
  { name: 'Elena Petrova',   email: 'elena.petrova@example.com', phone: '+44 7700 900011', prefs: { dietary: 'gluten-free' } },
  { name: 'Walk-in Guest',   email: null,                         phone: null,              prefs: {} },
];

// ── scenario archetypes ─────────────────────────────────────────────────────
// Pattern for any COMPLETED folio: add lines → pay → waitForRevenue → complete.
const SUMMARY = [];
const record = (label, detail) => SUMMARY.push({ label, detail });

// Room selection: favour Twin (room_count=5) and Suite (2) over Deluxe (2) to reduce
// contention. quantity is ALWAYS 1 (one room); nights come from the date window.
const ROOM_PREF = [P.ROOM_TWIN, P.ROOM_SUITE, P.ROOM_DELUXE];

// 1. Clean cross-vertical stay: room(MANUAL, captured)+spa+dinner, all paid, COMPLETED.
//    Each line paid by its OWN scoped payment ⇒ correct per-line allocation AND per-line
//    capture mode (room MANUAL held then captured at checkout; spa/dinner IMMEDIATE).
async function cleanCrossVerticalStay(cust, daysAgo) {
  const roomId = pick(...ROOM_PREF); if (!roomId) return;
  const folioId = (await openFolio(cust.id)).id;
  const w = roomWindow(daysAgo, 3);
  const { line: roomLine } = await addLine(folioId, roomId, w.startsAt, w.endsAt, 1); // 1 room
  let roomPay = await payLineScoped(folioId, roomLine);                 // ROOM ⇒ MANUAL
  roomPay = await authorise(roomPay);                                    // held, not captured

  const lineIds = [roomLine.id];
  const spaId = pick(P.SPA_MASSAGE, P.SPA_FACIAL, P.SPA_SAUNA);
  if (spaId) {
    const s = slotWindow(daysAgo - 1, 14, 60);
    const { line } = await addLine(folioId, spaId, s.startsAt, s.endsAt, 1); lineIds.push(line.id);
    await authorise(await payLineScoped(folioId, line));               // SPA ⇒ IMMEDIATE
  }
  const fnbId = pick(P.FNB_DINNER, P.FNB_BRUNCH, P.FNB_LUNCH);
  if (fnbId) {
    const d = slotWindow(daysAgo - 1, 20, 120);
    const { line } = await addLine(folioId, fnbId, d.startsAt, d.endsAt, 2); lineIds.push(line.id);
    await authorise(await payLineScoped(folioId, line));               // FNB ⇒ IMMEDIATE
  }
  await capture(roomPay.id);                          // checkout: capture the room hold
  await waitForRevenue(folioId, lineIds);             // wait for outbox to post ALL revenue
  for (const id of lineIds) await completeLine(folioId, id);
  await completeFolio(folioId);
  record('Clean cross-vertical stay (COMPLETED, balance 0)', `${cust.name} · ${w.nights}n · folio ${folioId.slice(0, 8)}`);
}

// 2. In-house guest: room MANUAL authorised, NOT captured. Folio OPEN, owes, live hold.
async function inHouseHeld(cust, daysAgo) {
  const roomId = pick(...ROOM_PREF); if (!roomId) return;
  const folioId = (await openFolio(cust.id)).id;
  const w = roomWindow(daysAgo, 2);
  const { line } = await addLine(folioId, roomId, w.startsAt, w.endsAt, 1);
  await authorise(await payLineScoped(folioId, line));                 // MANUAL hold, no capture
  record('In-house guest (held, not captured — owes, live auth hold)', `${cust.name} · folio ${folioId.slice(0, 8)}`);
}

// 3. Partial payment: total billed, ~60% paid IMMEDIATE. Folio OPEN, balance > 0.
//    Single line, so a folio-wide partial payment is fine (fill-by-order can't misallocate).
async function partialPayment(cust, daysAgo) {
  const roomId = pick(...ROOM_PREF); if (!roomId) return;
  const folioId = (await openFolio(cust.id)).id;
  const w = roomWindow(daysAgo, 4);
  const { line } = await addLine(folioId, roomId, w.startsAt, w.endsAt, 1);
  // Pay 60% of the line, scoped to it, IMMEDIATE so it captures now.
  const part = Math.round(line.lineAmount * 0.6);
  await authorise(await payLineScoped(folioId, line, { amount: part }));
  record('Partial payment (balance > 0)', `${cust.name} · ~60% paid · folio ${folioId.slice(0, 8)}`);
}

// 4. Partial capture: authorise full, capture 90%, release rest. Revenue < authorised.
//    Left OPEN (owes the released 10%) — demonstrates capture-in without forcing completion.
async function partialCapture(cust, daysAgo) {
  const roomId = pick(...ROOM_PREF); if (!roomId) return;
  const folioId = (await openFolio(cust.id)).id;
  const w = roomWindow(daysAgo, 3);
  const { line } = await addLine(folioId, roomId, w.startsAt, w.endsAt, 1);
  let pay = await payLineScoped(folioId, line);                        // MANUAL hold, full amount
  pay = await authorise(pay);
  await capture(pay.id, Math.round(line.lineAmount * 0.9));             // capture 90%
  await waitForRevenue(folioId, [line.id]);                            // confirm posting landed
  record('Partial capture (captured < authorised)', `${cust.name} · folio ${folioId.slice(0, 8)}`);
}

// 5. Goodwill partial refund: pay full IMMEDIATE, refund 25%. Net < gross. COMPLETED.
async function goodwillRefund(cust, daysAgo) {
  const fnbId = pick(P.FNB_DINNER, P.FNB_BRUNCH, P.FNB_LUNCH); if (!fnbId) return;
  const folioId = (await openFolio(cust.id)).id;
  const d = slotWindow(daysAgo, 20, 120);
  const { line } = await addLine(folioId, fnbId, d.startsAt, d.endsAt, 4);
  let pay = await payLineScoped(folioId, line);                        // FNB ⇒ IMMEDIATE
  pay = await authorise(pay);
  await waitForRevenue(folioId, [line.id]);                            // revenue must post first
  await refund(pay.id, Math.round(line.lineAmount * 0.25), 'Goodwill — slow service');
  await completeLine(folioId, line.id);
  await completeFolio(folioId);
  record('Goodwill partial refund (net < gross)', `${cust.name} · -25% · folio ${folioId.slice(0, 8)}`);
}

// 6. Full refund (cancelled stay after payment): net 0, reversal postings. Left OPEN.
async function fullRefundCancelledStay(cust, daysAgo) {
  const spaId = pick(P.SPA_MASSAGE, P.SPA_FACIAL, P.SPA_SAUNA); if (!spaId) return;
  const folioId = (await openFolio(cust.id)).id;
  const s = slotWindow(daysAgo, 15, 60);
  const { line } = await addLine(folioId, spaId, s.startsAt, s.endsAt, 1);
  let pay = await payLineScoped(folioId, line);                        // SPA ⇒ IMMEDIATE
  pay = await authorise(pay);
  await waitForRevenue(folioId, [line.id]);
  await refund(pay.id, line.lineAmount, 'Customer cancelled — full refund');
  record('Full refund after payment (net 0, reversal)', `${cust.name} · folio ${folioId.slice(0, 8)}`);
}

// 7. Cancelled authorisation: authorise then cancel before capture. No ledger effect.
async function cancelledAuth(cust, daysAgo) {
  const roomId = pick(...ROOM_PREF); if (!roomId) return;
  const folioId = (await openFolio(cust.id)).id;
  const w = roomWindow(daysAgo, 2);
  const { line } = await addLine(folioId, roomId, w.startsAt, w.endsAt, 1);
  let pay = await payLineScoped(folioId, line);                        // MANUAL
  pay = await authorise(pay);
  await cancelAuth(pay.id);                                             // nothing posted, nothing to wait for
  record('Cancelled authorisation (auth ≠ revenue)', `${cust.name} · folio ${folioId.slice(0, 8)}`);
}

// 8. Walk-in dinner: single FNB line, IMMEDIATE paid, COMPLETED.
async function walkInDinner(cust, daysAgo) {
  const fnbId = pick(P.FNB_DINNER, P.FNB_LUNCH, P.FNB_BRUNCH); if (!fnbId) return;
  const folioId = (await openFolio(cust.id)).id;
  const d = slotWindow(daysAgo, 20, 120);
  const { line } = await addLine(folioId, fnbId, d.startsAt, d.endsAt, 2);
  await authorise(await payLineScoped(folioId, line));                 // FNB ⇒ IMMEDIATE
  await waitForRevenue(folioId, [line.id]);                            // wait, then complete
  await completeLine(folioId, line.id);
  await completeFolio(folioId);
  record('Walk-in dinner (single-vertical, COMPLETED)', `${cust.name} · folio ${folioId.slice(0, 8)}`);
}

// 9. Spa day: two spa lines, each paid by its OWN scoped payment ⇒ correct per-line
//    allocation (this is the fix for the earlier fill-by-order misallocation). COMPLETED.
//    Slots are non-overlapping (11:00 and 14:00) to avoid consuming the same concurrent slot.
async function spaDay(cust, daysAgo) {
  const a = pick(P.SPA_MASSAGE, P.SPA_FACIAL);
  const b = pick(P.SPA_FACIAL, P.SPA_SAUNA, P.SPA_MASSAGE);
  if (!a) return;
  const folioId = (await openFolio(cust.id)).id;
  const ids = [];
  const s1 = slotWindow(daysAgo, 11, 60);
  const { line: l1 } = await addLine(folioId, a, s1.startsAt, s1.endsAt, 1); ids.push(l1.id);
  await authorise(await payLineScoped(folioId, l1));                   // scoped to l1
  if (b && b !== a) {
    const s2 = slotWindow(daysAgo, 14, 45);
    const { line: l2 } = await addLine(folioId, b, s2.startsAt, s2.endsAt, 1); ids.push(l2.id);
    await authorise(await payLineScoped(folioId, l2));                 // scoped to l2 (its OWN line)
  }
  await waitForRevenue(folioId, ids);
  for (const id of ids) await completeLine(folioId, id);
  await completeFolio(folioId);
  record('Spa day (repeat-vertical, COMPLETED)', `${cust.name} · folio ${folioId.slice(0, 8)}`);
}

// 10. No-show / abandoned: folio + line, no payment. Fully unpaid, OPEN.
async function abandoned(cust, daysAgo) {
  const roomId = pick(...ROOM_PREF); if (!roomId) return;
  const folioId = (await openFolio(cust.id)).id;
  const w = roomWindow(daysAgo, 1);
  await addLine(folioId, roomId, w.startsAt, w.endsAt, 1);  // 1 room; nights from dates
  record('Abandoned booking (fully unpaid, OPEN)', `${cust.name} · folio ${folioId.slice(0, 8)}`);
}

// ── orchestration: many separate visits per customer across ~90 days ─────────
async function main() {
  console.log(`core=${CORE}  sim=${SIM}`);
  await core('GET', '/actuator/health').catch(() => {
    throw new Error('core-api not reachable at /actuator/health — is the stack up (smoke overlay)?');
  });
  await probeProducts();
  const names = [...AVAILABLE].map(id => Object.entries(P).find(([, v]) => v === id)?.[0]);
  console.log(`products available (${AVAILABLE.size}): ${names.join(', ')}`);
  if (AVAILABLE.size === 0) throw new Error('No products found — run seed-products.sql first.');

  const customers = [];
  for (const r of ROSTER) {
    const c = await createCustomer(r.name, r.email, r.phone);
    for (const [k, v] of Object.entries(r.prefs)) await setPreference(c.id, k, v);
    customers.push({ ...c, ...r });
  }
  console.log(`created ${customers.length} customers`);

  const V = (fn, daysAgo) => ({ fn, daysAgo });
  const plans = [
    /* John Patel   */ [V(cleanCrossVerticalStay, 84), V(walkInDinner, 40), V(spaDay, 7)],
    /* Li Chen      */ [V(spaDay, 78), V(cleanCrossVerticalStay, 33), V(walkInDinner, 3)],
    /* Amara Okafor */ [V(inHouseHeld, 2), V(walkInDinner, 55), V(goodwillRefund, 70)],
    /* Diego F.     */ [V(partialCapture, 62), V(spaDay, 25), V(walkInDinner, 5)],
    /* Sofia Rossi  */ [V(spaDay, 88), V(fullRefundCancelledStay, 47), V(spaDay, 12)],
    /* Tom Whitmore */ [V(walkInDinner, 73), V(partialPayment, 20), V(walkInDinner, 4)],
    /* Priya Nair   */ [V(cleanCrossVerticalStay, 66), V(inHouseHeld, 1)],
    /* Hassan Ali   */ [V(walkInDinner, 58), V(spaDay, 30), V(cancelledAuth, 9)],
    /* Greta Bauer  */ [V(spaDay, 51), V(goodwillRefund, 22), V(walkInDinner, 6)],
    /* Marcus Lee   */ [V(cleanCrossVerticalStay, 44), V(walkInDinner, 15)],
    /* Elena P.     */ [V(partialPayment, 38), V(spaDay, 18), V(abandoned, 8)],
    /* Walk-in      */ [V(walkInDinner, 2), V(walkInDinner, 1), V(walkInDinner, 0)],
  ];

  let ok = 0, fail = 0;
  for (let i = 0; i < customers.length; i++) {
    for (const { fn, daysAgo } of plans[i]) {
      try { await fn(customers[i], daysAgo); process.stdout.write('.'); ok++; }
      catch (e) { process.stdout.write('x'); fail++; console.error(`\n  ! ${fn.name} / ${customers[i].name}: ${e.message}`); }
    }
  }
  console.log(`\n\nvisits: ${ok} ok, ${fail} failed`);

  // ── self-verifying read-back through the showcase reports ──────────────────
  const wide = '?from=2000-01-01T00:00:00Z&to=2100-01-01T00:00:00Z';
  const revenue = await core('GET', `/reports/revenue${wide}`);
  const unpaid  = await core('GET', '/reports/unpaid-bookings');

  console.log('\n=== SCENARIOS CREATED ===');
  for (const s of SUMMARY) console.log(`  • ${s.label}\n      ${s.detail}`);

  const gbp = (m) => `£${(m / 100).toFixed(2)}`;
  console.log('\n=== REVENUE by vertical (all-time window) ===');
  for (const v of revenue.byVertical ?? [])
    console.log(`  ${String(v.vertical).padEnd(5)}  gross=${gbp(v.gross)}  refunded=${gbp(v.refundedTotal)}  net=${gbp(v.net)}`);
  if (revenue.totals)
    console.log(`  TOTAL  gross=${gbp(revenue.totals.gross)}  refunded=${gbp(revenue.totals.refundedTotal)}  net=${gbp(revenue.totals.net)}`);
  console.log(`\n=== UNPAID WORKLIST: ${(unpaid.bookings ?? []).length} bookings owe money ===`);
  console.log(`\nHTTP calls: ${CALLS}`);
}

main().catch((e) => { console.error('\nFATAL:', e.message); process.exit(1); });
