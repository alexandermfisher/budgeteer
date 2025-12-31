#!/bin/bash
# Development run script for Budgeteer
# Usage: ./run-dev.sh

set -e

# Load SDKMAN if available
if [ -f "$HOME/.sdkman/bin/sdkman-init.sh" ]; then
    source "$HOME/.sdkman/bin/sdkman-init.sh"
fi

# Load .env file if it exists
if [ -f .env ]; then
    echo "📁 Loading environment from .env file..."
    export $(grep -v '^#' .env | xargs)
else
    echo "⚠️  No .env file found. Copy .env.example to .env and fill in your values."
    echo "   cp .env.example .env"
    exit 1
fi

# Check required environment variables
if [ -z "$MONZO_CLIENT_ID" ] || [ "$MONZO_CLIENT_ID" = "oauth2client_xxxxx" ]; then
    echo "❌ MONZO_CLIENT_ID is not set or still has placeholder value"
    exit 1
fi

if [ -z "$MONZO_CLIENT_SECRET" ] || [ "$MONZO_CLIENT_SECRET" = "your-client-secret-here" ]; then
    echo "❌ MONZO_CLIENT_SECRET is not set or still has placeholder value"
    exit 1
fi

if [ -z "$MONZO_REDIRECT_URI" ]; then
    echo "❌ MONZO_REDIRECT_URI is not set"
    exit 1
fi

echo "✅ Environment loaded"
echo "   Client ID: ${MONZO_CLIENT_ID:0:30}..."
echo "   Redirect URI: $MONZO_REDIRECT_URI"
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

echo "🚀 Starting Budgeteer..."
echo ""
mvn spring-boot:run
