mvn #!/bin/bash
# Development run script for Budgeteer
# Usage: ./scripts/dev.sh

set -e

# Get the project root (parent of scripts/)
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

cd "$PROJECT_ROOT"

# Load SDKMAN if available
if [ -f "$HOME/.sdkman/bin/sdkman-init.sh" ]; then
    source "$HOME/.sdkman/bin/sdkman-init.sh"
fi

# Check Java version
JAVA_VERSION=$(java -version 2>&1 | head -1 | cut -d'"' -f2 | cut -d'.' -f1)
echo "☕ Java version: $JAVA_VERSION"
if [ "$JAVA_VERSION" -lt 21 ]; then
    echo "❌ Java 21 or higher required. Current: $JAVA_VERSION"
    exit 1
fi

# Load .env file if it exists
if [ -f .env ]; then
    echo "📁 Loading environment from .env file..."
    # Read each line and export (handles special characters in values)
    while IFS= read -r line || [[ -n "$line" ]]; do
        # Skip empty lines and comments
        if [[ -n "$line" && ! "$line" =~ ^[[:space:]]*# ]]; then
            # Only export lines that look like VAR=value
            if [[ "$line" =~ ^[A-Za-z_][A-Za-z0-9_]*= ]]; then
                export "$line"
            fi
        fi
    done < .env
else
    echo "⚠️  No .env file found. Copy .env.example to .env and fill in your values."
    echo "   cp .env.example .env"
    exit 1
fi

# Check required environment variables - Monzo OAuth
if [ -z "$MONZO_CLIENT_ID" ]; then
    echo "❌ MONZO_CLIENT_ID is not set or still has placeholder value"
    exit 1
fi

if [ -z "$MONZO_CLIENT_SECRET" ]; then
    echo "❌ MONZO_CLIENT_SECRET is not set or still has placeholder value"
    exit 1
fi

if [ -z "$MONZO_REDIRECT_URI" ]; then
    echo "❌ MONZO_REDIRECT_URI is not set"
    exit 1
fi

# Check required environment variables - JWE Authentication
if [ -z "$JWE_SECRET_KEY" ]; then
    echo "⚠️  JWE_SECRET_KEY is not set. Auth will not work."
    echo "   Generate one with: openssl rand -base64 32"
fi

echo "✅ Environment loaded"
echo "   Client ID: ${MONZO_CLIENT_ID:0:30}..."
echo "   Redirect URI: $MONZO_REDIRECT_URI"
echo "   JWE Key: ${JWE_SECRET_KEY:+configured}"
echo ""

# Check if Docker is running and database is up
if ! docker info > /dev/null 2>&1; then
    echo "⚠️  Docker doesn't seem to be running. Start Docker Desktop first."
    exit 1
fi

# Start database if not running
if ! docker ps | grep -q budgeteer-postgres; then
    echo "🐘 Starting PostgreSQL database..."
    docker compose up -d
    echo "⏳ Waiting for database to be ready..."
    sleep 3
fi

echo "🚀 Starting Budgeteer backend..."
echo ""

# Run from the backend directory
cd "$PROJECT_ROOT/backend"
mvn spring-boot:run
