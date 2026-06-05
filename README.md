# FieldFinder Backend

[![Java](https://img.shields.io/badge/Java-22-orange?logo=openjdk&logoColor=white)](https://www.oracle.com/java/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x-6DB33F?logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![MySQL](https://img.shields.io/badge/MySQL-8.0-4479A1?logo=mysql&logoColor=white)](https://www.mysql.com/)
[![MongoDB](https://img.shields.io/badge/MongoDB-7-47A248?logo=mongodb&logoColor=white)](https://www.mongodb.com/)
[![Redis](https://img.shields.io/badge/Redis-7-DC382D?logo=redis&logoColor=white)](https://redis.io/)
[![RabbitMQ](https://img.shields.io/badge/RabbitMQ-3-FF6600?logo=rabbitmq&logoColor=white)](https://www.rabbitmq.com/)
[![Docker](https://img.shields.io/badge/Docker-compose-2496ED?logo=docker&logoColor=white)](https://www.docker.com/)
[![CI/CD](https://img.shields.io/badge/CI%2FCD-GitHub%20Actions-2088FF?logo=githubactions&logoColor=white)](https://github.com/features/actions)

> Smart sports field booking platform with AI-powered chatbot, image search, and personalized product recommendations.

---

## Table of Contents

- [Overview](#overview)
- [Key Features](#key-features)
- [Architecture](#architecture)
- [Tech Stack](#tech-stack)
- [Getting Started](#getting-started)
- [Project Structure](#project-structure)
- [API Documentation](#api-documentation)
- [Deployment](#deployment)
- [Contributors](#contributors)

---

## Overview

FieldFinder is a sports ecosystem mobile app combining **football pitch booking** and **sports equipment e-commerce** with an AI-powered conversational interface. This repository contains the Spring Boot backend serving REST APIs to the Flutter mobile client and orchestrating an ML microservice for image search and personalized recommendations.

## Key Features

- **AI Chatbot** — Intent-driven chat powered by Gemini 2.0 Flash, supporting 19+ actions (booking, product search, image search, weather queries, recommendations).
- **Multi-stage Image Search** — Perceptual Hash for exact match → Hybrid CLIP+Text+Tag retrieval → Gemini Vision fallback.
- **Composite Ranking Engine** — 4-tier degrade chain scoring 6 signals (product type, activity, brand preference, gender, ML score, text relevance) for personalized product search results.
- **Personalized Recommendations** — Real-time SASRec + DeepFM rerank from MongoDB live event stream with 60s TTL cache.
- **Async Event Logging** — RabbitMQ + MongoDB pipeline capturing 30,000+ user interactions for ML training data without blocking user requests.
- **Resilient ML Integration** — Circuit breaker pattern (5 consecutive failures → 60s cool-down, automatic fallback to local vector search).
- **Authentication** — Firebase Auth + JWT + Passkey (WebAuthn) + Social login (Google/Facebook).
- **Payment & Booking** — VNPay integration, slot-based pitch booking, cart + order workflow with discount voucher system.

## Architecture

```
┌─────────────────┐         ┌──────────────────┐         ┌─────────────────┐
│  Flutter App    │ ──────▶ │  Spring Boot BE  │ ──────▶ │  FastAPI ML     │
│  (Mobile)       │  REST   │  Port 8080       │  REST   │  Port 8000      │
└─────────────────┘         └──────────────────┘         └─────────────────┘
                                     │                            │
                            ┌────────┼────────┐                   │
                            ▼        ▼        ▼                   ▼
                       ┌────────┐ ┌──────┐ ┌───────┐    ┌──────────────────┐
                       │ MySQL  │ │Mongo │ │ Redis │    │ FAISS Indices    │
                       │Catalog │ │Logs  │ │Cache  │    │ + PyTorch Models │
                       └────────┘ └──────┘ └───────┘    └──────────────────┘
                                     ▲
                                     │
                            ┌────────┴────────┐
                            │    RabbitMQ     │
                            │  Async Logger   │
                            └─────────────────┘
```

**Microservice separation:** Backend (Java/Spring) handles business logic, auth, and CRUD. ML service (Python/FastAPI) handles model inference, FAISS retrieval, and vector embeddings. Communication via `WebClient` with timeout + retry + circuit breaker.

## Tech Stack

| Layer | Technologies |
|-------|--------------|
| **Backend** | Java 22, Spring Boot 3.x, Spring Security, Spring Data JPA, WebClient |
| **Persistence** | MySQL 8 (transactional), MongoDB 7 (event logs), Redis 7 (cache + session) |
| **Messaging** | RabbitMQ 3 (async event publishing) |
| **AI / ML** | Gemini 2.0 Flash API, ML Microservice (Python + FastAPI + PyTorch) |
| **Auth** | Firebase Authentication, JWT, Passkey (WebAuthn), OAuth2 |
| **Payments** | VNPay |
| **Storage** | Cloudinary (images), Local file system |
| **DevOps** | Docker, Docker Compose, GitHub Actions, AWS EC2, Cloudflare Tunnel |
| **Monitoring** | Spring Boot Actuator, structured logging |

## Getting Started

### Prerequisites

- Java 22+
- Maven 3.9+
- Docker + Docker Compose
- MySQL 8, MongoDB 7, Redis 7 (or use Docker Compose to spin up)

### Environment Variables

Create `.env` at project root:

```env
# Database
MYSQL_HOST=localhost
MYSQL_PORT=3306
MYSQL_DB=fieldfinder
MYSQL_USER=root
MYSQL_PASSWORD=...

# MongoDB
MONGO_URI=mongodb+srv://...

# Redis
SPRING_DATA_REDIS_HOST=localhost
SPRING_DATA_REDIS_PORT=6379

# RabbitMQ
SPRING_RABBITMQ_HOST=localhost

# External APIs
GOOGLE_API_KEY=...
FIREBASE_CONFIG=...   # base64-encoded service account JSON
CLOUDINARY_CLOUD_NAME=...
CLOUDINARY_API_KEY=...
CLOUDINARY_API_SECRET=...
VNPAY_TMN_CODE=...
VNPAY_HASH_SECRET=...

# ML Service
ML_API_BASE_URL=http://localhost:8000
ML_API_ENABLED=true
ML_API_TIMEOUT_MS=60000
```

### Run Locally

```bash
# 1. Start dependencies (MySQL/Mongo/Redis/RabbitMQ + ML)
docker compose up -d redis-master rabbitmq mongo ml

# 2. Build and run BE
./mvnw clean package -DskipTests
./mvnw spring-boot:run

# Or full stack via Docker
docker compose up -d --build
```

Backend will be available at `http://localhost:8080`.

API documentation (Swagger UI): `http://localhost:8080/swagger-ui/index.html`

### Live tracking — OSRM routing (optional)

Live shipper tracking vẽ tuyến đường + snap marker bám đường qua OSRM self-host
(miễn phí, **không** dùng traffic; ETA tĩnh). Tiền xử lý bản đồ **1 lần** trước khi `up`:

```bash
mkdir -p osrm-data
# 1. Tải bản đồ Việt Nam (Geofabrik, ~400MB)
curl -L -o osrm-data/vietnam-latest.osm.pbf \
  https://download.geofabrik.de/asia/vietnam-latest.osm.pbf

# 2. Tiền xử lý MLD pipeline (chạy 1 lần; mất vài phút)
docker run -t -v "${PWD}/osrm-data:/data" osrm/osrm-backend \
  osrm-extract -p /opt/car.lua /data/vietnam-latest.osm.pbf
docker run -t -v "${PWD}/osrm-data:/data" osrm/osrm-backend \
  osrm-partition /data/vietnam-latest.osrm
docker run -t -v "${PWD}/osrm-data:/data" osrm/osrm-backend \
  osrm-customize /data/vietnam-latest.osrm

# 3. Chạy OSRM
docker compose up -d osrm
# Kiểm tra: curl "http://localhost:5000/route/v1/driving/106.70,10.77;106.69,10.78?overview=false"
```

Tắt tính năng (BE tự fallback nội suy đường thẳng) bằng `OSRM_ENABLED=false`.
Endpoint: `GET /api/orders/{id}/route?fromLat=&fromLng=&toLat=&toLng=` → `{ geometry, distanceMeters, durationSeconds }` (polyline mã hoá precision-5), hoặc `204` khi OSRM tắt/lỗi.

## Project Structure

```
FieldFinder_BE/
├── src/main/java/com/example/FieldFinder/
│   ├── ai/                    # AI Chat handler + Gemini integration + ranking
│   │   ├── AIChat.java        # Main chat orchestrator
│   │   └── ranking/           # CompositeRanker + RankingContext
│   ├── aspect/                # @Aspect for user activity logging
│   ├── config/                # Spring config (Security, RabbitMQ, Redis, ...)
│   ├── controller/            # REST endpoints
│   ├── dto/                   # Request/Response DTOs
│   ├── entity/                # JPA entities + MongoDB documents
│   ├── repository/            # Spring Data repositories
│   ├── service/               # Business logic + impls
│   │   ├── impl/
│   │   └── log/               # Async log publisher + Rabbit consumer
│   └── util/                  # PhashUtil, JwtUtil, ...
├── ml/                        # Python ML microservice (FAISS + PyTorch)
├── docker-compose.yml         # Full stack orchestration
├── Dockerfile                 # BE image build
└── .github/workflows/         # CI/CD pipeline
```

## API Documentation

| Category | Endpoint Examples |
|----------|-------------------|
| **Auth** | `POST /api/auth/login`, `POST /api/auth/register`, `POST /api/auth/passkey/register` |
| **AI Chat** | `POST /api/chatbot/{sessionId}`, `POST /api/chatbot/image/{sessionId}` |
| **Pitch** | `GET /api/pitches`, `POST /api/bookings` |
| **Product** | `GET /api/products`, `POST /api/cart`, `POST /api/orders` |
| **User** | `GET /api/users/{id}`, `PUT /api/users/{id}` |
| **Payment** | `POST /api/vnpay/create-payment`, `GET /api/vnpay/return` |

Full reference via Swagger UI when running locally.

## Deployment

The project uses **GitHub Actions** for CI/CD:

1. Push to `main` triggers parallel builds: `build-be` (Spring Boot image) and `build-ml` (FastAPI image).
2. Both images pushed to **Docker Hub** (`minhtriet004/fieldfinder-be`, `minhtriet004/fieldfinder-ml`).
3. SSH to **AWS EC2** (t3.large) → pull latest images → `docker compose up -d`.
4. **Cloudflare Tunnel** exposes the service securely without opening additional inbound ports.

Workflow file: [.github/workflows/deploy.yml](.github/workflows/deploy.yml)

## Contributors

| Student ID | Name | Role |
|-----------|------|------|
| 22521530 | Huỳnh Minh Triết | Backend, AI Integration, ML Pipeline, DevOps |
| 22521529 | Vũ Hoàng Trọng Trí | Mobile (Flutter), UI/UX |

---

**Academic context:** This is the backend for our Bachelor's thesis project at UIT (University of Information Technology, VNU-HCM), 2026.
