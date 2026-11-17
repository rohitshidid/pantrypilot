# PantryPilot

AI-assisted shared kitchen inventory. Final project for CS6103 Introduction to Java (Fall 2026).

Team: Rohit Shidid, Prachiti Kulkarni, Vishvaksen Pendyala.

PantryPilot fills a household's inventory from a receipt scan or a grocery photo, and takes ingredients out when you paste the recipe link you cooked from. The AI only proposes changes. The user confirms or edits them before anything is applied.

## What is in this repository

One Spring Boot server (Java 21) split into three modules, one per team member. The modules only talk through the types in `shared`, and a test fails the build if one reaches into another's internals.

| Package | Owner | State |
|---|---|---|
| `com.pantrypilot.shared` | all | The team contract: interfaces, `ProposedChange`, events |
| `com.pantrypilot.ai` | Rohit | **Built**: receipt scan, grocery photos, recipe links, price estimator, threaded job pipeline |
| `com.pantrypilot.core` | Prachiti | **Built**: catalog and unit conversion, households and roles, confirmation queue, transactional ledger with audit log and undo, private items, expiry and leftovers |
| `com.pantrypilot.insights` | Vishvak | **Placeholder**: one endpoint under `insights.stub` showing the money-saved formula |

The insights placeholder exists so the other modules can run end to end. It is meant to be deleted when the real module arrives.

## Run it

You need Java 21. Everything else is optional, and the status row on the test page shows what is available.

