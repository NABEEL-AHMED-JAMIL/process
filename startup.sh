#!/bin/bash

# ETL Process - Complete Docker Compose Startup Script
# Usage: ./startup.sh [all|infrastructure|monitoring|boot]

set -e

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$PROJECT_DIR"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Default action
ACTION="${1:-all}"

print_header() {
    echo -e "\n${BLUE}================================${NC}"
    echo -e "${BLUE}$1${NC}"
    echo -e "${BLUE}================================${NC}\n"
}

print_success() {
    echo -e "${GREEN}✓ $1${NC}"
}

print_error() {
    echo -e "${RED}✗ $1${NC}"
}

print_info() {
    echo -e "${YELLOW}→ $1${NC}"
}

check_env_file() {
    if [ ! -f ".env" ]; then
        print_error ".env file not found!"
        echo "Please create .env file with required environment variables."
        exit 1
    fi
    print_success ".env file found"
}

check_docker() {
    if ! command -v docker &> /dev/null; then
        print_error "Docker is not installed!"
        exit 1
    fi
    print_success "Docker is installed"

    if ! docker ps &> /dev/null; then
        print_error "Docker daemon is not running!"
        exit 1
    fi
    print_success "Docker daemon is running"
}

start_all() {
    print_header "Starting ALL Services (Infrastructure + Monitoring)"

    docker-compose -f docker-compose-all.yml up -d

    print_success "All services started successfully!"
    print_info "Waiting for services to initialize... (30 seconds)"
    sleep 30

    print_header "Service Status"
    docker-compose -f docker-compose-all.yml ps

    print_header "Access URLs"
    echo -e "PostgreSQL:        ${GREEN}localhost:5432${NC} (postgres/admin)"
    echo -e "PgAdmin:           ${GREEN}http://localhost:5050${NC} (admin@example.com/admin)"
    echo -e "Kafka:             ${GREEN}localhost:9092${NC}"
    echo -e "Kafka UI:          ${GREEN}http://localhost:8085${NC}"
    echo -e "Prometheus:        ${GREEN}http://localhost:9090${NC}"
    echo -e "Grafana:           ${GREEN}http://localhost:3000${NC} (admin/admin)"
    echo -e "Spring Boot App:   ${GREEN}http://localhost:9098/api/v1${NC}"
    echo ""
}

start_infrastructure() {
    print_header "Starting Infrastructure Services"

    docker-compose -f infrastructure/docker-compose-infrastructure.yml up -d

    print_success "Infrastructure services started successfully!"
    print_info "Waiting for PostgreSQL to be ready... (15 seconds)"
    sleep 15

    print_header "Service Status"
    docker-compose -f infrastructure/docker-compose-infrastructure.yml ps
}

start_monitoring() {
    print_header "Starting Monitoring Services"

    docker-compose -f monitoring/docker-compose-monitoring.yml up -d

    print_success "Monitoring services started successfully!"
    print_info "Waiting for monitoring stack to initialize... (10 seconds)"
    sleep 10

    print_header "Service Status"
    docker-compose -f monitoring/docker-compose-monitoring.yml ps

    print_header "Access Monitoring"
    echo -e "Prometheus:        ${GREEN}http://localhost:9090${NC}"
    echo -e "Grafana:           ${GREEN}http://localhost:3000${NC} (admin/admin)"
}

start_boot() {
    print_header "Starting Spring Boot Application"

    docker-compose -f docker-compose-boot.yml up -d

    print_success "Spring Boot application started successfully!"

    print_header "Access Application"
    echo -e "Spring Boot App:   ${GREEN}http://localhost:9098/api/v1${NC}"
    echo -e "Swagger UI:        ${GREEN}http://localhost:9098/api/v1/swagger-ui.html${NC}"
    echo -e "Health Check:      ${GREEN}http://localhost:9098/api/v1/actuator/health${NC}"
    echo -e "Metrics:           ${GREEN}http://localhost:9098/api/v1/actuator/prometheus${NC}"
}

