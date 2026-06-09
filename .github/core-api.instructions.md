---
applyTo: "core-api/**,payments-sim/**"
---

# Spring Boot Conventions — `core-api` & `payments-sim`

Stack-specific structure for the Spring Boot services. This builds on the house rules in
`.github/copilot-instructions.md` (which always apply). When this file and a frozen contract
disagree, the contract wins — flag it.

- Build tool: **Gradle, Groovy DSL** (`build.gradle`, `settings.gradle`), with the wrapper.
- Java 21, Spring Boot 3.x.
- Package root: `com.hotelops.core` (core-api), `com.hotelops.payments` (payments-sim).

---

## 1. Package-by-feature, not package-by-layer

Top-level packages are **features**, not layers. Each feature folder owns its own
controller/service/domain/repository. This aligns folder ownership with the Wave 1 package
split, so parallel agents rarely touch the same directory.

```
core-api/
  build.gradle
  settings.gradle
  gradlew  gradlew.bat  gradle/wrapper/
  src/main/java/com/hotelops/core/
    HotelOpsApplication.java
    customer/                 # Wave 1 Package A (domain spine) + customer feature
      CustomerController.java
      CustomerService.java
      Customer.java                 # JPA entity (maps SCH-001)
      CustomerPreference.java       # JPA entity (maps SCH-003)
      CustomerRepository.java
      CustomerPreferenceRepository.java
      dto/
        CustomerDto.java
        CreateCustomerRequest.java
        CustomerPreferenceDto.java
      CustomerMapper.java           # entity <-> DTO
    product/                  # Wave 1 Package A + B
      Product.java                  # JTI base entity (SCH-010)
      ProductRoom.java  ProductSpa.java  ProductFnb.java  ProductEvent.java  # SCH-011..014
      ProductController.java
      ProductService.java
      ProductRepository.java
      dto/
        ProductDto.java               # polymorphic envelope (base + per-vertical block)
        RoomConfigDto.java  SpaConfigDto.java  FnbConfigDto.java  EventConfigDto.java
      ProductMapper.java
      vertical/                     # Strategy-per-vertical lives here (Package B)
        VerticalStrategy.java         # interface: availability, pricing, defaultCaptureMode()
        RoomStrategy.java  SpaStrategy.java  FnbStrategy.java  EventStrategy.java
        VerticalStrategyRegistry.java # resolves strategy by `vertical`
    booking/                  # Wave 1 Package A (booking/folio)
      Booking.java  BookingLine.java          # SCH-020, SCH-022
      BookingController.java
      BookingService.java                      # write-time revalidation (INV-003) lives here
      BookingRepository.java  BookingLineRepository.java
      dto/ ...
      BookingMapper.java
    payment/                  # Wave 1 Package D
      Payment.java  Refund.java                # SCH-030, SCH-040
      PaymentController.java
      PaymentService.java                       # capture/refund/cancel orchestration
      PaymentRepository.java  RefundRepository.java
      psp/                                      # outbound PSP port + adapter
        PspClient.java                          # interface (port)
        PaymentsSimPspClient.java               # HTTP adapter to payments-sim
      webhook/
        WebhookController.java                  # inbound PSP webhooks (idempotent, SCH-070)
        WebhookInbox.java  WebhookInboxRepository.java
      dto/ ...
    ledger/                   # Wave 1 Package C
      LedgerPosting.java                        # SCH-050
      LedgerService.java                        # posts on capture, not auth (INV-006)
      LedgerPostingRepository.java
      OutboxEvent.java  OutboxEventRepository.java   # SCH-060
      OutboxProcessor.java                      # consumes events -> postings
      RevenueQueryService.java                  # getRevenue(window, groupBy)
      dto/ ...
    common/                   # shared, NOT a junk drawer
      Money.java                                # minor-units value object + currency
      error/
        ApiError.java                           # the error envelope DTO (matches OpenAPI)
        GlobalExceptionHandler.java             # @RestControllerAdvice
        StateChangedException.java              # -> 409 write-time revalidation failure
        HumanAuthRequiredException.java         # -> 403/428 missing human-auth signal
      auth/
        HumanAuthorizationGate.java             # server-side gate (INV-007)
      config/
        JacksonConfig.java  OpenApiConfig.java
  src/main/resources/
    application.yml
    db/migration/
      V1__wave0_schema.sql                      # = WAVE0_01_SCHEMA.sql (Flyway)
  src/test/java/com/hotelops/core/
    <mirror feature packages>                   # tests tagged by requirement ID
```

`payments-sim` follows the same shape under `com.hotelops.payments` but is small:
`payment/` (link creation, auth/capture/refund simulation, pspReference minting) and
`webhook/` (outbound webhook emitter). See `WAVE0_03_WEBHOOK_PSP_CONTRACT.md`.

---

## 2. Layering rules (what may import what)

Within a feature, dependencies point inward only:

```
controller  ->  service  ->  domain (entity + domain logic)  ->  repository
     |             |                                              ^
     +--> dto      +--> mapper -------------------------------------
```

- **Controller**: thin. Parses/validates the request DTO, calls one service method, maps the
  result to a response DTO, sets the HTTP status. No business logic. No repository access.
- **Service**: the application logic. Transaction boundaries (`@Transactional`) live here.
  Write-time revalidation, the human-auth gate check, and orchestration live here.
- **Domain (entity)**: JPA entity + invariants that belong to the entity itself. No Spring
  web types. No DTOs.
- **Repository**: Spring Data interface. No business logic.
- **Mapper**: explicit entity <-> DTO conversion. Never serialise an entity directly.

