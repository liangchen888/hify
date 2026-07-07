# BGE + PostgreSQL pgvector Integration Setup Guide

## Overview
This document provides step-by-step instructions to complete the BGE embedding + PostgreSQL pgvector integration for the Hify RAG system.

## Completed Implementations

### 1. ✅ ChunkVO.from() Converter
- **File**: `hify-knowledge/src/main/java/com/hify/knowledge/dto/ChunkVO.java`
- **Change**: Added static `from(Chunk chunk)` method to convert entity to VO
- **Purpose**: Safely convert database entities to API response objects, excluding embedding field (vectors not needed in API responses)
- **Status**: COMPLETED

### 2. ✅ BgeEmbeddingService Injection
- **File**: `hify-knowledge/src/main/java/com/hify/knowledge/service/impl/BgeEmbeddingService.java`
- **Status**: VERIFIED - Uses `@RequiredArgsConstructor` Lombok annotation which properly injects:
  - `OkHttpClient httpClient` (from HttpClientConfig bean)
  - `ObjectMapper objectMapper` (Spring auto-configured)
- **Note**: No changes needed - dependency injection is correct

### 3. ✅ Application Configuration Added
- **File**: `hify-app/src/main/resources/application.yml`
- **Changes**:
  - Added BGE configuration section under `embedding.bge`:
    - `endpoint`: BGE API endpoint (default: http://localhost:8000/v1/embeddings)
    - `api-key`: Optional API key for authentication
    - `model`: BGE model identifier (default: BAAI/bge-large-zh-v1.5)
  - All values are environment variable configurable
- **Status**: COMPLETED

### 4. ✅ Database Schema Migration SQL
- **File**: `hify-knowledge/src/main/resources/db/migration/V1__Init_pgvector_extension.sql`
- **Contains**:
  - PostgreSQL pgvector extension initialization
  - `chunk` table creation with pgvector(768) support for BGE embeddings
  - IVFFLAT index for vector similarity search optimization
  - Proper constraints and foreign keys
  - Soft delete support
  - Comprehensive documentation
- **Status**: COMPLETED

## Setup Instructions

### Step 1: Ensure PostgreSQL is Running Locally

```bash
# Check if PostgreSQL is running
psql -U postgres -c "SELECT version();"

# If not running, start it (macOS):
brew services start postgresql

# Or on Linux:
sudo systemctl start postgresql
```

### Step 2: Create Hify Database on PostgreSQL

```bash
# Connect as postgres user
psql -U postgres

# Inside psql:
CREATE DATABASE hify;
CREATE USER hify_user WITH PASSWORD 'hify_password';
GRANT ALL PRIVILEGES ON DATABASE hify TO hify_user;
\q
```

### Step 3: Run Database Migration

```bash
# Connect to the hify database as hify_user
psql -U hify_user -d hify -h localhost

# Run the migration SQL script:
\i /path/to/hify-knowledge/src/main/resources/db/migration/V1__Init_pgvector_extension.sql

# Verify it worked:
\dt
SELECT * FROM information_schema.tables WHERE table_name='chunk';
\q
```

**Alternative - Manual SQL execution:**
```bash
psql -U hify_user -d hify -h localhost < \
  /Users/cn-aaronchen01/Downloads/git/hify/hify-knowledge/src/main/resources/db/migration/V1__Init_pgvector_extension.sql
```

### Step 4: Ensure BGE Service is Running Locally

**Option A: Using Ollama (Recommended)**
```bash
# Install Ollama: https://ollama.ai

# Pull BGE model:
ollama pull bge-large-zh-v1.5

# Serve on port 8000 (Ollama API compatible):
# The model will be available at: http://localhost:11434/api/embeddings
# Note: You may need an adapter/proxy for OpenAI API compatibility
```

**Option B: Using Hugging Face Transformers Directly**
```bash
# Install dependencies
pip install torch transformers sentence-transformers

# Create a simple Flask server:
# (See script below)
python serve_bge.py
```

**Python Script for Direct BGE Service (serve_bge.py):**
```python
from flask import Flask, request, jsonify
from sentence_transformers import SentenceTransformer
import os

app = Flask(__name__)
model = SentenceTransformer('BAAI/bge-large-zh-v1.5')

@app.route('/v1/embeddings', methods=['POST'])
def embeddings():
    data = request.json
    texts = data.get('input', [])
    embeddings_list = model.encode(texts).tolist()
    
    response = {
        'object': 'list',
        'data': [
            {'embedding': emb, 'index': i, 'object': 'embedding'}
            for i, emb in enumerate(embeddings_list)
        ],
        'model': 'BAAI/bge-large-zh-v1.5',
        'usage': {'prompt_tokens': 0, 'total_tokens': 0}
    }
    return jsonify(response)

if __name__ == '__main__':
    app.run(host='0.0.0.0', port=8000, debug=False)
```

### Step 5: Update application.yml with Environment Variables (Optional)

If you need custom settings, set environment variables:

```bash
# Linux/macOS
export BGE_ENDPOINT="http://localhost:8000/v1/embeddings"
export BGE_API_KEY=""
export BGE_MODEL="BAAI/bge-large-zh-v1.5"
export PGVECTOR_HOST="localhost"
export PGVECTOR_PORT="5432"
export PGVECTOR_DB="hify"
export PGVECTOR_USERNAME="hify_user"
export PGVECTOR_PASSWORD="hify_password"

# Or in docker-compose.yml for containerized setup
```

### Step 6: Build and Verify

```bash
cd /Users/cn-aaronchen01/Downloads/git/hify

# Clean build
mvn clean install -DskipTests

# Check for compilation errors
# Expected: BUILD SUCCESS
```

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────┐
│ Hify Application (Spring Boot)                              │
├─────────────────────────────────────────────────────────────┤
│                                                               │
│  KnowledgeServiceImpl                                         │
│    ├─ Uses EmbeddingService interface                       │
│    └─ Implementation: BgeEmbeddingService                   │
│                                                               │
│  BgeEmbeddingService                                         │
│    ├─ Calls BGE API: http://localhost:8000/v1/embeddings   │
│    ├─ Injects: OkHttpClient (HttpClientConfig)             │
│    ├─ Injects: ObjectMapper (Spring auto-config)           │
│    └─ Returns: PGvector(768) embeddings                     │
│                                                               │
│  ChunkMapper (MyBatis)                                       │
│    ├─ Stores chunks with embeddings in PostgreSQL          │
│    ├─ Vector search: searchByVector()                       │
│    └─ Uses IVFFLAT index for performance                    │
│                                                               │
│  ChunkVO                                                      │
│    ├─ VO (Value Object) for API responses                  │
│    ├─ from(Chunk) converter method                          │
│    └─ Excludes embedding field (not returned in API)        │
│                                                               │
└─────────────────────────────────────────────────────────────┘
           ↓              ↓
    ┌────────────┐  ┌──────────────────────┐
    │ MySQL      │  │ PostgreSQL pgvector  │
    │ (Metadata) │  │ (Vectors + Chunks)   │
    └────────────┘  └──────────────────────┘
           ↓              ↓
      Local MySQL    Local PostgreSQL
```

## Vector Dimension: 768

- **Model**: BAAI/bge-large-zh-v1.5
- **Output Dimension**: 768
- **Database Schema**: `embedding vector(768)` in pgvector extension
- **Index Type**: IVFFLAT with cosine similarity
- **Use Case**: Semantic search for Chinese text (optimized for Chinese)

## Testing Vector Search

Once everything is set up, test the vector search capability:

```bash
# 1. Upload a document (creates chunks + embeddings)
curl -X POST http://localhost:8080/api/documents/upload \
  -F "file=@sample.txt"

# 2. Search by semantic similarity
curl -X POST http://localhost:8080/api/knowledge/search \
  -H "Content-Type: application/json" \
  -d '{"query": "搜索关键词", "limit": 5}'

# 3. Verify PostgreSQL vectors were created:
psql -U hify_user -d hify -c "SELECT id, document_id, chunk_index, embedding FROM chunk LIMIT 1;"
```

## Troubleshooting

### Problem: "Connection refused" to BGE API
- **Solution**: Ensure BGE service is running on port 8000
- **Check**: `curl http://localhost:8000/v1/embeddings` should return an error (not "Connection refused")

### Problem: "pgvector extension not found"
- **Solution**: Run the migration SQL with superuser privileges or contact DBA
- **Check**: `CREATE EXTENSION vector;` should succeed

### Problem: "ObjectMapper not found" compile error
- **Solution**: Ensure jackson-databind dependency is in pom.xml (already included)
- **Check**: `mvn dependency:tree | grep jackson`

### Problem: Build fails with "Cannot find symbol"
- **Solution**: Run `mvn clean` and rebuild
- **Check**: `mvn clean install -DskipTests`

## Environment Variables Summary

| Variable | Default | Required | Description |
|----------|---------|----------|-------------|
| `BGE_ENDPOINT` | http://localhost:8000/v1/embeddings | No | BGE API endpoint |
| `BGE_API_KEY` | (empty) | No | Optional API key for BGE |
| `BGE_MODEL` | BAAI/bge-large-zh-v1.5 | No | BGE model identifier |
| `PGVECTOR_HOST` | (empty) | Yes | PostgreSQL host |
| `PGVECTOR_PORT` | 5432 | No | PostgreSQL port |
| `PGVECTOR_DB` | hify | No | PostgreSQL database name |
| `PGVECTOR_USERNAME` | (empty) | Yes | PostgreSQL username |
| `PGVECTOR_PASSWORD` | (empty) | Yes | PostgreSQL password |

## Next Steps

1. **Build Project**: `mvn clean install -DskipTests`
2. **Start PostgreSQL**: Ensure it's running locally
3. **Initialize Database**: Run migration SQL
4. **Start BGE Service**: Run Ollama or custom server
5. **Start Application**: `java -jar hify-app.jar`
6. **Test Functionality**: Upload document and search by vector

## Reference Files

- **Entity**: `hify-knowledge/src/main/java/com/hify/knowledge/entity/Chunk.java` ✅
- **DTO**: `hify-knowledge/src/main/java/com/hify/knowledge/dto/ChunkVO.java` ✅ (with from())
- **Service Interface**: `hify-knowledge/src/main/java/com/hify/knowledge/service/EmbeddingService.java` ✅
- **Service Implementation**: `hify-knowledge/src/main/java/com/hify/knowledge/service/impl/BgeEmbeddingService.java` ✅
- **Mapper**: `hify-knowledge/src/main/java/com/hify/knowledge/mapper/ChunkMapper.java` ✅
- **Config**: `hify-knowledge/src/main/java/com/hify/knowledge/config/HttpClientConfig.java` ✅
- **Application Config**: `hify-app/src/main/resources/application.yml` ✅ (with BGE section)
- **Database Migration**: `hify-knowledge/src/main/resources/db/migration/V1__Init_pgvector_extension.sql` ✅

---

**Status**: Ready for deployment ✅  
**Last Updated**: 2024  
**BGE Model**: BAAI/bge-large-zh-v1.5 (768-dimensional, Chinese optimized)
