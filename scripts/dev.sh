#!/bin/bash
# Development run script for Budgeteer
# Usage: ./scripts/dev.sh [command]
#
# Commands:
#   start   - Start the application (default)
#   stop    - Stop the application
#   status  - Check if application is running
#   restart - Stop and start the application
#   logs    - Show recent logs (if running in background)
#   db      - Start only the database
#   test    - Run unit tests
#   it      - Run integration tests (requires Docker)
#   test-all - Run all tests (unit + integration)
#   tunnel  - Start ngrok tunnel for Monzo OAuth
#   help    - Show this help

set -e

# Configuration
APP_PORT=8080
APP_NAME="budgeteer"
PID_FILE="/tmp/budgeteer.pid"

# Get the project root (parent of scripts/)
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

cd "$PROJECT_ROOT"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Helper functions
print_status() { echo -e "${BLUE}ℹ️  $1${NC}"; }
print_success() { echo -e "${GREEN}✅ $1${NC}"; }
print_warning() { echo -e "${YELLOW}⚠️  $1${NC}"; }
print_error() { echo -e "${RED}❌ $1${NC}"; }

# Check if port is in use
is_port_in_use() {
    lsof -i :$APP_PORT > /dev/null 2>&1
}

# Get PID of process using port
get_port_pid() {
    lsof -ti :$APP_PORT 2>/dev/null
}

# Kill process on port with confirmation
kill_port_process() {
    local pid=$(get_port_pid)
    if [ -n "$pid" ]; then
        local process_name=$(ps -p $pid -o comm= 2>/dev/null || echo "unknown")
        print_warning "Port $APP_PORT is in use by process: $process_name (PID: $pid)"
        echo -n "Kill this process? [y/N]: "
        read -r response
        if [[ "$response" =~ ^[Yy]$ ]]; then
            kill -9 $pid 2>/dev/null
            sleep 1
            print_success "Process killed"
            return 0
        else
            print_error "Cannot start - port $APP_PORT is in use"
            return 1
        fi
    fi
    return 0
}

