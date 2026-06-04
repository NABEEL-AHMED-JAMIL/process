#!/bin/bash

# Docker Compose Helper Script
# This script provides convenient commands for managing Docker services

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Script directory
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

# Function to print colored output
print_info() {
    echo -e "${BLUE}ℹ️  INFO:${NC} $1"
}

print_success() {
    echo -e "${GREEN}✓ SUCCESS:${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}⚠️  WARNING:${NC} $1"
}

print_error() {
    echo -e "${RED}✗ ERROR:${NC} $1"
}

# Function to check if Docker is running
check_docker() {
    if ! command -v docker &> /dev/null; then
        print_error "Docker is not installed or not in PATH"
        exit 1
    fi

    if ! docker ps &> /dev/null; then
        print_error "Docker daemon is not running"
        exit 1
    fi

    print_success "Docker is running"
}

# Function to build the JAR
build_jar() {
    print_info "Building JAR file with Maven..."
    cd "$SCRIPT_DIR"

    if command -v mvn &> /dev/null; then
        mvn clean package -DskipTests
        print_success "JAR built successfully"
    else
        print_error "Maven is not installed. Please run: mvn clean package -DskipTests"
        exit 1
    fi
}

# Function to start services
start_services() {
    print_info "Starting Docker services..."
    cd "$SCRIPT_DIR"

    if [ ! -f "target/process-1.0-0.jar" ]; then
        print_warning "JAR file not found. Building..."
        build_jar
    fi

    docker-compose up -d
    print_success "Services started"

    print_info "Waiting for services to be healthy..."
    sleep 10

    print_info "Checking service status..."
    docker-compose ps

    echo ""
    print_success "Services are running!"
    echo ""
    echo -e "${YELLOW}Available endpoints:${NC}"
    echo "  API Base:        http://localhost:9098/api/v1"
    echo "  Swagger UI:      http://localhost:9098/api/v1/swagger-ui.html"
    echo "  Health Check:    http://localhost:9098/api/v1/actuator/health"
    echo "  Prometheus:      http://localhost:9098/api/v1/actuator/prometheus"
    echo ""
    echo -e "${YELLOW}Database:${NC}"
    echo "  Host: localhost:5432"
    echo "  User: nabeel.amd93"
    echo "  Password: admin"
    echo "  Database: etl_job"
    echo ""
    echo -e "${YELLOW}Kafka:${NC}"
    echo "  Bootstrap Server: localhost:9092"
    echo "  Zookeeper: localhost:2181"
}

# Function to stop services
stop_services() {
    print_info "Stopping Docker services..."
    cd "$SCRIPT_DIR"
    docker-compose down
    print_success "Services stopped"
}

# Function to stop and remove volumes
stop_services_clean() {
    print_warning "Stopping services and removing volumes (DATA LOSS)..."
    read -p "Are you sure? (y/n) " -n 1 -r
    echo
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        cd "$SCRIPT_DIR"
        docker-compose down -v
        print_success "Services stopped and volumes removed"
    else
        print_info "Cancelled"
    fi
}

# Function to view logs
view_logs() {
    cd "$SCRIPT_DIR"
    if [ "$1" == "" ]; then
        docker-compose logs -f
    else
        docker-compose logs -f "$1"
    fi
}

# Function to check status
check_status() {
    print_info "Checking service status..."
    cd "$SCRIPT_DIR"
    docker-compose ps

    echo ""
    print_info "Checking application health..."
    if curl -f http://localhost:9098/api/v1/actuator/health &> /dev/null; then
        print_success "Application is healthy"
    else
        print_warning "Application health check failed"
    fi
}

# Function to rebuild images
rebuild() {
    print_info "Rebuilding Docker images..."
    cd "$SCRIPT_DIR"
    docker-compose build --no-cache
    print_success "Images rebuilt"
}

# Function to clean up
cleanup() {
    print_info "Cleaning up Docker resources..."
    cd "$SCRIPT_DIR"

    print_info "Removing stopped containers..."
    docker container prune -f

    print_info "Removing dangling images..."
    docker image prune -f

    print_success "Cleanup completed"
}

# Function to show help
show_help() {
    cat << EOF
Docker Compose Helper Script

Usage: $0 {command}

Commands:
    start       Start all services (builds JAR if needed)
    stop        Stop services (keeps data)
    stop-clean  Stop services and remove volumes (DATA LOSS WARNING)
    rebuild     Rebuild Docker images
    status      Check service status and health
    logs        Show logs from all services
    logs-app    Show logs from application only
    logs-db     Show logs from PostgreSQL only
    logs-kafka  Show logs from Kafka only
    cleanup     Clean up Docker resources
    help        Show this help message

Examples:
    $0 start                # Start all services
    $0 logs                 # View all logs
    $0 logs-app             # View application logs only
    $0 status               # Check status
    $0 stop                 # Stop services
    $0 cleanup              # Clean up resources

Version: 1.0
EOF
}

# Main command dispatcher
main() {
    case "$1" in
        start)
            check_docker
            start_services
            ;;
        stop)
            stop_services
            ;;
        stop-clean)
            stop_services_clean
            ;;
        rebuild)
            check_docker
            rebuild
            ;;
        status)
            check_status
            ;;
        logs)
            view_logs
            ;;
        logs-app)
            view_logs "process_app"
            ;;
        logs-db)
            view_logs "postgres"
            ;;
        logs-kafka)
            view_logs "kafka"
            ;;
        cleanup)
            cleanup
            ;;
        help|--help|-h)
            show_help
            ;;
        *)
            print_error "Unknown command: $1"
            echo ""
            show_help
            exit 1
            ;;
    esac
}

# Run main function
main "$@"