Cross-feature calls go **service -> service**, never controller -> another feature's
service, never repository -> another feature's repository. If two features need shared
types, they go in `common/` — but `common/` is for genuinely shared primitives (Money,
error envelope, the auth gate), not a dumping ground.

---

## 3. Naming conventions

| Kind | Convention | Example |
|------|------------|---------|
| Entity | Singular noun, matches table meaning | `Booking`, `BookingLine`, `Payment` |
| Repository | `<Entity>Repository` | `BookingRepository` |
| Service | `<Feature>Service` | `BookingService` |
| Controller | `<Feature>Controller` | `BookingController` |
| Request DTO | `<Verb><Noun>Request` | `CreateBookingRequest` |
| Response DTO | `<Noun>Dto` | `BookingDto`, `FolioDto` |
| Mapper | `<Feature>Mapper` | `BookingMapper` |
| Strategy | `<Vertical>Strategy` | `RoomStrategy` |
| Exception | `<Condition>Exception` | `StateChangedException` |

- REST paths are plural kebab/nouns from the OpenAPI contract (e.g. `/bookings`,
  `/payments/{id}/capture`). The contract is authoritative for exact paths.
- Enum values match `ENM-*` exactly (e.g. `CaptureMode.IMMEDIATE`). Do not invent variants.

---

## 4. Money, persistence, and contract fidelity

- Money is a `Money` value object wrapping `long minorUnits` + `String currency`. Persist as
  `BIGINT` + `CHAR(3)` exactly as the schema defines. **No `BigDecimal` for storage, no
  `double` anywhere.** Arithmetic on `Money`, never on raw doubles.
- JPA entities map the frozen schema 1:1. Do **not** let Hibernate auto-generate or alter
  DDL — `spring.jpa.hibernate.ddl-auto=validate`. The schema comes from the Flyway migration
  (`V1__wave0_schema.sql`), which **is** `WAVE0_01_SCHEMA.sql`. Hibernate validates against
  it; it never owns it.
- JTI: map `Product` as the base with `@Inheritance(strategy = JOINED)`; children extend it.
  The single-child invariant (`INV-002`) is enforced in `ProductService`, not by JPA.
- DTOs match the OpenAPI schemas (`API-*`) field-for-field. If a mapping is awkward, that's a
  flag against the contract — don't quietly diverge.

---

## 5. Errors & the safety mechanisms

- One error envelope for the whole API: `ApiError` (matches the OpenAPI error schema), emitted
  by `GlobalExceptionHandler`. Don't hand-roll error JSON in controllers.
- Write-time revalidation failures throw `StateChangedException` -> **409** with the current
  state in the body (`INV-003`). Never silently overwrite.
- Missing/invalid human-auth signal throws `HumanAuthRequiredException`, handled by
  `HumanAuthorizationGate` (`INV-007`). The gate is server-side; a client (UI or agent)
  cannot self-mint the signal.
- Webhook handling is idempotent (`SCH-070/071`): dedupe by idempotency key, match by
  `merchantReference`, stamp `pspReference`. Re-delivery must be a no-op.

---

## 6. Worked example — one feature, end to end

A minimal `customer` slice showing the layering. (Illustrative; the real DTOs/paths come from
the OpenAPI contract.)

```java
// CustomerController.java  — thin
@RestController
@RequestMapping("/customers")
class CustomerController {
  private final CustomerService service;
  CustomerController(CustomerService service) { this.service = service; }

  @PostMapping
  ResponseEntity<CustomerDto> create(@Valid @RequestBody CreateCustomerRequest req) {
    return ResponseEntity.status(201).body(service.create(req));   // API-00x
  }
}

// CustomerService.java  — logic + transaction boundary
@Service
class CustomerService {
  private final CustomerRepository repo;
  private final CustomerMapper mapper;
  CustomerService(CustomerRepository repo, CustomerMapper mapper) {
    this.repo = repo; this.mapper = mapper;
  }

  @Transactional
  CustomerDto create(CreateCustomerRequest req) {
    var c = new Customer();
    c.setFullName(req.fullName());
    c.setShopperReference(ShopperReference.mint());   // INV-001: minted once, never changes
    c.setEmail(req.email());
    return mapper.toDto(repo.save(c));
  }
}

// Customer.java  — entity, maps SCH-001; no web/DTO types
@Entity @Table(name = "customer")
class Customer {
  @Id @GeneratedValue(strategy = GenerationType.UUID) UUID id;
  @Column(name = "shopper_reference", nullable = false, unique = true, updatable = false)
  String shopperReference;                              // updatable=false reinforces INV-001
  @Column(name = "full_name", nullable = false) String fullName;
  String email; String phone;
  // getters/setters
}
```

Note: controller has no logic, service owns the transaction and the invariant, entity has no
Spring-web or DTO imports, mapper does the conversion. Copy this shape for every feature.

---

## 7. Build & run

- `./gradlew build` — compile + test. `./gradlew bootRun` — run locally.
- Tests use JUnit 5 + Spring Boot Test; integration tests use **Testcontainers Postgres** so
  they run against the real schema, not H2. Tag tests with the requirement ID they prove.
- Don't add dependencies casually — each new library is a flag-worthy decision in a POC.

---

## 8. Definition of done (per the house rules)

A feature is done when: it implements its contract-slice IDs; entities validate against the
Flyway-applied schema; DTOs match the OpenAPI schemas; the invariants it touches have tests
referencing their `INV-*` IDs; and the verification log in the relevant contract file is
filled (requirement ID, what was built, commit/PR, proving test).
