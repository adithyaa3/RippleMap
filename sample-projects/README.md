# Sample projects

`shop-v1` and `shop-v2` are two versions of a small order-management service. They are made for trying RippleMap,
not for compiling: they reference library types that are not included (a `com.thirdparty.payments` client, Spring
Web annotations and SLF4J).

| File | Contents |
|---|---|
| `shop-v1.zip` / `shop-v1/` | version 1: 25 types in `domain`, `repository`, `service`, `pricing`, `payment`, `events`, `support`, `web`, plus one test class |
| `shop-v2.zip` / `shop-v2/` | version 2: adds `ShippingService` and `NoDiscount`, removes `SeasonalDiscount`, and breaks the `InventoryService ↔ ReservationService` cycle |
| `make_samples.py` | regenerates both directories and ZIPs (`python make_samples.py` in this directory) |

## What RippleMap reports

These are the values RippleMap 1.0.0 produced when the packaged application imported the two ZIPs over HTTP
(production sources only, default settings; see `docs/VERIFICATION_REPORT.md`). They are observations, not
hand-derived expectations; the golden fixtures in `src/test/resources/fixtures` serve that purpose.

| | v1 | v2 |
|---|---|---|
| Java files parsed (test file excluded) | 25 | 26 |
| Types | 25 | 26 |
| Methods / constructors | 40 / 10 | 41 / 10 |
| Type dependencies (distinct pairs) | 48 | 51 |
| Method dependencies | 37 | 39 |
| Cyclic components (type layer) | 1 (`InventoryService → ReservationService → InventoryService`) | 0 |
| References resolved / inferred / unresolved | 126 / 3 / 0 | 129 / 2 / 0 |
| References external (JDK, libraries) | 71 | 76 |

Comparing v1 with v2 (type layer) shows the following changes:

* Added types: `pricing.NoDiscount`, `shipping.ShippingService`. Removed: `pricing.SeasonalDiscount`.
* Seven dependencies were added. Examples: `OrderController → ShippingService`, `ShippingService → Order`,
  `DefaultOrderService → NoDiscount`.
* Four dependencies were removed, including `ReservationService → InventoryService`, which was the edge that closed
  the cycle.

Impact in v2 (type layer, N = 26):

| Changed type | D | T | H | Score | Level |
|---|---|---|---|---|---|
| `domain.Money` | 10 | 19 | 2 | (45·10 + 40·19)/25 + 3·2 = **54.4** | MEDIUM |
| `domain.Order` | 8 | 8 | 1 | (45·8 + 40·8)/25 + 3·1 = **30.2** | MEDIUM |

The library references (payments client, Spring, SLF4J) are reported as external. None of them is unresolved,
because their names do not collide with project types.
