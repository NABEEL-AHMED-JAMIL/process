# ETL Process Monitoring Setup

This guide explains how to set up and run the complete ETL Process with infrastructure, Kafka, and monitoring stack.

## Architecture Overview

The monitoring stack includes:
- **Prometheus**: Time-series database for metrics collection
- **Grafana**: Visualization and dashboarding
- **Node Exporter**: System metrics (CPU, Memory, Disk)
- **PostgreSQL Exporter**: Database metrics
- **Spring Boot Actuator**: Application metrics endpoint

## Quick Start

### Option 1: Run Everything Together (Recommended)

Start all services (Infrastructure + Monitoring + App) with one command:

```bash
cd /Users/nabeel.amd93/Desktop/Old-School/process

# Start all services
docker-compose -f docker-compose-all.yml up -d

# Wait 20-30 seconds for services to be ready
sleep 30
```

### Option 2: Run Infrastructure and Monitoring Separately

If you prefer to manage them separately:

```bash
# Terminal 1: Start infrastructure (PostgreSQL, Kafka, etc.)
docker-compose -f infrastructure/docker-compose-infrastructure.yml up -d

# Terminal 2: Start monitoring (Prometheus, Grafana, Exporters)
docker-compose -f monitoring/docker-compose-monitoring.yml up -d

# Wait for services to initialize
sleep 30
```

## Access the Services

### Database & Tools
- **PostgreSQL**: `localhost:5432`
  - Username: `postgres`
  - Password: `admin`
  - Database: `mydb`

- **PgAdmin** (Web UI): http://localhost:5050
  - Email: `admin@example.com`
  - Password: `admin`

- **Kafka UI**: http://localhost:8085

### Monitoring & Metrics
- **Prometheus**: http://localhost:9090
  - Query metrics from here
  - Check targets at http://localhost:9090/targets

- **Grafana**: http://localhost:3000
  - Username: `admin`
  - Password: `admin`
  - Pre-configured dashboard: "ETL Process Monitoring"
  - Datasource: Prometheus (auto-configured)

### Application
- **Spring Boot App**: http://localhost:9098/api/v1
- **Swagger UI**: http://localhost:9098/api/v1/swagger-ui.html
- **Actuator Health**: http://localhost:9098/api/v1/actuator/health
- **Prometheus Metrics**: http://localhost:9098/api/v1/actuator/prometheus

## Verify Setup

### Check if all containers are running:

```bash
docker ps
```

Expected output should show:
- postgres_db
- pgadmin
- zookeeper
- kafka
- kafka_ui
- prometheus
- grafana
- node_exporter
- postgres_exporter
- process_app (if running)

### Check Prometheus is scraping targets:

```bash
# Visit this URL in browser
http://localhost:9090/targets
```

All 4 targets should be "UP":
- prometheus
- node-exporter
- postgres-exporter
- spring-boot-app

### View metrics in Prometheus:

1. Go to http://localhost:9090
2. Click "Graph" tab
3. In the "Expression" field, type any metric like:
   - `process_uptime_seconds` - Spring Boot uptime
   - `pg_up` - PostgreSQL status
   - `node_cpu_seconds_total` - System CPU metrics

### Monitor in Grafana:

1. Go to http://localhost:3000
2. Login with `admin`/`admin`
3. Go to Dashboards → ETL Process Monitoring
4. View real-time metrics and alerts

## Available Metrics

### Spring Boot Application Metrics

- `http_requests_total` - Total HTTP requests
- `process_cpu_usage` - Application CPU usage
- `process_uptime_seconds` - Application uptime
- `jvm_memory_used_bytes` - JVM memory usage
- `tomcat_threads_current_threads` - Tomcat threads

### System Metrics (Node Exporter)

- `node_cpu_seconds_total` - CPU time
- `node_memory_MemAvailable_bytes` - Available memory
- `node_filesystem_avail_bytes` - Disk space available
- `node_load1` - System load

### Database Metrics (PostgreSQL Exporter)

- `pg_up` - PostgreSQL connection status
- `pg_stat_activity_count` - Active connections
- `pg_database_size_bytes` - Database size

## Viewing Alert Rules

All alert rules are defined in `monitoring/prometheus/rules.yml`. They monitor:

- High CPU usage (>80% for 5 minutes)
- High memory usage (>85% for 5 minutes)
- Low disk space (<20% remaining)
- PostgreSQL down
- Too many database connections (>80)

To view active alerts:
- Prometheus: http://localhost:9090/alerts
- Grafana: Dashboards → ETL Process Monitoring → Check status panels

## Stopping Services

### Stop all services:

```bash
# If using docker-compose-all.yml
docker-compose -f docker-compose-all.yml down

# If using separate files
docker-compose -f infrastructure/docker-compose-infrastructure.yml down
docker-compose -f monitoring/docker-compose-monitoring.yml down
```

### Keep data volumes (for restart):

```bash
docker-compose -f docker-compose-all.yml down
# Data persists in volumes, just restart with 'up -d'
```

### Clean everything (remove volumes):

```bash
docker-compose -f docker-compose-all.yml down -v
# Warning: All data will be deleted
```

## Troubleshooting

### PostgreSQL Exporter can't connect

Check the postgres_exporter logs:

```bash
docker logs postgres_exporter
```

Verify PostgreSQL is running:

```bash
docker exec -it postgres_db psql -U postgres -d mydb -c "SELECT 1"
```

### Prometheus not scraping metrics

1. Check Prometheus targets: http://localhost:9090/targets
2. Check Prometheus logs:
   ```bash
   docker logs prometheus
   ```
3. Verify app is exposing metrics:
   ```bash
   curl http://localhost:9098/api/v1/actuator/prometheus
   ```

### Grafana dashboard not showing data

1. Verify Prometheus datasource is working:
   - Go to http://localhost:3000
   - Settings → Data Sources → Prometheus → Test
2. Check if metrics exist in Prometheus:
   - Go to http://localhost:9090
   - Query a metric (e.g., `up`)

## Configuration Files

- **Prometheus Config**: `monitoring/prometheus/prometheus.yml`
- **Alert Rules**: `monitoring/prometheus/rules.yml`
- **Grafana Datasource**: `monitoring/grafana/provisioning/datasources/prometheus.yml`
- **Dashboard Definition**: `monitoring/grafana/provisioning/dashboards/process-dashboard.json`
- **Environment Variables**: `.env`

## Advanced Configuration

### Add custom metrics to Spring Boot

See Spring Boot Actuator documentation:
https://docs.spring.io/spring-boot/docs/2.7.x/reference/html/actuator.html

### Customize Prometheus scrape intervals

Edit `monitoring/prometheus/prometheus.yml` and restart:

```bash
docker-compose -f docker-compose-all.yml restart prometheus
```

### Create custom Grafana dashboard

1. Go to http://localhost:3000
2. Dashboards → Create New Dashboard
3. Add panels with Prometheus queries
4. Save the dashboard (exported JSON can be checked into version control)

## Production Considerations

⚠️ **Before deploying to production:**

1. Change default passwords in `.env`
2. Enable authentication/TLS for Prometheus and Grafana
3. Configure persistent storage for Prometheus data
4. Set up proper alerting (Slack, email, etc.)
5. Review and customize alert thresholds in `rules.yml`
6. Configure log aggregation
7. Set up backup strategy for database and metrics

## Support

For issues or questions:
- Check container logs: `docker logs <container_name>`
- Verify all containers are running: `docker ps`
- Check network connectivity: `docker network ls`
- Review configuration files in `monitoring/` directory


