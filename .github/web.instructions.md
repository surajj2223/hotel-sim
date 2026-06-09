---
applyTo: "ops-web/**,pay-web/**"
---

# React Conventions — `ops-web` & `pay-web`

Stack-specific structure for the React apps. Builds on `.github/copilot-instructions.md`
(always applies). When this file and a frozen contract disagree, the contract wins — flag it.

- Vite + React + TypeScript. Function components and hooks only (no class components).
- `ops-web` is a **complete standalone operations console** (Wave 1 Package F) — not a
  confirmation stub. `pay-web` is a small simulated hosted-checkout page (Package E).
- **Thin client rule:** no business logic in the UI. All rules, validation of record state,
  pricing, availability, and the human-auth gate live in `core-api`. The UI presents,
  collects input, calls the API, and renders the result.

---

## 1. Folder structure (feature-first)

Mirror the API's feature split so a UI feature maps to one API area.

```
ops-web/
  index.html
  package.json  vite.config.ts  tsconfig.json
  src/
    main.tsx
    App.tsx
    routes.tsx
    api/
      client.ts                 # configured fetch/axios wrapper (base URL, error envelope)
      generated/                # OpenAPI-generated types & client — DO NOT hand-edit
        index.ts
      hooks/                    # data hooks wrapping the client (React Query)
        useCustomers.ts  useBooking.ts  usePayments.ts  useRevenue.ts
    features/
      customers/
        CustomerListPage.tsx
        CustomerDetailPage.tsx
        CustomerForm.tsx
        PreferencesEditor.tsx
        components/             # feature-local presentational components
      bookings/
        BookingListPage.tsx
        FolioPage.tsx           # the cross-vertical folio view
        NewBookingWizard.tsx    # compose room + spa + dinner into one folio
        AvailabilitySearch.tsx
        components/
      payments/
        PaymentPanel.tsx        # create link, capture, refund, cancel
        CaptureDialog.tsx       # partial capture (amount-bearing)
        RefundDialog.tsx
        components/
      finance/
        RevenueDashboard.tsx    # revenue by vertical
        UnpaidBookingsPage.tsx
    components/                 # shared, app-wide presentational components
      ui/                       # buttons, inputs, dialogs, table, money display
      ConfirmAction.tsx         # the dual-channel human confirmation control
      MoneyText.tsx             # renders minor-units + currency correctly
    lib/
      money.ts                  # minor-units formatting/parsing (NO float math)
      format.ts
    types/
      domain.ts                 # re-exports from api/generated, plus UI-only view types
```

`pay-web` is much smaller: a single checkout route that takes a `paymentLinkId`, shows the
amount, and POSTs a simulated "pay" that triggers the webhook (see `WAVE0_03`).

---

## 2. The API layer (this is where contract fidelity lives)

- **Generate types/client from the OpenAPI contract.** `api/generated/` is produced from
  `WAVE0_02_OPENAPI.yaml` (e.g. via `openapi-typescript` / `openapi-fetch`). **Never
  hand-edit generated files**; regenerate when the contract changes. This guarantees the UI
  and API never drift — the compiler catches a contract change.
- `api/client.ts` configures the base URL and centralises **error-envelope handling**: parse
  the `ApiError` shape, surface `409` (write-time revalidation / state changed) and the
  human-auth responses distinctly so the UI can react.
- `api/hooks/` wraps the client in **React Query** hooks. Components consume hooks, never call
  `fetch` directly. Reads are queries; writes are mutations that invalidate the right queries.

```ts
// api/hooks/useBooking.ts
export function useFolio(bookingId: string) {
  return useQuery({ queryKey: ['folio', bookingId],
    queryFn: () => api.GET('/bookings/{id}/folio', { params: { path: { id: bookingId } } }) });
}
export function useCreateBooking() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: CreateBookingRequest) => api.POST('/bookings', { body }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['bookings'] }),
  });
}
```

---

## 3. State, data, and money

- **Server state** lives in React Query (the API is the source of truth). Don't duplicate it
  into global stores. **UI state** (form fields, open dialogs) is local `useState`/`useReducer`.
  Reach for a global store only if genuinely app-wide and not server-derived — flag if unsure.
- **Money is minor units (integer) end to end.** Format for display only at the edge via
  `lib/money.ts` / `<MoneyText>`. Never do arithmetic on formatted strings, never use floats.
  "Paid" is `balance === 0`, computed from the API's amounts — never a boolean the UI invents.
- Enum values (vertical, statuses, capture mode) come from the generated types — don't
  hardcode string literals that could drift from `ENM-*`.

---

## 4. Components

- Function components, typed props (no `any`). Prefer small presentational components fed by a
  page/container that owns the data hooks.
- Co-locate feature-local components under the feature; promote to `components/ui/` only when
  reused across features.
- Forms validate **shape/required** client-side for UX, but the **authoritative validation is
  the API**. Always handle and display the API's `409`/error-envelope responses — the UI must
  show "that room just went, here's current availability," not swallow it.

---

## 5. The human-in-the-loop control (the proof surface)

The dual-channel confirmation story is a first-class UI concern, not an afterthought:

- `ConfirmAction` is the shared control for confirming a repercussive write. A click here is a
  legitimate human-authorisation signal — the same gate the agent's "yes" satisfies server-side
  (`INV-007`). The UI sends the human-auth signal exactly as the OpenAPI contract specifies.
- The folio/payment screens must clearly show payment *state* distinctions the model supports:
  authorised-but-not-captured vs captured vs refunded. This is what makes "capture at checkout"
  legible to an operator.

---

## 6. Build, test, conventions

- `npm run dev` (Vite), `npm run build`, `npm run gen:api` (regenerate the client from the
  contract).
- Tests: Vitest + React Testing Library. Test behaviour (does the folio render unpaid balance,
  does a 409 surface the conflict), not implementation detail. Tag tests with the `API-*` IDs
  they exercise where it makes sense.
- Lint/format with ESLint + Prettier; no committed lint errors.
- TypeScript `strict` on. No `any`, no non-null `!` to silence the compiler — fix the type.

---

## 7. Definition of done

A UI feature is done when: it's built on the generated client (no hand-rolled request shapes),
it handles the error envelope including `409` and human-auth responses, money is minor-units
throughout, server state is in React Query, it's usable standalone in `ops-web` (no agent
required), and it has behavioural tests. Record completion against the relevant `API-*` IDs in
the verification log.