stop_all() {
    print_header "Stopping ALL Services"

    if docker-compose -f docker-compose-all.yml ps 2>/dev/null | grep -q 'running'; then
        docker-compose -f docker-compose-all.yml down
        print_success "All services stopped"
    else
        print_info "No services running"
    fi
}

status() {
    print_header "System Status"

    if docker-compose -f docker-compose-all.yml ps 2>/dev/null | grep -q 'running'; then
        print_success "Services are running:"
        docker-compose -f docker-compose-all.yml ps
    else
        print_info "No services are running"
        echo "Run './startup.sh start' to start services"
    fi
}

logs_all() {
    print_header "Showing Logs (docker-compose-all.yml)"
    docker-compose -f docker-compose-all.yml logs -f
}

logs_service() {
    SERVICE="$2"
    if [ -z "$SERVICE" ]; then
        print_error "Please specify service name"
        echo "Usage: ./startup.sh logs <service_name>"
        exit 1
    fi
    docker-compose -f docker-compose-all.yml logs -f "$SERVICE"
}

health_check() {
    print_header "Health Checks"

    print_info "Checking PostgreSQL..."
    if docker exec -it postgres_db pg_isready -U postgres &> /dev/null; then
        print_success "PostgreSQL is healthy"
    else
        print_error "PostgreSQL is not responding"
    fi

    print_info "Checking Prometheus..."
    if curl -s http://localhost:9090/-/healthy &> /dev/null; then
        print_success "Prometheus is healthy"
    else
        print_error "Prometheus is not responding"
    fi

    print_info "Checking Grafana..."
    if curl -s http://localhost:3000/api/health &> /dev/null; then
        print_success "Grafana is healthy"
    else
        print_error "Grafana is not responding"
    fi

    print_info "Checking Spring Boot App..."
    if curl -s http://localhost:9098/api/v1/actuator/health &> /dev/null; then
        print_success "Spring Boot App is healthy"
    else
        print_error "Spring Boot App is not responding"
    fi
}

# Main logic
case "$ACTION" in
    all)
        check_env_file
        check_docker
        start_all
        ;;
    infrastructure)
        check_env_file
        check_docker
        start_infrastructure
        ;;
    monitoring)
        check_env_file
        check_docker
        start_monitoring
        ;;
    boot)
        check_env_file
        check_docker
        start_boot
        ;;
    stop)
        stop_all
        ;;
    status)
        status
        ;;
    logs)
        logs_all
        ;;
    logs:*)
        SERVICE="${ACTION#logs:}"
        logs_service "$ACTION" "$SERVICE"
        ;;
    health)
        health_check
        ;;
    *)
        echo "ETL Process - Docker Compose Management Script"
        echo ""
        echo "Usage: $0 [command]"
        echo ""
        echo "Commands:"
        echo "  all             Start all services (infrastructure + monitoring)"
        echo "  infrastructure  Start infrastructure only (PostgreSQL, Kafka, etc.)"
        echo "  monitoring      Start monitoring only (Prometheus, Grafana, Exporters)"
        echo "  boot            Start Spring Boot application"
        echo "  stop            Stop all services"
        echo "  status          Show status of all services"
        echo "  logs            Show logs from all services (tail -f)"
        echo "  logs:<service>  Show logs from specific service"
        echo "  health          Check health of all services"
        echo ""
        echo "Examples:"
        echo "  ./startup.sh                    # Start all services"
        echo "  ./startup.sh infrastructure     # Start only infrastructure"
        echo "  ./startup.sh monitoring         # Start only monitoring"
        echo "  ./startup.sh stop               # Stop all services"
        echo "  ./startup.sh logs               # Show all logs"
        echo "  ./startup.sh logs:prometheus    # Show prometheus logs"
        echo "  ./startup.sh health             # Health check"
        exit 1
        ;;
esac


