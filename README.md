# Acme Financial Data Warehouse

Spring Boot + MongoDB financial warehouse for exactly three assets:

- `USDT` from Binance public daily klines
- `BTC` from Binance public daily klines
- `AAPL` from Yahoo Finance public chart data

No Nasdaq Data Link key is required. The LLM/MCP bonus part is intentionally not implemented.

## What The Project Demonstrates

- Bi-temporal storage with `businessDate` and `systemDate`
- Temporal inserts only for time-series versions; logical delete markers use `metadata.deleted=true`
- Heterogeneous indicator storage through `Map<String, Object>` values such as `Open`, `High`, `Low`, `Close`, and `Volume`
- Repository-based DAL with latest-version queries by `systemDate`
- Idempotent ETL that skips unchanged daily records
- REST APIs with pagination, filtering, sorting, and Swagger UI
- Apache Spark 4 analytics job that reads MongoDB, aggregates data, trains a simple linear regression model, and writes results back to MongoDB

## Requirements

- Java 21
- MongoDB running locally on `mongodb://localhost:27017`
- PowerShell or an IDE terminal

## Run The Tests

From this folder:

```powershell
.\mvnw.cmd test
```

Expected result:

```text
Tests run: 28, Failures: 0, Errors: 0, Skipped: 0
```

## Run The Application

```powershell
.\mvnw.cmd spring-boot:run
```

Open:

- Dashboard: [http://localhost:8080](http://localhost:8080)
- Swagger UI: [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html)

If port `8080` is busy:

```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--server.port=8091"
```

Then use `http://localhost:8091`.

## Main Workflow

1. Start MongoDB.
2. Start the Spring Boot app.
3. Open the dashboard or Swagger UI.
4. Run ingestion for all supported assets:

```powershell
Invoke-RestMethod -Method Post "http://localhost:8080/api/v1/ingest"
```

5. Query the most current USDT point:

```powershell
Invoke-RestMethod "http://localhost:8080/api/v1/data?assetId=USDT&dataSourceId=BINANCE&currentOnly=true&includeAttributes=true"
```

6. Query historical AAPL data:

```powershell
Invoke-RestMethod "http://localhost:8080/api/v1/data?assetId=AAPL&dataSourceId=YAHOO&startBusinessDate=2025-01-01&endBusinessDate=2026-01-01&includeAttributes=true&offset=0&limit=50&sort=desc"
```

## Important API Endpoints

- `GET /api/v1/assets?offset=0&limit=20`
- `GET /api/v1/assets/catalog?assetClass=Crypto&sortBy=assetId&direction=asc`
- `GET /api/v1/assets/{assetId}`
- `GET /api/v1/data-sources?offset=0&limit=20`
- `GET /api/v1/data-sources/{dataSourceId}`
- `GET /api/v1/data`
- `GET /api/v1/data/range`
- `POST /api/v1/ingest`
- `POST /api/v1/ingest/{assetSymbol}`
- `POST /api/v1/demo-data`
- `POST /api/v1/analytics/yearly-summary`
- `POST /api/v1/analytics/predict-next-close`
- `GET /api/v1/analytics/risk-signal`
- `GET /api/v1/analytics/compare`
- `POST /api/v1/analytics/spark/run`

## Spark Analytics

Run the Spark job after ingestion:

```powershell
Invoke-RestMethod -Method Post "http://localhost:8080/api/v1/analytics/spark/run?assetId=BTC&dataSourceId=BINANCE&startBusinessDate=2025-01-01&endBusinessDate=2026-01-01"
```

It writes:

- Yearly aggregations to `spark_analytics_summaries`
- Linear regression prediction results to `spark_prediction_results`

Spark uses these properties in `src/main/resources/application.properties`:

```properties
spark.master=local[*]
spark.mongodb.connection-string=mongodb://localhost:27017
spark.mongodb.database=test
```

## Presentation Script

1. Show the model: assets, data sources, and time-series points.
2. Explain temporal storage: market date is `businessDate`, version time is `systemDate`.
3. Run `POST /api/v1/ingest` and show inserted/skipped counts.
4. Query `/api/v1/data` and explain that only the newest version per business date is returned.
5. Show pagination and filters on `/assets/catalog` and `/data`.
6. Run the Spark endpoint and show the new MongoDB collections.
7. Mention that MCP/LLM integration is the only skipped bonus part.

## Troubleshooting

- If ingestion returns no rows, check your internet connection and try again later.
- If Binance blocks temporarily with rate limits, wait a few minutes and retry.
- If Swagger does not open, confirm the app is running and check whether you used port `8080` or `8091`.
- If Spark cannot connect to MongoDB, confirm MongoDB is running locally and the database name matches `spark.mongodb.database`.

##Demo Video 
https://github.com/nicolaecerga03-cpu/data-warehouse-project/releases/tag/v1.0
