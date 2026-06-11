# Payment & refund flows

How money moves through `core-api`, from a payment being created through to a
ledger posting — and the mirror-image path for refunds.

> **What's live today vs. designed:** everything from **capture** rightward (the
> outbox note, the background worker, the ledger postings) is built and runnable.
> Everything left of capture — starting a payment, the checkout link, the guest
> paying, the authorisation webhook — depends on `payments-sim`, which is currently
> an empty shell with no payment controller wired. So the **right half of each
> diagram is live; the left half is designed but not yet executable.**

---

## The one rule that explains everything

**Putting a hold on a card is not the same as earning money.** Reserving £600
against a card is a promise. Money is only *earned* — written into the ledger —
when you actually **capture** it. This single rule explains why the flow splits
where it does and why cancelling an authorisation reverses nothing.

---

## Payment flow

Colours mark the money-state: grey = no money moved, amber = held but not earned,
green = taken / recorded.

```mermaid
flowchart TD
    A["Bill exists<br/>folio owes £600"]:::none
    B["Start payment<br/>intent only, no money"]:::none
    C["Checkout page<br/>link sent to guest"]:::none
    D["Authorised<br/>£600 held, NOT earned"]:::held
    E["Cancel auth<br/>release hold, nothing posted"]:::held
    F["Capture<br/>money taken + to-do note written"]:::taken
    G["Background worker<br/>reads to-do every 5s"]:::taken
    H["Ledger posting<br/>revenue, tagged by vertical"]:::taken
    I["Folio paid<br/>balance hits zero"]:::none

    A --> B --> C --> D
    D -->|guest cancels| E
    D -->|time to charge| F
    F --> G --> H --> I

    classDef none fill:#F1EFE8,stroke:#5F5E5A,color:#2C2C2A
    classDef held fill:#FAEEDA,stroke:#854F0B,color:#412402
    classDef taken fill:#E1F5EE,stroke:#0F6E56,color:#04342C
```

The path **splits at authorisation**. The left branch (cancel) touches nothing in
the ledger because nothing was ever posted — the payoff of "a hold isn't revenue."
The right branch is the only way money becomes earnings, and even then capture
doesn't write the ledger directly: it drops a note that the background worker
picks up.

---

## Refund flow

Same colour language, plus red for the reversal.

```mermaid
flowchart TD
    A["Captured payment exists<br/>£600 was taken"]:::taken
    B["Start refund<br/>linked to original payment"]:::ref
    C["Processor sends money back<br/>£200 returned to card"]:::ref
    D["Refund settled<br/>to-do note written"]:::ref
    E["Background worker<br/>same 5s loop"]:::taken
    F["Reversal posting<br/>minus £200, tagged by vertical"]:::reversal
    G["Folio updates<br/>refunded rises, balance recalculates"]:::none

    A --> B --> C --> D --> E --> F --> G

    classDef none fill:#F1EFE8,stroke:#5F5E5A,color:#2C2C2A
    classDef taken fill:#E1F5EE,stroke:#0F6E56,color:#04342C
    classDef ref fill:#FAEEDA,stroke:#854F0B,color:#412402
    classDef reversal fill:#FCEBEB,stroke:#A32D2D,color:#501313
```

A refund reuses the **exact same back half** as a payment — the to-do note and the
background worker are shared machinery. The only real differences: a refund must be
linked to an original *captured* payment (you can't refund a hold), and the posting
it produces is negative.

---

## Sequence view — who talks to whom over time

The same two flows from the angle of the four parties passing messages.

```mermaid
sequenceDiagram
    actor Staff as Front desk
    participant Core as Hotel system<br/>(core-api)
    participant PSP as Processor<br/>(payments-sim)
    actor Guest
    participant Worker as Background worker
    participant Ledger

    Note over Staff,Ledger: Payment
    Staff->>Core: collect £600 for booking
    Core->>Core: create payment attempt (PENDING)
    Core->>PSP: create checkout link
    PSP-->>Core: paymentLinkId
    Core-->>Guest: here is your payment link
    Guest->>PSP: enters card on hosted page
    PSP->>PSP: authorise — place hold, mint pspReference
    PSP-->>Core: webhook: AUTHORISED (+ pspReference)
    Core->>Core: match by merchantReference, status AUTHORISED
    Note right of Core: no ledger entry — a hold isn't revenue

    Staff->>Core: capture
    Core->>Core: mark CAPTURED + write outbox note (one transaction)
    Worker->>Core: poll outbox every 5s, claim note
    Worker->>Ledger: post REVENUE, split per vertical
    Note right of Ledger: folio balance now zero, paid

    Note over Staff,Ledger: Refund
    Staff->>Core: refund £200
    Core->>PSP: refund against original pspReference
    PSP-->>Core: webhook: REFUND settled (+ own pspReference)
    Core->>Core: mark refund settled + write outbox note
    Worker->>Core: poll, claim note
    Worker->>Ledger: post REFUND_REVERSAL (minus £200, per vertical)
```

Three things the sequence view makes obvious:

- **Every confirmation is a webhook** (the dashed return arrows). The processor is
  never called synchronously and trusted to answer inline — real PSPs are
  asynchronous, so the system is built to *receive* "it happened" messages.
- **The background worker is never in the critical path** of taking money. Staff
  trigger capture, the system commits, and only later does the worker independently
  move money into the ledger. That horizontal gap between "mark CAPTURED" and "post
  REVENUE" is the decoupling, drawn as time.
- **Both flows converge on one worker and one ledger.** Capture and refund aren't
  two pipelines; they're two kinds of note in one to-do list, drained by one worker.

---

## Reference: the four payment identifiers

| Reference | Who mints it | When | What it names |
|-----------|--------------|------|---------------|
| `shopperReference` | Hotel system | Customer created | The customer, permanently (one per human) |
| `merchantReference` | Hotel system | Per payment attempt | One payment attempt — the reconciliation anchor |
| `pspReference` | Processor | At authorisation | The processor's own id for the transaction |
| `paymentLinkId` | Processor | When link requested | The hosted checkout page |

Two are yours (you send them out), two are the processor's (you store what comes
back). Reconciliation is keeping your `merchantReference` and their `pspReference`
glued to the same row.