# Load environment
load_env() {
    # Load SDKMAN if available
    if [ -f "$HOME/.sdkman/bin/sdkman-init.sh" ]; then
        source "$HOME/.sdkman/bin/sdkman-init.sh"
    fi

    # Check Java version
    JAVA_VERSION=$(java -version 2>&1 | head -1 | cut -d'"' -f2 | cut -d'.' -f1)
    echo "☕ Java version: $JAVA_VERSION"
    if [ "$JAVA_VERSION" -lt 21 ]; then
        print_error "Java 21 or higher required. Current: $JAVA_VERSION"
        exit 1
    fi

    # Load .env file if it exists
    if [ -f .env ]; then
        print_status "Loading environment from .env file..."
        while IFS= read -r line || [[ -n "$line" ]]; do
            if [[ -n "$line" && ! "$line" =~ ^[[:space:]]*# ]]; then
                if [[ "$line" =~ ^[A-Za-z_][A-Za-z0-9_]*= ]]; then
                    export "$line"
                fi
            fi
        done < .env
    else
        print_warning "No .env file found. Copy .env.example to .env and fill in your values."
        echo "   cp .env.example .env"
        exit 1
    fi

    # Check required environment variables
    if [ -z "$MONZO_CLIENT_ID" ]; then
        print_error "MONZO_CLIENT_ID is not set"
        exit 1
    fi

    if [ -z "$MONZO_CLIENT_SECRET" ]; then
        print_error "MONZO_CLIENT_SECRET is not set"
        exit 1
    fi

    if [ -z "$MONZO_REDIRECT_URI" ]; then
        print_error "MONZO_REDIRECT_URI is not set"
        exit 1
    fi

    if [ -z "$JWE_SECRET_KEY" ]; then
        print_warning "JWE_SECRET_KEY is not set. Auth will not work."
        echo "   Generate one with: openssl rand -base64 32"
    fi

    if [ -z "$MONZO_ENCRYPTION_KEY" ]; then
        print_warning "MONZO_ENCRYPTION_KEY is not set. Token encryption will not work."
        echo "   Generate one with: openssl rand -base64 32"
    fi

    print_success "Environment loaded"
    echo "   Client ID: ${MONZO_CLIENT_ID:0:30}..."
    echo "   Redirect URI: $MONZO_REDIRECT_URI"
    echo "   JWE Key: ${JWE_SECRET_KEY:+configured}"
    echo "   Encryption Key: ${MONZO_ENCRYPTION_KEY:+configured}"
    echo ""
}

# Ensure database is running
ensure_db() {
    if ! docker info > /dev/null 2>&1; then
        print_error "Docker doesn't seem to be running. Start Docker Desktop first."
        exit 1
    fi

    if ! docker ps | grep -q budgeteer-postgres; then
        print_status "Starting PostgreSQL database..."
        docker compose up -d
        echo "⏳ Waiting for database to be ready..."
        sleep 3
    else
        print_success "Database already running"
    fi
}

# Commands
cmd_start() {
    load_env
    ensure_db

    # Check if port is in use
    if is_port_in_use; then
        kill_port_process || exit 1
    fi

    print_status "Starting Budgeteer backend on port $APP_PORT..."
    echo ""

    cd "$PROJECT_ROOT/backend"
    mvn spring-boot:run
}

cmd_stop() {
    print_status "Stopping Budgeteer..."
    
    if is_port_in_use; then
        local pid=$(get_port_pid)
        if [ -n "$pid" ]; then
            kill $pid 2>/dev/null
            sleep 2
            # Force kill if still running
            if is_port_in_use; then
                kill -9 $pid 2>/dev/null
            fi
            print_success "Application stopped"
        fi
    else
        print_warning "Application is not running"
    fi
}

cmd_status() {
    echo ""
    echo "╔══════════════════════════════════════════╗"
    echo "║         Budgeteer Status                 ║"
    echo "╚══════════════════════════════════════════╝"
    echo ""

    # Check app
    if is_port_in_use; then
        local pid=$(get_port_pid)
        print_success "Application: RUNNING (PID: $pid, Port: $APP_PORT)"
        
        # Try to hit health endpoint
        if curl -s "http://localhost:$APP_PORT/actuator/health" > /dev/null 2>&1; then
            local health=$(curl -s "http://localhost:$APP_PORT/actuator/health" | grep -o '"status":"[^"]*"' | head -1)
            echo "   Health: $health"
        fi
    else
        print_warning "Application: STOPPED"
    fi

    # Check database
    if docker ps | grep -q budgeteer-postgres; then
        print_success "Database: RUNNING"
    else
        print_warning "Database: STOPPED"
    fi

    echo ""
}

cmd_restart() {
    cmd_stop
    sleep 1
    cmd_start
}

cmd_db() {
    if ! docker info > /dev/null 2>&1; then
        print_error "Docker doesn't seem to be running. Start Docker Desktop first."
        exit 1
    fi

    print_status "Starting PostgreSQL database..."
    docker compose up -d
    print_success "Database started"
}

cmd_test() {
    print_status "Running unit tests..."
    cd "$PROJECT_ROOT/backend"
    mvn test -DskipITs
    print_success "Unit tests completed"
}

cmd_it() {
    local test_pattern=${1:-"*IT"}
    
    if ! docker info > /dev/null 2>&1; then
        print_error "Docker doesn't seem to be running. Start Docker Desktop first."
        print_warning "Integration tests require Docker for Testcontainers (PostgreSQL)"
        exit 1
    fi

    print_status "Running integration tests: $test_pattern"
    echo "   ℹ️  This requires Docker - Testcontainers will spin up PostgreSQL"
    echo ""
    
    cd "$PROJECT_ROOT/backend"
    mvn test -Dtest="$test_pattern"
    print_success "Integration tests completed"
}

cmd_test_all() {
    if ! docker info > /dev/null 2>&1; then
        print_error "Docker doesn't seem to be running. Start Docker Desktop first."
        exit 1
    fi

    print_status "Running all tests (unit + integration)..."
    cd "$PROJECT_ROOT/backend"
    mvn verify
    print_success "All tests completed"
}

cmd_tunnel() {
    # Check if ngrok is installed
    if ! command -v ngrok &> /dev/null; then
        print_error "ngrok is not installed!"
        echo ""
        echo "Install ngrok:"
        echo "  brew install ngrok       # macOS"
        echo "  snap install ngrok       # Linux"
        echo ""
        echo "Then authenticate:"
        echo "  ngrok config add-authtoken <your-token>"
        echo ""
        echo "Get your token at: https://dashboard.ngrok.com/get-started/your-authtoken"
        exit 1
    fi

    print_status "Starting ngrok tunnel to port $APP_PORT..."
    echo ""
    echo "╔══════════════════════════════════════════════════════════════════╗"
    echo "║  🚇 NGROK TUNNEL FOR MONZO OAUTH                                ║"
    echo "╠══════════════════════════════════════════════════════════════════╣"
    echo "║                                                                  ║"
    echo "║  After ngrok starts, copy the HTTPS URL and:                     ║"
    echo "║                                                                  ║"
    echo "║  1. Go to: https://developers.monzo.com/                         ║"
    echo "║  2. Update your OAuth redirect URI to:                           ║"
    echo "║     https://YOUR-NGROK-URL.ngrok-free.app/api/monzo/callback     ║"
    echo "║                                                                  ║"
    echo "║  3. Update your .env file:                                       ║"
    echo "║     MONZO_REDIRECT_URI=https://YOUR-NGROK-URL.ngrok-free.app/api/monzo/callback"
    echo "║                                                                  ║"
    echo "║  4. Restart the app: ./scripts/dev.sh restart                    ║"
    echo "║                                                                  ║"
    echo "║  Press Ctrl+C to stop the tunnel                                 ║"
    echo "╚══════════════════════════════════════════════════════════════════╝"
    echo ""
    
    # Start ngrok
    ngrok http $APP_PORT
}

cmd_help() {
    echo ""
    echo "Budgeteer Development Script"
    echo ""
    echo "Usage: ./scripts/dev.sh [command]"
    echo ""
    echo "Commands:"
    echo "  start       Start the application (default)"
    echo "  stop        Stop the application"
    echo "  status      Check application and database status"
    echo "  restart     Stop and start the application"
    echo "  db          Start only the database"
    echo "  tunnel      Start ngrok tunnel (for Monzo OAuth)"
    echo "  test        Run unit tests (no Docker required)"
    echo "  it [name]   Run integration tests (requires Docker)"
    echo "  test-all    Run all tests (unit + integration)"
    echo "  help        Show this help"
    echo ""
    echo "Examples:"
    echo "  ./scripts/dev.sh              # Start the app"
    echo "  ./scripts/dev.sh start        # Start the app"
    echo "  ./scripts/dev.sh stop         # Stop the app"
    echo "  ./scripts/dev.sh status       # Check status"
    echo "  ./scripts/dev.sh tunnel       # Start ngrok for Monzo OAuth"
    echo "  ./scripts/dev.sh test         # Run unit tests"
    echo "  ./scripts/dev.sh it           # Run all integration tests"
    echo "  ./scripts/dev.sh it MonzoOAuthFlowIT  # Run specific IT"
    echo ""
    echo "Monzo OAuth Setup:"
    echo "  1. ./scripts/dev.sh tunnel    # Get public URL"
    echo "  2. Update Monzo Developer Portal with ngrok URL"
    echo "  3. Update .env MONZO_REDIRECT_URI"
    echo "  4. ./scripts/dev.sh start     # Start the app"
    echo ""
}

# Main
COMMAND=${1:-start}

case "$COMMAND" in
    start)
        cmd_start
        ;;
    stop)
        cmd_stop
        ;;
    status)
        cmd_status
        ;;
    restart)
        cmd_restart
        ;;
    db)
        cmd_db
        ;;
    test)
        cmd_test
        ;;
    it|integration)
        cmd_it "$2"
        ;;
    test-all)
        cmd_test_all
        ;;
    tunnel|ngrok)
        cmd_tunnel
        ;;
    help|--help|-h)
        cmd_help
        ;;
    *)
        print_error "Unknown command: $COMMAND"
        cmd_help
        exit 1
        ;;
esac
