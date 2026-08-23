#!/bin/bash
# Build script for Donut Algorithm mod
# This script builds the mod JAR file

set -e

echo "Building Donut Algorithm mod..."

# Check if Java 21 is available
if ! command -v java &> /dev/null; then
    echo "Error: Java is not installed. Please install Java 21 or later."
    echo "You can use one of the following methods:"
    echo "  1. Install OpenJDK 21: sudo apt install openjdk-21-jdk"
    echo "  2. Use SDKMAN: sdk install java 21.0.2-tem"
    echo "  3. Download from: https://adoptium.net/"
    exit 1
fi

JAVA_VERSION=$(java -version 2>&1 | head -1 | cut -d'"' -f2 | cut -d'.' -f1)
if [ "$JAVA_VERSION" -lt 21 ]; then
    echo "Error: Java 21 or later is required. Found version $JAVA_VERSION"
    exit 1
fi

cd "$(dirname "$0")/Donut-Algorithm"

echo "Running Gradle build..."
chmod +x gradlew
./gradlew build

echo ""
echo "Build complete! JAR file location:"
ls -la build/libs/*.jar

echo ""
echo "To install the mod:"
echo "  1. Install Fabric Loader from https://fabricmc.net/use/"
echo "  2. Install Fabric API from https://modrinth.com/mod/fabric-api"
echo "  3. Copy the JAR file to your .minecraft/mods folder"
echo "  4. Launch Minecraft with the Fabric profile"
echo ""
echo "No API key required! The mod will run in local mode by default."
echo "To use real DonutSMP data, create a file at:"
echo "  config/donut-flip-scanner/api-key.txt"
echo "containing your API key."
