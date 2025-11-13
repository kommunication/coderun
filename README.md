# Distributed Remote Code Execution Engine

Send code, we will run it :)

Blog version: https://blog.rockthejvm.com/remote-code-execution-engine/ 

Video demo: https://www.youtube.com/watch?v=sMlJC7Kr330

Requirements for deploying locally:
- docker engine

Running locally (startup may be slow for the first time since it needs to pull a few docker images):
- clone the project and navigate to the root directory
- start the docker engine
- `chmod +x deploy.sh`
- `./deploy.sh`

In case you change code and want to run the new version you should execute:
- `./deploy.sh rebuild`

## Security & Authentication

**⚠️ IMPORTANT: Authentication is now required for all code execution requests!**

All code execution requests require an API key for authentication. There are three ways to provide your API key:

**HTTP Header (Recommended)**:
```bash
curl -X POST http://localhost:8080/lang/python \
  -H "X-API-Key: dev-key-12345" \
  -H "Content-Type: text/plain" \
  -d "print('Hello World')"
```

**Query Parameter**:
```bash
curl -X POST "http://localhost:8080/lang/python?api_key=dev-key-12345" \
  -H "Content-Type: text/plain" \
  -d "print('Hello World')"
```

### Default API Keys

For development and testing, the following API keys are available:
- `dev-key-12345` - Development key
- `prod-key-67890` - Production key
- `test-key-abcde` - Testing key

**Note**: In production, replace these with secure API keys stored in environment variables or a secrets manager.

### Rate Limiting

- **Default Limit**: 100 requests per hour per API key
- **Configuration**: Set `RATE_LIMIT_MAX_REQUESTS` environment variable to change the limit
- Rate limit information is returned in response headers:
  - `X-RateLimit-Remaining`: Number of requests remaining in current window
  - `X-RateLimit-Retry-After`: Seconds to wait before retrying (when rate limited)

### Input Validation

All code submissions are validated for:
- **Maximum code size**: 100 KB (bytes) or 50,000 characters
- **Language support**: Only supported languages are accepted
- **Security patterns**: Dangerous patterns (e.g., `rm -rf`, `wget`, `curl`) are blocked
- **Empty code**: Non-empty code is required

## Monitoring & Health Checks

The system exposes several monitoring endpoints (no authentication required):

### Health Check
```bash
curl http://localhost:8080/health
```
Returns `200 OK` with "healthy" if the service is running.

### Readiness Check
```bash
curl http://localhost:8080/ready
```
Returns cluster readiness status and member count.

### Prometheus Metrics
```bash
curl http://localhost:8080/metrics
```
Exposes Prometheus-compatible metrics including:
- `braindrill_requests_total` - Total requests by language and status
- `braindrill_execution_duration_seconds` - Execution duration histogram
- `braindrill_active_executions` - Currently active executions
- `braindrill_auth_failures_total` - Authentication failure count
- `braindrill_rate_limit_hits_total` - Rate limit violations
- `braindrill_validation_errors_total` - Input validation errors
- `braindrill_worker_pool_size` - Worker pool size
- JVM metrics (memory, GC, threads, etc.)

Example:
- sending `POST` request at `localhost:8080/lang/python` with API key
- attaching `python` code to request body

![My Image](assets/python_example.png)

Supported programming languages, HTTP paths and simple code snippets for request body, respectively:
- `Java` - `localhost:8080/lang/java`
```java
public class BrainDrill {
    public static void main(String[] args) {
        System.out.println("drill my brain");
    }
}
```

- `Python` - `localhost:8080/lang/python`
```python
print("drill my brain") 
```

- `Ruby` - `localhost:8080/lang/ruby`
```ruby
puts "drill my brain" 
```

- `Perl` - `localhost:8080/lang/perl`
```perl
print "drill my brain\n"; 
```

- `JavaScript` - `localhost:8080/lang/javascript`
```javascript
console.log("drill my brain");
```

- `PHP` - `localhost:8080/lang/php`
```javascript
<?php
echo "drill my brain";
?>
```

Architecture Diagram:

![My Image](assets/diagram.png)

## Recent Improvements (Phase 1: Security & Monitoring)

### ✅ Security Features
- **API Key Authentication**: All code execution endpoints now require authentication
- **Rate Limiting**: 100 requests/hour per API key (configurable)
- **Input Validation**: Code size limits, language validation, and dangerous pattern detection
- **Security Hardening**: Removed insecure `seccomp=unconfined` from Docker containers

### ✅ Monitoring & Observability
- **Prometheus Metrics**: Comprehensive metrics for requests, executions, errors, and system health
- **Health Checks**: `/health` and `/ready` endpoints for Kubernetes/load balancer integration
- **JVM Metrics**: Built-in monitoring of memory, GC, and thread pools
- **Request Tracking**: Duration histograms, success/failure rates, and active execution counts

### ✅ Configuration
- Rate limit configuration via `RATE_LIMIT_MAX_REQUESTS` environment variable
- Centralized security configuration in `application.conf`
- API keys configurable for different environments (dev/prod/test)

## Architecture Improvements

The updated architecture now includes:
1. **Authentication Layer**: API key validation before request processing
2. **Rate Limiter Actor**: Token bucket-based rate limiting per API key
3. **Input Validator**: Multi-stage validation (size, language, security patterns)
4. **Metrics Collection**: Real-time Prometheus metrics export
5. **Health Endpoints**: Kubernetes-ready health and readiness probes

TODO:
- add support for C, Go, Rust and others - ❌
- use other `pekko` libraries to make cluster bootstrapping and management flexible and configurable - ❌
- wrap the cluster in k8s and enable autoscaling - ❌
- implement async job execution with job queue system - ❌
- add multi-file project support and dependency management - ❌