| Tool | Needed for | Install (macOS) |
|---|---|---|
| [Ollama](https://ollama.com) with a vision model | photos, receipt line parsing, price estimates, pages without recipe data | `ollama pull gemma4` |
| Tesseract | receipt scanning | `brew install tesseract` |
| yt-dlp | YouTube links | `brew install yt-dlp` |

```bash
./mvnw spring-boot:run
```

Then open <http://localhost:8090>. If Maven picks up a newer JDK and fails, point it at Java 21 first: `export JAVA_HOME=$(/usr/libexec/java_home -v 21)`.

Settings can be changed with environment variables:

| Variable | Default | Meaning |
|---|---|---|
| `PORT` | `8090` | Server port |
| `OLLAMA_URL` | `http://localhost:11434` | Ollama server |
| `PANTRYPILOT_VISION_MODEL` | `gemma4` | Any Ollama model with vision |
| `PANTRYPILOT_TEXT_MODEL` | `gemma4` | Any Ollama text model |
| `SEARXNG_URL` | empty | SearXNG instance for web price search; empty means ask the model |

The default database is in-memory H2, so data is lost on restart. For PostgreSQL: `docker compose up -d`, then `./mvnw spring-boot:run -Dspring-boot.run.profiles=postgres`.

## Test it

```bash
./mvnw test
```

160 tests. They need no Ollama, no network and no yt-dlp, because those are replaced by fakes. The OCR tests run the real Tesseract and skip themselves if it is not installed.

To try the real thing, start the server and use the test page:

1. **Recipe link**: paste a recipe page such as `https://www.bbcgoodfood.com/recipes/best-spaghetti-bolognese-recipe`, set servings, press *Propose deduction*. Works without Ollama when the page has schema.org recipe data.
2. **Restaurant price**: type a dish, press *Estimate*, then correct the price and estimate again.
3. **Receipt**: upload a photo of a receipt.
4. **Groceries**: upload one to six photos. A local vision model takes 30 to 60 seconds per photo.

Each result lands in the review queue, where it can be confirmed or rejected. Confirming applies it to the pantry shown below the queue, and every change appears in the history with an Undo button. The page starts as "Demo User" in "Demo Household" (both id 1), which the server creates on an empty database.

## AI module API

The three POST endpoints start a background job and answer `202 Accepted` at once. Poll the job until its status is `SUCCEEDED` or `FAILED`.

| Method and path | Body | Purpose |
|---|---|---|
| `POST /api/ai/receipts` | multipart: `image`, `householdId`, `userId` | Scan a receipt |
| `POST /api/ai/photos` | multipart: `images` (1 to 6), `householdId`, `userId` | Recognise groceries |
| `POST /api/ai/recipe-links` | JSON: `url`, `servingsMade` (optional), `householdId`, `userId` | Deduction for a cooked recipe |
| `GET /api/ai/jobs/{id}` | | Job status and, when finished, the proposal |
| `GET /api/ai/jobs?householdId=` | | The household's 20 most recent jobs |
| `GET /api/ai/price-estimate?dish=&servings=&city=` | | Price of ordering the dish out |
| `PUT /api/ai/price-estimate` | JSON: `dish`, `city`, `pricePerServing` | Save the user's own price |
| `GET /api/ai/status` | | Which tools and models are available |

```bash
curl -X POST localhost:8090/api/ai/recipe-links -H 'Content-Type: application/json' \
  -d '{"url":"https://www.bbcgoodfood.com/recipes/best-spaghetti-bolognese-recipe","servingsMade":3,"householdId":1,"userId":1}'
curl localhost:8090/api/ai/jobs/1
```

## How the AI module works

- **Job pipeline** (`ai.internal.jobs`): a fixed thread pool with a bounded queue. A request records a job and returns; a worker thread does the work through a `CompletableFuture` with a timeout. Finishing a job locks its database row, so a worker and the timeout cannot both finish it and a late result is never submitted. When the queue is full the server answers 503.
- **Receipts** (`ai.internal.receipt`, `ai.internal.ocr`): grayscale, enlarge, deskew, then Tesseract through Tess4J. The text model turns lines like `ORG BNS 2LB` into ingredients. Unit prices are computed in Java from the line total. If the model is down, a layout-based parser takes over with low confidence.
- **Photos** (`ai.internal.photo`): each photo goes to the vision model on its own virtual thread, then the answers are merged. Within a photo, amounts of one ingredient are added. Across photos the largest wins, since photos of one haul overlap.
- **Recipe links** (`ai.internal.recipe`): schema.org recipe data is read with Jsoup and parsed without any model. Pages without it go to the text model. YouTube links are read with yt-dlp: the description's ingredient list first, then the model over the description, then the captions.
- **Prices** (`ai.internal.price`): the user's own price, else the cache, else the median of prices found through SearXNG, else the model's estimate.
- **Safety**: every number from a model is range-checked. Links to private network addresses are refused, on every redirect. Uploads are checked to be real images.

## Core module API

Until login exists, `userId` is a request parameter saying who is asking. Every call checks that user is a member of the household. Errors come back as `{"error": "..."}` with 400, 403, 404 or 409.

| Method and path | Purpose |
|---|---|
| `POST /api/core/users` | Register: `displayName`, `email` |
| `GET /api/core/users/{id}/households` | The households a user belongs to |
| `POST /api/core/households?userId=` | Create a household (`name`); the creator is its admin |
| `POST /api/core/households/join?userId=` | Join with an `inviteCode` |
| `GET /api/core/households/{h}/members?userId=` | Members and roles |
| `PUT /api/core/households/{h}/members/{memberId}?userId=` | Change a `role` (admin only) |
| `DELETE /api/core/households/{h}/members/{memberId}?userId=` | Remove a member (admin), or leave |
| `POST /api/core/households/{h}/invite-code?userId=` | New invite code (admin only) |
| `GET /api/core/households/{h}/pantry?userId=` | Every lot, soonest expiry first |
| `GET /api/core/households/{h}/stock?userId=` | One row per ingredient, in count, grams and millilitres |
| `GET /api/core/households/{h}/expiring?userId=&days=3` | Lots expiring within `days` |
| `GET /api/core/households/{h}/history?userId=&limit=50` | The audit log |
| `POST /api/core/households/{h}/stock-in?userId=` | Manual entry: `lines` of `name` or `ingredientId`, `quantity`, `unit`, optional `unitPrice`, `location`, `expiresOn`, `personal` |
| `POST /api/core/households/{h}/consume?userId=` | Manual use: `lines`, optional `strict`, `dishName`, `servings` |
| `POST /api/core/households/{h}/lots/{lotId}/discard?userId=` | Throw away: optional `quantity`, `reason` |
| `POST /api/core/households/{h}/leftovers?userId=` | Save a leftover: `name`, `servings`, optional `location`, `expiresOn`, `personal` |
| `POST /api/core/households/{h}/changes/{txnId}/undo?userId=` | Undo a change |
| `GET /api/core/pending?householdId=&userId=` | The review queue |
| `PUT /api/core/pending/{id}/lines?userId=` | Replace a proposal's lines with corrected ones |
| `POST /api/core/pending/{id}/confirm?userId=&strict=true` | Apply a proposal |
| `POST /api/core/pending/{id}/reject?userId=` | Drop a proposal |
| `GET /api/core/catalog?q=` | Search the catalog |
| `GET /api/core/catalog/resolve?name=` | Match free text to an ingredient |
| `GET /api/core/catalog/convert?ingredientId=&quantity=&from=&to=` | Convert between COUNT, G and ML |
| `POST /api/core/catalog` | Add an ingredient |

## How the core module works

- **Lots and ledger** (`core.internal.inventory`): a lot is one purchase of one ingredient with its own price and expiry. Every amount that moves is one row in an append-only ledger, signed, so the ledger is the audit log and a lot's quantity always equals the sum of its ledger rows.
- **Atomicity**: `InventoryWriter` is the only class that changes stock, and each of its methods is one `@Transactional` unit. Confirming a proposal (`PendingStore.confirm`) runs the lots, the ledger rows, the cook event and the CONFIRMED state in a single transaction, so a line that fails (not enough stock, not matched) rolls back the whole proposal and leaves it PENDING.
- **Concurrency**: lots and proposals carry a `@Version` column. A member who writes from a stale read is refused instead of overwriting the other member's change; `ConflictRetry` then runs that whole transaction again on fresh data. A proposal confirmed by several people at once is applied once.
- **Undo**: a compensating transaction. The original rows stay; a new transaction is appended with the opposite amounts and a pointer to the one it reverses. A unique constraint on that pointer means a change can be undone only once. Stock that has been partly used cannot be un-added until the use is undone.
- **Deduction order**: soonest expiry first, across the lots the user may use. A recipe in grams can deduct from items stored by count, through the ingredient's average weight or density. `strict=false` uses what is there and reports the shortfall.
- **Private items**: a lot marked `personal` is visible to and usable by its owner only, so another member's recipe never consumes it.
- **Expiry and leftovers**: a lot without a date gets one from the ingredient's shelf life in the place it is stored. A leftover is a household-scoped item counted in servings, with 4 days in the fridge or 90 in the freezer. `ExpiryNotifier` publishes `ItemExpiringSoon` daily at 08:00 for lots expiring that day or in two days.
- **Events**: `InventoryChanged`, `ItemConsumed`, `ItemDiscarded` and `DishCooked` are published only after the transaction has committed, so the insights module never sees a change that was rolled back.
- **Catalog** (`core.internal.catalog`): 80 ingredients seeded by Flyway with aliases, average weights, densities and shelf lives, held in memory as an immutable snapshot. Matching tries the exact name or alias, the singular, the longest name inside the text, then one or two typos.

## For teammates: replacing a placeholder

1. Write your implementation of the interface in your own module (for example `CatalogService` in `core`).
2. Delete the matching class under `insights.stub`.
3. Run `./mvnw test`. `ModularityTest` checks that modules still only use `shared`.

The AI module uses `CatalogService.resolve` and `PendingChangeService.submit`, and provides `PriceEstimator` and `RecipeExtractor`. The core module provides `CatalogService`, `QuantityService`, `PendingChangeService` and `InventoryQuery`, and publishes the events in `shared.events`. `StubCatalog` now lives under `src/test` only, as a database-free catalog for the AI unit tests. In `ProposedLine`, `unitPrice` is the price of one unit: one item, one gram or one millilitre.

## Not done yet

- Stretch goals: photos while cooking, finished-dish photo with correction memory, Instagram links.
- Speech-to-text fallback (whisper.cpp) for videos without captions.
- The Flutter screens for capture and link paste. The test page stands in for them.
- Login. `householdId` and `userId` are plain request parameters; Spring Security with JWT is not added yet. The core module checks household membership on every call, but trusts the `userId` it is given.
- The Flutter pantry and household screens. The test page stands in for them.
- Undoing a cooked dish restores the stock but does not publish an event, because `shared.events` has none for it; the cook event row stays. The insights module would need one to take the dish back out of money saved.
- Photo storage (MinIO). Photos are processed in memory and not kept.
- The labelled evaluation sets and accuracy scripts from the proposal.

Known limits of what is built:

- Receipt scanning has only been tried on computer-drawn receipt images, not on phone photos of paper receipts. Sideways phone photos are not rotated, and HEIC images are not accepted.
- Web price search has been tested against sample SearXNG answers only, never a running SearXNG.
- The PostgreSQL profile has not been run, including the core module's migrations (`V2`, `V3`), which have only run on H2 in PostgreSQL mode.
- The catalog's weights, densities and shelf lives are approximate hand-entered values, not yet imported from USDA FoodData Central and FoodKeeper.
- `submit` accepts a proposal for any household id; membership is checked when a user reads or decides it.
- The models are called with `java.net.http.HttpClient` directly rather than through LangChain4j, and it has been run with `gemma4` rather than the Qwen2.5-VL and Llama 3.1 models named in the proposal.

## Libraries

Spring Boot, Spring Data JPA, Spring Modulith, Flyway, H2, PostgreSQL driver, Tess4J (Tesseract), Jsoup, JUnit 5, AssertJ. External tools: Ollama, Tesseract, yt-dlp.
